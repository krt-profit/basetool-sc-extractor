package com.basetool.bpextractor.net.auth

import com.basetool.bpextractor.net.TransportPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** The Keycloak device-authorization answer — only the fields the flow needs. */
@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String = "",
    @SerialName("expires_in") val expiresIn: Long = 600,
    /** Minimum seconds between token polls; Keycloak default is 5. */
    val interval: Long = 5,
) {
    /** The URL to open in the browser: the complete form (with the code) when present. */
    fun browserUrl(): String = verificationUriComplete.ifBlank { verificationUri }
}

/** The token answer (or, on the error path, [TokenErrorResponse]). */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
) {
    /**
     * Whether the server bound this token to the DPoP key, i.e. answered `token_type` `DPoP`
     * (case-insensitive, RFC 9449 §5).
     *
     * @return `true` when the token must be presented under the `DPoP` scheme
     */
    fun isDpopBound(): Boolean = tokenType.equals("DPoP", ignoreCase = true)
}

/** The OAuth2 error body returned by the token endpoint while/if the grant is not (yet) ready. */
@Serializable
data class TokenErrorResponse(
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)

/**
 * Signals that the device-grant flow could not complete; [message] is safe to show (no token).
 *
 * @param message the already-safe detail to surface
 * @param oauthError the OAuth2 `error` code the server named; only [TOKEN_REJECTED] means the stored
 *   grant is dead. Empty for transport failures and unparseable answers
 * @param clockOffsetSeconds the measured clock deviation from the server when large enough to be the
 *   plausible cause ([ServerClock.REPORTABLE_SECONDS]) and the corrected retry did not help; `0`
 *   otherwise
 */
class DeviceGrantException(
    message: String,
    val oauthError: String = "",
    val clockOffsetSeconds: Long = 0,
) : Exception(message) {
    companion object {
        /**
         * The OAuth2 error codes that mean the presented token is unusable and worth deleting
         * (RFC 6749 §5.2): expired, revoked, or reuse-detected. Everything else — a proof rejection,
         * a 5xx, a dropped connection — leaves the stored credential alone.
         */
        val TOKEN_REJECTED = setOf("invalid_grant", "invalid_token")
    }
}

/**
 * OAuth2 Device Authorization Grant (RFC 8628) client for the public Keycloak client
 * `basetool-sc-extractor`: requests a device and user code and polls the token endpoint until the
 * user approves.
 *
 * Every token-endpoint request may carry a DPoP proof from the caller's [DpopKey], which binds the
 * issued access and refresh token to it; `null` requests plain bearer tokens (REQ-INGEST-012).
 */
class DeviceGrantClient(
    private val issuer: String = PROD_ISSUER,
    private val clientId: String = CLIENT_ID,
    private val http: HttpClient = defaultHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val deviceEndpoint = "$issuer/protocol/openid-connect/auth/device"
    private val tokenEndpoint = "$issuer/protocol/openid-connect/token"

    /**
     * This authorization server's clock, learned from its `Date` headers. Keycloak's `iat` window is
     * the tightest in the system (−25s…+15s), so this is what keeps a desktop with a drifting clock
     * able to authenticate at all. It belongs to this server alone — clocks, like nonces, are not
     * shared between the authorization server and the gateway.
     */
    private val clock = ServerClock()

    init {
        require(TransportPolicy.isAllowedServerUrl(issuer)) {
            "refusing a non-https issuer (localhost excepted for dev): $issuer"
        }
    }

    /**
     * Starts the flow: asks Keycloak for a device + user code. The caller shows
     * [DeviceCodeResponse.userCode] and opens [DeviceCodeResponse.browserUrl].
     *
     * @return the device-code grant details
     * @throws DeviceGrantException when the request fails or the answer is unparseable
     */
    fun requestDeviceCode(): DeviceCodeResponse {
        val form =
            encodeForm(
                "client_id" to clientId,
                "scope" to "openid $INGEST_SCOPE",
            )
        val request =
            HttpRequest.newBuilder(URI.create(deviceEndpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response =
            try {
                val sentAt = Instant.now()
                http.send(request, HttpResponse.BodyHandlers.ofString()).also { observeClock(it, sentAt) }
            } catch (e: Exception) {
                throw DeviceGrantException("device-authorization request failed: ${e.message}")
            }
        if (response.statusCode() != 200) {
            throw DeviceGrantException("device-authorization request failed: HTTP ${response.statusCode()}")
        }
        return try {
            json.decodeFromString<DeviceCodeResponse>(response.body())
        } catch (e: Exception) {
            throw DeviceGrantException("device-authorization answer was not parseable: ${e.message}")
        }
    }

    /**
     * Polls the token endpoint until the user approves, the device code expires, or the user denies;
     * `authorization_pending` keeps polling and `slow_down` widens the interval.
     *
     * @param device the device-code grant from [requestDeviceCode]
     * @param sleep how to wait between polls, in seconds
     * @param nowMillis the clock; defaults to `System.currentTimeMillis`
     * @param dpopKey the key to bind the issued tokens to, or `null` for a plain bearer grant
     * @return the token answer once granted
     * @throws DeviceGrantException on denial, expiry, or an unexpected error
     */
    fun pollForToken(
        device: DeviceCodeResponse,
        sleep: (Long) -> Unit = { seconds -> Thread.sleep(seconds * 1000L) },
        nowMillis: () -> Long = { System.currentTimeMillis() },
        dpopKey: DpopKey? = null,
    ): TokenResponse {
        var intervalSeconds = device.interval.coerceAtLeast(1)
        val deadline = nowMillis() + device.expiresIn * 1000L
        while (nowMillis() < deadline) {
            sleep(intervalSeconds)
            val form =
                encodeForm(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to device.deviceCode,
                    "client_id" to clientId,
                )
            val response =
                try {
                    postToToken(form, dpopKey)
                } catch (e: Exception) {
                    throw DeviceGrantException("token request failed: ${e.message}")
                }
            if (response.statusCode() == 200) {
                return try {
                    json.decodeFromString<TokenResponse>(response.body())
                } catch (e: Exception) {
                    throw DeviceGrantException("token answer was not parseable: ${e.message}")
                }
            }
            when (val error = parseError(response.body())) {
                "authorization_pending" -> {}
                "slow_down" -> intervalSeconds += 5
                "expired_token" ->
                    throw DeviceGrantException("the approval window expired — please try again")
                "access_denied" -> throw DeviceGrantException("the request was denied")
                else ->
                    throw DeviceGrantException(describeError(response), error, reportableClockOffset())
            }
        }
        throw DeviceGrantException("the approval window expired — please try again")
    }

    /**
     * Exchanges a stored refresh token for a fresh access token and, with rotation, a new refresh token.
     * A DPoP-bound token needs a proof from the key it was bound to.
     *
     * @param refreshToken the persisted refresh token
     * @param dpopKey the key the stored token is bound to, or `null` for an unbound token
     * @return the new token answer; its `refreshToken` is the rotated one to re-persist
     * @throws DeviceGrantException when the refresh is rejected or the answer is unparseable
     */
    fun refreshAccessToken(refreshToken: String, dpopKey: DpopKey? = null): TokenResponse {
        val form =
            encodeForm(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
        val response =
            try {
                postToToken(form, dpopKey)
            } catch (e: Exception) {
                throw DeviceGrantException("token refresh failed: ${e.message}")
            }
        if (response.statusCode() != 200) {
            throw DeviceGrantException(
                "token refresh rejected — ${describeError(response)}",
                parseError(response.body()),
                reportableClockOffset(),
            )
        }
        return try {
            json.decodeFromString<TokenResponse>(response.body())
        } catch (e: Exception) {
            throw DeviceGrantException("token-refresh answer was not parseable: ${e.message}")
        }
    }

    /**
     * Revokes a refresh token at Keycloak's RFC 7009 revocation endpoint, best-effort: failures are
     * swallowed. A DPoP proof is sent when [dpopKey] is given.
     *
     * @param refreshToken the refresh token to revoke
     * @param dpopKey the key the token is bound to, or `null` for an unbound token
     */
    fun revoke(refreshToken: String, dpopKey: DpopKey? = null) {
        val form =
            encodeForm(
                "token" to refreshToken,
                "token_type_hint" to "refresh_token",
                "client_id" to clientId,
            )
        try {
            postForm("$issuer/protocol/openid-connect/revoke", form, dpopKey)
        } catch (_: Exception) {
        }
    }

    /** [postForm] against the token endpoint — the one URL all three grant calls share. */
    private fun postToToken(form: String, dpopKey: DpopKey?): HttpResponse<String> =
        postForm(tokenEndpoint, form, dpopKey)

    /**
     * POSTs a form-encoded body, carrying a fresh DPoP proof when [dpopKey] is given. Retries exactly
     * once, and only when the rejection materially corrected the measured server-clock offset; a nonce
     * challenge is never retried ([DpopNonce]).
     */
    private fun postForm(endpoint: String, form: String, dpopKey: DpopKey?): HttpResponse<String> {
        val offsetBefore = clock.offsetSeconds()
        val response = postOnce(endpoint, form, dpopKey)
        if (dpopKey == null) return response
        val clockJustCorrected =
            (response.statusCode() == 400 || response.statusCode() == 401) &&
                abs(clock.offsetSeconds() - offsetBefore) >= ServerClock.MATERIAL_SECONDS
        return if (clockJustCorrected) postOnce(endpoint, form, dpopKey) else response
    }

    private fun postOnce(endpoint: String, form: String, dpopKey: DpopKey?): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
        if (dpopKey != null) {
            builder.header(
                "DPoP",
                dpopKey.proof(
                    htm = "POST",
                    htu = DpopKey.htu(URI.create(endpoint)),
                    issuedAt = clock.now(),
                ),
            )
        }
        val sentAt = Instant.now()
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        observeClock(response, sentAt)
        if (dpopKey != null && parseError(response.body()) == DpopNonce.USE_DPOP_NONCE) {
            DpopNonce.reportChallenge(endpoint)
        }
        return response
    }

    private fun observeClock(response: HttpResponse<*>, sentAt: Instant) {
        clock.observe(response.headers().firstValue("Date").orElse(null), sentAt)
    }

    /**
     * The measured clock offset when it is large enough to explain a rejected proof, else `0`; used
     * only on terminal failure paths after the corrected retry.
     */
    private fun reportableClockOffset(): Long =
        clock.offsetSeconds().takeIf { abs(it) >= ServerClock.REPORTABLE_SECONDS } ?: 0L

    /**
     * Describes a rejected token request safely as the server's OAuth2 `error` and `error_description`,
     * falling back to the HTTP status. Neither field carries a token or key.
     */
    private fun describeError(response: HttpResponse<String>): String {
        val parsed =
            try {
                json.decodeFromString<TokenErrorResponse>(response.body())
            } catch (_: Exception) {
                null
            }
        val error = parsed?.error?.ifBlank { null }
        val description = parsed?.errorDescription?.ifBlank { null }
        val detail = listOfNotNull(error, description).joinToString(": ")
        return if (detail.isBlank()) {
            "authentication failed (HTTP ${response.statusCode()})"
        } else {
            "$detail (HTTP ${response.statusCode()})"
        }
    }

    private fun parseError(body: String): String =
        try {
            json.decodeFromString<TokenErrorResponse>(body).error
        } catch (_: Exception) {
            ""
        }

    /** OAuth2 form bodies are {@code x-www-form-urlencoded}; encode each key and value. */
    private fun encodeForm(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }

    companion object {
        /**
         * The production Keycloak realm issuer, served under `/auth` on the web origin (ADR-0166). It is the
         * only copy of the identity base, so moving the identity host requires a release.
         */
        const val PROD_ISSUER = "https://profit-base.online/auth/realms/iri"

        /**
         * The public device-grant client id in Keycloak. The ingest gateway accepts only tokens whose `azp`
         * is on its allowlist (REQ-INGEST-011), so a new id must be allowlisted before it ships.
         */
        const val CLIENT_ID = "basetool-sc-extractor"

        /** The client scope that stamps `aud=basetool-backend` on the token. */
        const val INGEST_SCOPE = "extractor-ingest"

        private fun defaultHttp(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
