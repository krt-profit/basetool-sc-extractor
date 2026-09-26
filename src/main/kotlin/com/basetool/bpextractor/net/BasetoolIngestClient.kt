package com.basetool.bpextractor.net


import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.ServerClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** The ingest gateway's success answer: where the staged draft was put + the browser landing URL. */
@Serializable
data class IngestResponse(
    val handoffId: String = "",
    val kind: String = "",
    val frontendUrl: String = "",
)

/** RFC 7807 problem body the gateway returns on error — only the fields worth surfacing. */
@Serializable
data class IngestProblem(
    val title: String = "",
    val detail: String = "",
    val status: Int = 0,
    val code: String = "",
    /**
     * Per-field bean-validation messages (e.g. `"orders[0].goods[3].inputQuantity: must not be null"`)
     * carried by the gateway's `VALIDATION_FAILED` problem.
     */
    val fieldErrors: List<String> = emptyList(),
) {
    companion object {
        /**
         * The gateway's stable code for "this client software is not approved" (`REQ-INGEST-011`):
         * the token authenticated fine, but its {@code azp} / scope / payload {@code tool} is not on
         * the server-side allowlist. **Permanent by construction** — the same binary will be refused
         * every time — so it must never be treated as a transient failure worth retrying.
         */
        const val CLIENT_NOT_ALLOWED = "CLIENT_NOT_ALLOWED"
    }
}

/**
 * Signals an ingest send failure; [message] is the already-localized detail, safe to show.
 *
 * @param message the RFC 7807 detail plus field errors
 * @param code the problem's stable `code`, e.g. [IngestProblem.CLIENT_NOT_ALLOWED]; empty when the
 *   answer carried none
 */
class IngestException(message: String, val code: String = "") : Exception(message)

/**
 * Sends the locally produced export JSON to the basetool ingest gateway with a caller-supplied
 * access token; it never authenticates itself. Only `https`, or `http` on `localhost`/`127.0.0.1`,
 * is accepted, with standard TLS trust.
 *
 * A DPoP proof and the `DPoP` authorization scheme are used exactly when Keycloak bound the token
 * (`token_type: DPoP`), otherwise the bearer scheme (REQ-INGEST-012).
 */
class BasetoolIngestClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** This server's clock, learned from its `Date` headers — see [ServerClock]. Its own, not shared. */
    private val clock = ServerClock()

    init {
        require(TransportPolicy.isAllowedServerUrl(baseUrl)) {
            "refusing a non-https ingest base URL (localhost excepted for dev): $baseUrl"
        }
    }

    /**
     * Sends a `RefineryExtract` JSON document and returns the handoff.
     *
     * @param accessToken the token obtained via the device grant
     * @param extractJson the serialized `RefineryExtract`
     * @param acceptLanguage the UI locale to relay, so backend problems are localized
     * @return the gateway handoff (id, kind, frontend URL)
     * @throws IngestException with the gateway's problem detail on any non-2xx / failure
     */
    fun sendRefinery(
        accessToken: String,
        extractJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey?,
    ): IngestResponse = send("/v1/refinery-extract", accessToken, extractJson, acceptLanguage, dpopKey)

    /**
     * Sends a blueprint export JSON document and returns the handoff.
     *
     * @param accessToken the token obtained via the device grant
     * @param blueprintJson the serialized blueprint export
     * @param acceptLanguage the UI locale to relay
     * @return the gateway handoff (id, kind, frontend URL)
     * @throws IngestException with the gateway's problem detail on any non-2xx / failure
     */
    fun sendBlueprint(
        accessToken: String,
        blueprintJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey?,
    ): IngestResponse = send("/v1/blueprint-preview", accessToken, blueprintJson, acceptLanguage, dpopKey)

    private fun send(
        path: String,
        accessToken: String,
        bodyJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey?,
    ): IngestResponse {
        val uri = URI.create(baseUrl.trimEnd('/') + path)
        val response =
            try {
                post(uri, accessToken, bodyJson, acceptLanguage, dpopKey)
            } catch (e: Exception) {
                throw IngestException("could not reach the basetool: ${e.message}")
            }
        if (response.statusCode() in 200..299) {
            return try {
                json.decodeFromString<IngestResponse>(response.body())
            } catch (e: Exception) {
                throw IngestException("the basetool answer was not parseable: ${e.message}")
            }
        }
        val problem =
            try {
                json.decodeFromString<IngestProblem>(response.body())
            } catch (_: Exception) {
                null
            }
        throw IngestException(problemDetail(problem, response.statusCode()), problem?.code.orEmpty())
    }

    private fun post(
        uri: URI,
        accessToken: String,
        bodyJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey?,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header(
                    "Authorization",
                    if (dpopKey == null) "Bearer $accessToken" else "DPoP $accessToken",
                )
                .apply {
                    if (dpopKey != null) {
                        header(
                            "DPoP",
                            dpopKey.proof(htm = "POST", htu = DpopKey.htu(uri), accessToken = accessToken),
                        )
                    }
                }
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Language", acceptLanguage)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build()
        val sentAt = Instant.now()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        clock.observe(response.headers().firstValue("Date").orElse(null), sentAt)
        return response
    }


    /**
     * Extracts the RFC 7807 {@code detail} (already localized); appends the per-field validation
     * messages when present so a generic "Validation failed." names the offending field, and falls
     * back to a generic phrase when the body carries neither.
     */
    private fun problemDetail(problem: IngestProblem?, status: Int): String {
        val detail = problem?.detail?.ifBlank { null }
        val fields = problem?.fieldErrors?.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return when {
            detail != null && fields != null -> "$detail ($fields)"
            detail != null -> detail
            fields != null -> fields
            else -> "the basetool rejected the upload (HTTP $status)"
        }
    }

    companion object {
        private fun defaultHttp(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
