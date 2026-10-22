package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.net.RawHttpServer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [HttpModelRegistry]: the manifest digest Ollama stores locally, and the download size. */
class HttpModelRegistryTest {

    private val manifest =
        """{"schemaVersion":2,"config":{"digest":"sha256:c","size":177},""" +
            """"layers":[{"digest":"sha256:a","size":921704832},{"digest":"sha256:b","size":5629109120}]}"""

    @Test
    fun `the digest is the SHA-256 of the manifest bytes and the size sums config and layers`() {
        RawHttpServer { _, _ -> RawHttpServer.response("200 OK", manifest) }.use { server ->
            val version = HttpModelRegistry(server.baseUrl).latest("qwen3.5:9b")

            val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray()))
            assertEquals(RegistryVersion(expected, 177L + 921704832L + 5629109120L), version)
            val request = server.received.single()
            assertEquals("/v2/library/qwen3.5/manifests/9b", request.target)
            assertEquals("application/vnd.docker.distribution.manifest.v2+json", request.header("Accept"))
            assertTrue(request.header("User-Agent").orEmpty().startsWith("BasetoolSCExtractor/"))
        }
    }

    @Test
    fun `an unknown tag or an unreachable registry yields no version`() {
        RawHttpServer { _, _ -> RawHttpServer.response("404 Not Found", """{"errors":[]}""") }.use { server ->
            assertNull(HttpModelRegistry(server.baseUrl).latest("qwen3.5:nope"))
        }
        assertNull(HttpModelRegistry("http://localhost:1").latest("qwen3.5:9b"))
    }

    @Test
    fun `manifest paths keep a namespace and default the tag`() {
        assertEquals("library/qwen3-vl/manifests/8b-instruct", HttpModelRegistry.manifestPath("qwen3-vl:8b-instruct"))
        assertEquals("library/qwen3.5/manifests/latest", HttpModelRegistry.manifestPath("qwen3.5"))
        assertEquals("someone/model/manifests/v2", HttpModelRegistry.manifestPath("someone/model:v2"))
    }

    @Test
    fun `a registry URL outside the transport policy is refused`() {
        assertFailsWith<IllegalArgumentException> { HttpModelRegistry("http://registry.example.com") }
    }
}
