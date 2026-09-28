package com.basetool.bpextractor.net

import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopProofs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exchange client against a local stand-in for the gateway ([RawHttpServer]): the headers every
 * call carries, the nonce handshake, the idempotency key, and how a problem reaches the user.
 */
class ExchangeClientTest {

    private val key = DpopKey.generate()
    private val credentials = ExchangeCredentials("AT-1", key)

    private val draftResult =
        """{"frontendUrl":"https://profit-base.online/personal-inventory/blueprints?handoff=h1","handoffId":"h1","kind":"BLUEPRINT"}"""

    @Test
    fun `a blueprint draft goes to its route with DPoP, the user agent, the language and an idempotency key`() {
        RawHttpServer { _, _ -> RawHttpServer.response("200 OK", draftResult) }.use { server ->
            val result = ExchangeClient(server.baseUrl).draftBlueprints(credentials, """{"format":"x"}""", "de")

            assertEquals("h1", result.handoffId)
            assertEquals("BLUEPRINT", result.kind)
            val request = server.received.single()
            assertEquals("/exchange/v1/me/drafts/blueprints", request.target)
            assertEquals("DPoP AT-1", request.header("Authorization"))
            assertEquals(ExchangeClient.USER_AGENT, request.header("User-Agent"))
            assertTrue(request.header("User-Agent")!!.matches(Regex("""BasetoolSCExtractor/\S+ \(\+https://github\.com/krt-profit/basetool-sc-extractor\)""")))
            assertEquals("de", request.header("Accept-Language"))
            assertNotNull(request.header("Idempotency-Key")).also { assertTrue(it.length in 8..128) }
            assertEquals("""{"format":"x"}""", request.body)
            val proof = assertNotNull(request.header("DPoP"))
            assertEquals("POST", DpopProofs.claim(proof, "htm"))
            assertEquals("${server.baseUrl}/exchange/v1/me/drafts/blueprints", DpopProofs.claim(proof, "htu"))
            assertEquals(DpopProofs.expectedAth("AT-1"), DpopProofs.claim(proof, "ath"))
            assertNull(DpopProofs.claim(proof, "nonce"))
        }
    }

    @Test
    fun `a refinery draft goes to the refinery route`() {
        RawHttpServer { _, _ -> RawHttpServer.response("200 OK", draftResult.replace("BLUEPRINT", "REFINERY")) }.use { server ->
            ExchangeClient(server.baseUrl).draftRefinery(credentials, """{"schemaVersion":1,"orders":[]}""", "en")

            assertEquals("/exchange/v1/me/drafts/refinery-orders", server.received.single().target)
        }
    }

    @Test
    fun `a nonce challenge is answered once with the nonce and the same idempotency key`() {
        RawHttpServer { attempt, _ ->
            if (attempt == 1) {
                RawHttpServer.response(
                    "401 Unauthorized",
                    """{"status":401,"code":"DPOP_INVALID","detail":"nonce"}""",
                    headers = mapOf("WWW-Authenticate" to """DPoP algs="ES256", error="use_dpop_nonce"""", "DPoP-Nonce" to "n-1"),
                )
            } else {
                RawHttpServer.response("200 OK", draftResult, headers = mapOf("DPoP-Nonce" to "n-2"))
            }
        }.use { server ->
            val client = ExchangeClient(server.baseUrl)
            client.draftBlueprints(credentials, "{}", "de")

            val (first, second) = server.received
            assertEquals(first.header("Idempotency-Key"), second.header("Idempotency-Key"))
            val retried = assertNotNull(second.header("DPoP"))
            assertEquals("n-1", DpopProofs.claim(retried, "nonce"))
            assertNotEquals(DpopProofs.claim(first.header("DPoP")!!, "jti"), DpopProofs.claim(retried, "jti"))

            client.draftBlueprints(credentials, "{}", "de")
            assertEquals("n-2", DpopProofs.claim(server.received[2].header("DPoP")!!, "nonce"))
            assertNotEquals(first.header("Idempotency-Key"), server.received[2].header("Idempotency-Key"))
        }
    }

    @Test
    fun `a second refusal after the nonce retry is not retried again`() {
        RawHttpServer { attempt, _ ->
            RawHttpServer.response(
                "401 Unauthorized",
                """{"status":401,"code":"DPOP_INVALID","detail":"proof refused"}""",
                headers = mapOf("WWW-Authenticate" to """DPoP error="use_dpop_nonce"""", "DPoP-Nonce" to "n-$attempt"),
            )
        }.use { server ->
            val e = assertFailsWith<ExchangeException> {
                ExchangeClient(server.baseUrl).draftBlueprints(credentials, "{}", "de")
            }
            assertEquals(2, server.received.size)
            assertEquals(Codes.DPOP_INVALID, e.code)
            assertEquals(401, e.status)
        }
    }

    @Test
    fun `a refusal without a nonce challenge is not retried`() {
        RawHttpServer { _, _ ->
            RawHttpServer.response("403 Forbidden", """{"status":403,"code":"CLIENT_VERSION_UNSUPPORTED","detail":"too old"}""")
        }.use { server ->
            val e = assertFailsWith<ExchangeException> {
                ExchangeClient(server.baseUrl).draftRefinery(credentials, "{}", "de")
            }
            assertEquals(1, server.received.size)
            assertEquals(Codes.CLIENT_VERSION_UNSUPPORTED, e.code)
            assertEquals("too old", e.message)
        }
    }

    @Test
    fun `a schema problem names the fields it points at`() {
        RawHttpServer { _, _ ->
            RawHttpServer.response(
                "400 Bad Request",
                """{"status":400,"code":"SCHEMA_INVALID","detail":"The body does not match the contract.",""" +
                    """"errors":[{"pointer":"/items/3/ref","message":"required"}]}""",
            )
        }.use { server ->
            val e = assertFailsWith<ExchangeException> {
                ExchangeClient(server.baseUrl).draftBlueprints(credentials, "{}", "de")
            }
            assertEquals("The body does not match the contract. (/items/3/ref: required)", e.message)
        }
    }

    @Test
    fun `a problem without detail still says its code and status`() {
        RawHttpServer { _, _ -> RawHttpServer.response("413 Payload Too Large", """{"status":413,"code":"PAYLOAD_TOO_LARGE"}""") }
            .use { server ->
                val e = assertFailsWith<ExchangeException> {
                    ExchangeClient(server.baseUrl).draftBlueprints(credentials, "{}", "de")
                }
                assertTrue("PAYLOAD_TOO_LARGE" in e.message!! && "413" in e.message!!)
            }
    }

    @Test
    fun `the installation label is posted without an idempotency key`() {
        RawHttpServer { _, _ ->
            RawHttpServer.response("200 OK", """{"label":"Spiele-PC","installationId":"inst-1"}""")
        }.use { server ->
            val installation = ExchangeClient(server.baseUrl).labelInstallation(credentials, "Spiele-PC", "de")

            assertEquals("inst-1", installation.installationId)
            val request = server.received.single()
            assertEquals("/exchange/v1/me/installation", request.target)
            assertEquals("""{"label":"Spiele-PC"}""", request.body)
            assertNull(request.header("Idempotency-Key"))
        }
    }

    @Test
    fun `an unreachable gateway is a transport failure, not a problem`() {
        val url = RawHttpServer { _, _ -> "" }.use { it.baseUrl }
        val e = assertFailsWith<ExchangeException> {
            ExchangeClient(url).draftBlueprints(credentials, "{}", "de")
        }
        assertEquals(0, e.status)
        assertTrue(e.message!!.startsWith("could not reach the basetool"))
    }

    @Test
    fun `a plain-http base URL off loopback is refused`() {
        assertFailsWith<IllegalArgumentException> { ExchangeClient("http://ingest.example.org") }
    }

    @Test
    fun `the installation label rule matches the schema's`() {
        listOf("Windows-PC", "Bürorechner", "Linux-Laptop_2.0", "a", "x".repeat(40)).forEach {
            assertTrue(InstallationLabel.isValid(it), it)
        }
        listOf("", " PC", "x".repeat(41), "PC!", "PC‮", "Mein\tPC").forEach {
            assertTrue(!InstallationLabel.isValid(it), it)
        }
    }

    @Test
    fun `a refusal carries the request's reference and the server's wait`() {
        RawHttpServer { _, _ ->
            RawHttpServer.response(
                "503 Service Unavailable",
                """{"status":503,"code":"EXCHANGE_DISABLED","detail":"The exchange is switched off."}""",
                headers = mapOf("X-Correlation-Id" to "req-1.a_b", "Retry-After" to "30"),
            )
        }.use { server ->
            val e = assertFailsWith<ExchangeException> { ExchangeClient(server.baseUrl).draftBlueprints(credentials, "{}", "de") }

            assertEquals("EXCHANGE_DISABLED", e.code)
            assertEquals("req-1.a_b", e.correlationId)
            assertEquals(30L, e.retryAfterSeconds)
        }
    }

    @Test
    fun `a reference that is not a plain id is dropped`() {
        RawHttpServer { _, _ ->
            RawHttpServer.response(
                "429 Too Many Requests",
                """{"status":429,"code":"RATE_LIMITED","detail":"Slow down.","retryAfterSeconds":12}""",
                headers = mapOf("X-Correlation-Id" to "x".repeat(129)),
            )
        }.use { server ->
            val e = assertFailsWith<ExchangeException> { ExchangeClient(server.baseUrl).draftBlueprints(credentials, "{}", "de") }

            assertEquals("", e.correlationId)
            assertEquals(12L, e.retryAfterSeconds, "the problem's own field when the header is absent")
        }
    }

    @Test
    fun `the service document is read from the API root`() {
        RawHttpServer { _, _ ->
            RawHttpServer.response(
                "200 OK",
                """{"apiVersion":"1","capabilities":["exchange.connect"],"minClientVersion":"2.10.0","installationId":"i-1"}""",
            )
        }.use { server ->
            val document = ExchangeClient(server.baseUrl).serviceDocument(credentials, "de")

            assertEquals("/exchange/v1", server.received.single().target)
            assertEquals(listOf("exchange.connect"), document.capabilities)
            assertEquals("2.10.0", document.minClientVersion)
            assertEquals("GET", DpopProofs.claim(server.received.single().header("DPoP")!!, "htm"))
        }
    }
}
