package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.BuildInfo
import com.basetool.bpextractor.net.TransportPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/** What the registry currently serves under a model tag: its manifest digest and total download size. */
data class RegistryVersion(val digest: String, val sizeBytes: Long?)

/** The public model registry Ollama pulls from, asked which version a tag points to now. */
fun interface ModelRegistry {
    /** The version [model] (`name:tag`) points to now, or `null` when the registry cannot say. */
    fun latest(model: String): RegistryVersion?
}

/**
 * [ModelRegistry] over the registry's manifest endpoint. Ollama's local model digest is the SHA-256 of
 * exactly these manifest bytes, so equal digests mean the installed model is the registry's current one.
 * Only public manifests are read; nothing is sent beyond the model name.
 */
class HttpModelRegistry(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = "BasetoolSCExtractor/${BuildInfo.VERSION}",
) : ModelRegistry {

    init {
        require(TransportPolicy.isAllowedServerUrl(baseUrl)) { "registry URL refused by the transport policy" }
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun latest(model: String): RegistryVersion? = runCatching {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/v2/${manifestPath(model)}"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", MANIFEST_MEDIA_TYPE)
                .header("User-Agent", userAgent)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() != 200) return@runCatching null
        val bytes = response.body()
        val manifest = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val parts = listOfNotNull(manifest["config"]?.jsonObject) + (manifest["layers"]?.jsonArray?.map { it.jsonObject } ?: emptyList())
        val sizes = parts.map { it["size"]?.jsonPrimitive?.long }
        RegistryVersion(
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            sizeBytes = if (sizes.isEmpty() || sizes.any { it == null }) null else sizes.sumOf { it!! },
        )
    }.getOrNull()

    companion object {
        /** The registry `ollama pull` uses. */
        const val DEFAULT_BASE_URL = "https://registry.ollama.ai"

        private const val MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json"

        /**
         * The manifest path of [model]: `library/<name>/manifests/<tag>` for an official model, the
         * namespace kept for `user/name`, and `latest` when no tag is given.
         */
        fun manifestPath(model: String): String {
            val name = model.substringBefore(':')
            val tag = model.substringAfter(':', "latest").ifEmpty { "latest" }
            val repository = if ('/' in name) name else "library/$name"
            return "$repository/manifests/$tag"
        }
    }
}
