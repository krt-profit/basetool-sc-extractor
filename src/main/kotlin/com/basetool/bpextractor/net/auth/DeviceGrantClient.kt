package com.basetool.bpextractor.net.auth

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
)

/** The OAuth2 error body returned by the token endpoint while/if the grant is not (yet) ready. */
@Serializable
data class TokenErrorResponse(
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)

/** Signals that the device-grant flow could not complete; [message] is safe to show (no token). */
class DeviceGrantException(message: String) : Exception(message)

/**
 * OAuth2 Device Authorization Grant (RFC 8628) client for Keycloak (epic
 * krt-iri/basetool#639, sub-issue #641's public client `basetool-sc-extractor`). It requests a
 * device + user code, the UI shows the user code and opens the verification URL in the browser,
 * and this client polls the token endpoint until the user approves — one click under an active
 * SSO session.
 *
 * <p>Mirrors {@code UpdateChecker}'s HTTP discipline: a shared timeout-bounded [HttpClient],
 * https-only, kotlinx-serialization with {@code ignoreUnknownKeys}. The realm issuer is a hardcoded
 * **prod** constant — only the ingest base URL is configurable (it lives in the ingest client) —
 * but the constructor accepts an override so tests can point at a local stand-in. No secret is held
 * (public client, no client secret); PKCE is not used because the device-code itself is the
 * proof-of-possession in this grant.
 */
class DeviceGrantClient(
    private val issuer: String = PROD_ISSUER,
    private val clientId: String = CLIENT_ID,
    private val http: HttpClient = defaultHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val deviceEndpoint = "$issuer/protocol/openid-connect/auth/device"
    private val tokenEndpoint = "$issuer/protocol/openid-connect/token"

    init {
        require(issuer.startsWith("https://") || issuer.startsWith("http://localhost")) {
            "refusing a non-https issuer: $issuer"
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
                http.send(request, HttpResponse.BodyHandlers.ofString())
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
     * Polls the token endpoint until the user approves, the device code expires, or the user
     * denies. Honors the OAuth2 device-grant signals: {@code authorization_pending} keeps polling,
     * {@code slow_down} widens the interval, anything else is terminal.
     *
     * @param device the device-code grant from [requestDeviceCode]
     * @param sleep how to wait between polls (injected so tests run without real delays); seconds
     * @param nowMillis the clock (injected for tests); defaults to {@link System#currentTimeMillis}
     * @return the token answer once granted
     * @throws DeviceGrantException on denial, expiry, or an unexpected error
     */
    fun pollForToken(
        device: DeviceCodeResponse,
        sleep: (Long) -> Unit = { seconds -> Thread.sleep(seconds * 1000L) },
        nowMillis: () -> Long = { System.currentTimeMillis() },
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
            val request =
                HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build()
            val response =
                try {
                    http.send(request, HttpResponse.BodyHandlers.ofString())
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
            when (parseError(response.body())) {
                "authorization_pending" -> {} // keep waiting
                "slow_down" -> intervalSeconds += 5
                "expired_token" ->
                    throw DeviceGrantException("the approval window expired — please try again")
                "access_denied" -> throw DeviceGrantException("the request was denied")
                else ->
                    throw DeviceGrantException("authentication failed (HTTP ${response.statusCode()})")
            }
        }
        throw DeviceGrantException("the approval window expired — please try again")
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
        /** Prod Keycloak realm issuer (hardcoded per #645; only the ingest base URL is config). */
        const val PROD_ISSUER = "https://keycloak.profit-base.online/realms/iri"

        /** The public device-grant client provisioned in Keycloak (#641). */
        const val CLIENT_ID = "basetool-sc-extractor"

        /** The client scope that stamps {@code aud=basetool-backend} on the token (#641). */
        const val INGEST_SCOPE = "extractor-ingest"

        private fun defaultHttp(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
