package com.basetool.bpextractor.net

import com.basetool.bpextractor.BuildInfo
import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopNonce
import com.basetool.bpextractor.net.auth.ServerClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * The handoff a draft route answers with (`draft-result.schema.json`).
 *
 * @param frontendUrl the Basetool page the member reviews the draft on
 * @param handoffId the single-use handoff id; a secret, never logged
 * @param kind `BLUEPRINT` or `REFINERY`
 */
@Serializable
data class DraftResult(
    val frontendUrl: String = "",
    val handoffId: String = "",
    val kind: String = "",
)

/**
 * An installation as the server knows it (`installation.schema.json`).
 *
 * @param label the label the member chose
 * @param installationId the server's opaque id of this installation
 */
@Serializable
data class Installation(val label: String = "", val installationId: String? = null)

/**
 * One field a `SCHEMA_INVALID` problem points at.
 *
 * @param pointer the JSON Pointer into the request
 * @param message what is wrong with it
 */
@Serializable
data class ProblemError(val pointer: String = "", val message: String? = null)

/**
 * An exchange error answer (RFC 9457, `problem.schema.json`) — only the fields worth surfacing.
 *
 * @param title the short summary
 * @param detail the human-readable detail, safe to show
 * @param status the HTTP status
 * @param code the stable code from the exchange error registry
 * @param retryAfterSeconds how long to wait before a retry, when the server says
 * @param confirmationUrl where the member confirms a staged mass change
 * @param errors the fields a `SCHEMA_INVALID` problem points at
 * @param correlationId the request's id, the same value as the `X-Correlation-Id` header
 */
@Serializable
data class ExchangeProblem(
    val title: String = "",
    val detail: String = "",
    val status: Int = 0,
    val code: String = "",
    val retryAfterSeconds: Int? = null,
    val confirmationUrl: String? = null,
    val errors: List<ProblemError> = emptyList(),
    val correlationId: String? = null,
)

/**
 * A failed exchange request; [message] is safe to show.
 *
 * @param message the problem's detail plus the fields it points at, or a transport failure
 * @param status the HTTP status, `0` when the server was not reached
 * @param code the problem's stable code (see [Codes]), empty when the answer carried none
 * @param confirmationUrl where the member confirms a staged mass change, for `MASS_CHANGE_CONFIRMATION_REQUIRED`
 * @param correlationId the request's `X-Correlation-Id`, for a problem report; empty when unknown
 * @param retryAfterSeconds the answer's `Retry-After`, or `null` when it carried none
 */
class ExchangeException(
    message: String,
    val status: Int = 0,
    val code: String = "",
    val confirmationUrl: String? = null,
    val correlationId: String = "",
    val retryAfterSeconds: Long? = null,
) : Exception(message)

/**
 * The service document (`GET /exchange/v1`, `service-document.schema.json`) — the fields the extractor
 * acts on.
 *
 * @param capabilities the exchange scopes this token may use, granted by both the token and the registry
 * @param minClientVersion the registry's minimum version for this client, or `null`
 * @param installationId the server's opaque id of this installation
 */
@Serializable
data class ServiceDocument(
    val capabilities: List<String> = emptyList(),
    val minClientVersion: String? = null,
    val installationId: String? = null,
)

/** The exchange error codes this client acts on (`docs/exchange/errors.md`). */
object Codes {
    /** The token is missing, invalid, expired, or not issued for the gateway; refresh once and retry. */
    const val UNAUTHENTICATED = "UNAUTHENTICATED"

    /** The member has not accepted the current terms of use. */
    const val TERMS_NOT_ACCEPTED = "TERMS_NOT_ACCEPTED"

    /** The member's registration awaits approval. */
    const val PENDING_APPROVAL = "PENDING_APPROVAL"

    /** The member may not use the Basetool this way: no role, unknown or disabled, or not permitted. */
    val ACCOUNT_REFUSED: Set<String> = setOf("NO_ROLE", "ACTING_MEMBER_REFUSED", "NOT_PERMITTED")

    /** A per-minute limit or the member's cap of live DPoP proofs is used up. */
    val SLOW_DOWN: Set<String> = setOf("RATE_LIMITED", "DPOP_PROOF_LIMIT")

    /** The daily write quota is used up. */
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"

    /** The exchange cannot serve the request right now; retry later. */
    val UNAVAILABLE: Set<String> = setOf(
        "EXCHANGE_DISABLED",
        "REGISTRY_UNAVAILABLE",
        "EXCHANGE_BUDGET_EXHAUSTED",
        "SERVICE_UNAVAILABLE",
        "BACKEND_RELAY_FAILED",
        "IDEMPOTENCY_IN_PROGRESS",
        "INTERNAL_ERROR",
    )

    /** The Basetool refused what was sent. */
    val REJECTED: Set<String> = setOf(
        "SCHEMA_INVALID",
        "PAYLOAD_TOO_LARGE",
        "BATCH_TOO_LARGE",
        "IDEMPOTENCY_KEY_MISSING",
        "IDEMPOTENCY_KEY_REUSED",
        "UNSUPPORTED_MEDIA_TYPE",
    )

    /** The client is not in the registry. */
    const val CLIENT_NOT_ALLOWED = "CLIENT_NOT_ALLOWED"

    /** The client is suspended in the registry. */
    const val CLIENT_SUSPENDED = "CLIENT_SUSPENDED"

    /** The member disconnected this client after the token was issued. */
    const val CLIENT_REVOKED = "CLIENT_REVOKED"

    /** The member disconnected this installation; its DPoP key is refused for good. */
    const val INSTALLATION_REVOKED = "INSTALLATION_REVOKED"

    /** This release is below the client's minimum version. */
    const val CLIENT_VERSION_UNSUPPORTED = "CLIENT_VERSION_UNSUPPORTED"

    /** The route's capability is not in the token or not granted to the client. */
    const val SCOPE_MISSING = "SCOPE_MISSING"

    /** The proof is invalid, replayed, for another key, or lacks the server nonce. */
    const val DPOP_INVALID = "DPOP_INVALID"

    /** The batch exceeds the mass-change guard and waits for the member's confirmation. */
    const val MASS_CHANGE_CONFIRMATION_REQUIRED = "MASS_CHANGE_CONFIRMATION_REQUIRED"

    /** Raised by the extractor itself: the [Backoff] still runs, so nothing was sent. */
    const val BACKING_OFF = "BACKING_OFF"
}

/**
 * The exchange API client (`/exchange/v1`, REQ-XCH-001) the extractor sends its drafts and syncs through.
 * It never authenticates itself: every call takes a DPoP-bound token and the key it is bound to, and
 * carries a proof with the token's hash and the server's current nonce (REQ-XCH-006), the extractor's
 * `User-Agent` (REQ-XCH-024) and, on a write, a fresh `Idempotency-Key` (REQ-XCH-020). A request the
 * server refuses for a missing nonce, or for a proof the corrected clock would have passed, is sent
 * again exactly once, with the same idempotency key. Only `https`, or `http` to loopback, is accepted.
 *
 * @param baseUrl the ingest gateway's base URL, without `/exchange/v1`
 * @param http the HTTP client
 * @param userAgent the `User-Agent` to send
 */
class ExchangeClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultHttp(),
    private val userAgent: String = USER_AGENT,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** This server's clock, learned from its `Date` headers — see [ServerClock]. */
    private val clock = ServerClock()

    /** This server's DPoP nonce. */
    private val nonce = DpopNonce()

    init {
        require(TransportPolicy.isAllowedServerUrl(baseUrl)) {
            "refusing a non-https ingest base URL (localhost excepted for dev): $baseUrl"
        }
    }

    /**
     * Stages a blueprint envelope for review in the browser (`POST /me/drafts/blueprints`).
     *
     * @param credentials the member's token and its key
     * @param envelopeJson the serialized `basetool.blueprints` envelope
     * @param acceptLanguage the UI locale to relay
     * @return the handoff to open
     * @throws ExchangeException on any refusal or transport failure
     */
    fun draftBlueprints(credentials: ExchangeCredentials, envelopeJson: String, acceptLanguage: String): DraftResult =
        decode(post("/me/drafts/blueprints", envelopeJson, credentials, acceptLanguage, write = true))

    /**
     * Stages a refinery extract for review in the browser (`POST /me/drafts/refinery-orders`).
     *
     * @param credentials the member's token and its key
     * @param extractJson the serialized `RefineryExtract`
     * @param acceptLanguage the UI locale to relay
     * @return the handoff to open
     * @throws ExchangeException on any refusal or transport failure
     */
    fun draftRefinery(credentials: ExchangeCredentials, extractJson: String, acceptLanguage: String): DraftResult =
        decode(post("/me/drafts/refinery-orders", extractJson, credentials, acceptLanguage, write = true))

    /**
     * Labels this installation (`POST /me/installation`, REQ-XCH-007).
     *
     * @param credentials the member's token and its key
     * @param label the label the member chose; see [InstallationLabel]
     * @param acceptLanguage the UI locale to relay
     * @return the installation as the server knows it
     * @throws ExchangeException on any refusal or transport failure
     */
    fun labelInstallation(credentials: ExchangeCredentials, label: String, acceptLanguage: String): Installation =
        decode(
            post(
                "/me/installation",
                json.encodeToString(Installation.serializer(), Installation(label)),
                credentials,
                acceptLanguage,
                write = false,
            ),
        )

    /**
     * Reads the service document (`GET /exchange/v1`): which capabilities this token may use and the
     * client's minimum version.
     *
     * @param credentials the member's token and its key
     * @param acceptLanguage the UI locale to relay
     * @return the service document
     * @throws ExchangeException on any refusal or transport failure
     */
    fun serviceDocument(credentials: ExchangeCredentials, acceptLanguage: String): ServiceDocument =
        decode(get("", null, credentials, acceptLanguage))

    /**
     * Sends an authenticated `GET` and returns the answer's body.
     *
     * @param path the path below `/exchange/v1`
     * @param query the encoded query string, or `null`
     * @param credentials the member's token and its key
     * @param acceptLanguage the UI locale to relay
     * @return the `2xx` answer's body
     * @throws ExchangeException on any refusal or transport failure
     */
    internal fun get(path: String, query: String?, credentials: ExchangeCredentials, acceptLanguage: String): String =
        exchange("GET", path, query, null, credentials, acceptLanguage, null)

    /**
     * Sends an authenticated `POST` of a JSON body and returns the answer's body.
     *
     * @param path the path below `/exchange/v1`
     * @param body the JSON body
     * @param credentials the member's token and its key
     * @param acceptLanguage the UI locale to relay
     * @param write whether the route is a write route, which carries an `Idempotency-Key`
     * @return the `2xx` answer's body
     * @throws ExchangeException on any refusal or transport failure
     */
    internal fun post(
        path: String,
        body: String,
        credentials: ExchangeCredentials,
        acceptLanguage: String,
        write: Boolean,
    ): String =
        exchange("POST", path, null, body, credentials, acceptLanguage, if (write) UUID.randomUUID().toString() else null)

    /**
     * Decodes an answer body.
     *
     * @throws ExchangeException when it does not parse
     */
    internal inline fun <reified T> decode(body: String): T =
        try {
            jsonCodec().decodeFromString<T>(body)
        } catch (e: Exception) {
            throw ExchangeException("the basetool answer was not parseable: ${e.message}")
        }

    /** The JSON codec, shared with the callers that build request bodies. */
    internal fun jsonCodec(): Json = json

    private fun exchange(
        method: String,
        path: String,
        query: String?,
        body: String?,
        credentials: ExchangeCredentials,
        acceptLanguage: String,
        idempotencyKey: String?,
    ): String {
        val uri = URI.create(baseUrl.trimEnd('/') + PREFIX + path + (query?.let { "?$it" } ?: ""))
        val offsetBefore = clock.offsetSeconds()
        var response = send(method, uri, body, credentials, acceptLanguage, idempotencyKey)
        if (response.statusCode() == 401) {
            val challenged = response.headers().allValues("WWW-Authenticate").any { DpopNonce.USE_DPOP_NONCE in it }
            val clockCorrected = abs(clock.offsetSeconds() - offsetBefore) >= ServerClock.MATERIAL_SECONDS
            if ((challenged && nonce.current() != null) || clockCorrected) {
                response = send(method, uri, body, credentials, acceptLanguage, idempotencyKey)
            }
        }
        if (response.statusCode() in 200..299) return response.body()
        val problem =
            try {
                json.decodeFromString<ExchangeProblem>(response.body())
            } catch (_: Exception) {
                null
            }
        throw ExchangeException(
            describe(problem, response.statusCode()),
            response.statusCode(),
            problem?.code.orEmpty(),
            problem?.confirmationUrl,
            response.headers().firstValue(CORRELATION_HEADER).orElse(null)?.takeIf { CORRELATION_ID.matches(it) }
                ?: problem?.correlationId?.takeIf { CORRELATION_ID.matches(it) }
                ?: "",
            (response.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()
                ?: problem?.retryAfterSeconds?.toLong())?.takeIf { it >= 0 },
        )
    }

    private fun send(
        method: String,
        uri: URI,
        body: String?,
        credentials: ExchangeCredentials,
        acceptLanguage: String,
        idempotencyKey: String?,
    ): HttpResponse<String> {
        val proof =
            credentials.key.proof(
                htm = method,
                htu = DpopKey.htu(uri),
                accessToken = credentials.accessToken,
                issuedAt = clock.now(),
                nonce = nonce.current(),
            )
        val builder =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "DPoP ${credentials.accessToken}")
                .header("DPoP", proof)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", acceptLanguage)
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        if (body == null) {
            builder.GET()
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        }
        val sentAt = Instant.now()
        val response =
            try {
                http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw ExchangeException("could not reach the basetool: ${e.message}")
            }
        clock.observe(response.headers().firstValue("Date").orElse(null), sentAt)
        nonce.observe(response.headers().firstValue(DpopNonce.HEADER).orElse(null))
        return response
    }

    /**
     * The problem's detail with the fields it points at, else its code, else a generic phrase; none of
     * them carries a token.
     */
    private fun describe(problem: ExchangeProblem?, status: Int): String {
        val detail = problem?.detail?.ifBlank { null }
        val fields =
            problem?.errors
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { e -> listOfNotNull(e.pointer.ifBlank { null }, e.message).joinToString(": ") }
        val code = problem?.code?.ifBlank { null }
        return when {
            detail != null && fields != null -> "$detail ($fields)"
            detail != null -> detail
            fields != null -> fields
            code != null -> "the basetool refused the request ($code, HTTP $status)"
            else -> "the basetool refused the request (HTTP $status)"
        }
    }

    companion object {
        /** The exchange API's path prefix on the gateway. */
        const val PREFIX = "/exchange/v1"

        /** The header every exchange answer carries with the request's id. */
        const val CORRELATION_HEADER = "X-Correlation-Id"

        /** The shape a correlation id has; anything else is not shown. */
        private val CORRELATION_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

        /** The `User-Agent` the minimum-version gate reads: product, version and the project URL. */
        val USER_AGENT = "BasetoolSCExtractor/${BuildInfo.VERSION} (+https://github.com/krt-profit/basetool-sc-extractor)"

        private fun defaultHttp(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}

/**
 * A DPoP-bound access token together with the key its proofs are signed by. Not a data class, so no
 * generated `toString` prints the token.
 *
 * @param accessToken the access token
 * @param key the key the token is bound to
 */
class ExchangeCredentials(val accessToken: String, val key: DpopKey)

/** The installation label rule of `installation.schema.json` (REQ-XCH-007). */
object InstallationLabel {
    private val PATTERN = Regex("""^[\p{L}\p{N}._-][\p{L}\p{N} ._-]{0,39}$""")

    /**
     * Whether the server accepts [label]: at most 40 letters, digits, spaces, `-`, `_` and `.`, not
     * starting with a space.
     *
     * @param label the label, already trimmed
     * @return `true` when it is valid
     */
    fun isValid(label: String): Boolean = PATTERN.matches(label)
}
