package com.basetool.bpextractor.refinery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One model entry from `GET /api/tags` (installed) or `GET /api/ps` (loaded). */
data class OllamaModel(val name: String, val sizeBytes: Long?, val sizeVramBytes: Long?)

/** The result of one chat read: the raw answer text + why generation stopped. */
data class ChatResult(val text: String, val doneReason: String?)

/**
 * The Ollama surface the refinery pipeline needs — behind an interface so the pipeline, the
 * preflight decision logic and the UI are unit-testable without a running Ollama (master plan
 * Phase 3: "all behind interfaces"). [HttpOllamaClient] is the production implementation.
 */
interface OllamaApi {
    /** Installed model tags (`GET /api/tags`); empty list on a reachable-but-empty install. */
    fun installedModels(): List<OllamaModel>

    /** Currently loaded models with VRAM split (`GET /api/ps`) — the "does it fit" probe. */
    fun loadedModels(): List<OllamaModel>

    /**
     * One vision chat call (`POST /api/chat`): [prompt] + one base64 [imageB64], temperature 0,
     * [numPredict] output budget, [keepAlive] model retention (e.g. `"10m"`, `"0"` to release).
     * [numGpu] = 0 forces CPU-only inference (the below-minimum hardware tier); null keeps
     * Ollama's automatic GPU offload.
     */
    fun chat(model: String, prompt: String, imageB64: String, numPredict: Int, keepAlive: String, numGpu: Int? = null): ChatResult

    /**
     * Pulls [model] (`POST /api/pull`, streaming): [onProgress] receives (completedBytes,
     * totalBytes, status) per progress line. Returns when the pull completes; throws on failure.
     */
    fun pull(model: String, onProgress: (Long?, Long?, String) -> Unit)

    /**
     * Release [model] from (V)RAM now (`POST /api/chat` with an empty message list and
     * `keep_alive 0` — Ollama's documented unload). Unloading a model that is not loaded is a
     * harmless no-op.
     */
    fun unload(model: String)
}

/**
 * Production [OllamaApi] over the JDK's built-in [HttpClient] (no extra dependency; the slim
 * runtime gains the `java.net.http` module for it). The endpoint is configurable — Ollama's
 * default is `http://localhost:11434`. Connect timeouts are short (the preflight must answer
 * quickly); read timeouts are generous (a CPU-mode read takes ~a minute, see
 * `PHASE0_FINDINGS.md` §5).
 */
class HttpOllamaClient(private val endpoint: String = DEFAULT_ENDPOINT) : OllamaApi {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun installedModels(): List<OllamaModel> = modelList("/api/tags", "models")

    override fun loadedModels(): List<OllamaModel> = modelList("/api/ps", "models")

    private fun modelList(path: String, key: String): List<OllamaModel> {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 200) { "GET $path failed: HTTP ${response.statusCode()}" }
        val root = json.parseToJsonElement(response.body()).jsonObject
        return (root[key]?.jsonArray ?: return emptyList()).map { element ->
            val obj = element.jsonObject
            OllamaModel(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                sizeBytes = obj["size"]?.jsonPrimitive?.long,
                sizeVramBytes = obj["size_vram"]?.jsonPrimitive?.long,
            )
        }
    }

    override fun chat(
        model: String,
        prompt: String,
        imageB64: String,
        numPredict: Int,
        keepAlive: String,
        numGpu: Int?,
    ): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("keep_alive", keepAlive)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", prompt)
                            put("images", buildJsonArray { add(imageB64) })
                        },
                    )
                },
            )
            put(
                "options",
                buildJsonObject {
                    put("temperature", 0)
                    put("num_predict", numPredict)
                    put("num_ctx", NUM_CTX)
                    if (numGpu != null) {
                        put("num_gpu", numGpu)
                    }
                },
            )
        }
        val response = sendWithOneRetry(
            HttpRequest.newBuilder(URI.create("$endpoint/api/chat"))
                .timeout(Duration.ofMinutes(CHAT_TIMEOUT_MINUTES))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
        )
        check(response.statusCode() == 200) { "POST /api/chat failed: HTTP ${response.statusCode()} ${response.body()}" }
        val root = json.parseToJsonElement(response.body()).jsonObject
        return ChatResult(
            text = root["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: "",
            doneReason = root["done_reason"]?.jsonPrimitive?.content,
        )
    }

    override fun unload(model: String) {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {})
            put("keep_alive", 0)
            put("stream", false)
        }
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$endpoint/api/chat"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 200) { "POST /api/chat (unload) failed: HTTP ${response.statusCode()}" }
    }

    /**
     * One retry on a TRANSIENT transport failure (connection reset/refused mid-batch — e.g. an
     * Ollama restart between reads). HTTP error answers are never retried: the server spoke, the
     * caller's status check is the right reaction. The request body publisher is reusable.
     */
    private fun sendWithOneRetry(request: HttpRequest): HttpResponse<String> =
        try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: java.io.IOException) {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        }

    override fun pull(model: String, onProgress: (Long?, Long?, String) -> Unit) {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
        }
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$endpoint/api/pull"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        check(response.statusCode() == 200) { "POST /api/pull failed: HTTP ${response.statusCode()}" }
        response.body().bufferedReader().use { reader: BufferedReader ->
            reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val obj: JsonObject = json.parseToJsonElement(line).jsonObject
                obj["error"]?.jsonPrimitive?.content?.let { error("ollama pull failed: $it") }
                onProgress(
                    obj["completed"]?.jsonPrimitive?.long,
                    obj["total"]?.jsonPrimitive?.long,
                    obj["status"]?.jsonPrimitive?.content ?: "",
                )
            }
        }
    }

    companion object {
        /** Ollama's default local endpoint. */
        const val DEFAULT_ENDPOINT = "http://localhost:11434"

        /**
         * Pinned context window (PHASE0_FINDINGS §10 item 2 / 2026-06-12 addendum). Measured on
         * the golden set: a worst-case read (1536 px panel crop) is ~2018 prompt tokens (text +
         * vision) + ~250 output tokens, so 12288 holds prompt + the full retry output budget
         * (`PanelReader.NUM_PREDICT_RETRY` = 8192) with margin, while Ollama's 32k default
         * wastes VRAM on KV-cache: 8b 9.5 → 6.7 GB, 4b 7.4 → 4.5 GB loaded. That is the real
         * headroom for the 12 GB (recommended) and 8 GB (minimum) tiers.
         */
        const val NUM_CTX = 12288

        /** Generous read budget: a CPU-mode panel read takes ~a minute (PHASE0_FINDINGS §5). */
        private const val CHAT_TIMEOUT_MINUTES = 10L
    }
}
