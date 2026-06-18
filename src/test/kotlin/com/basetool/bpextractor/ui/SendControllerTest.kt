package com.basetool.bpextractor.ui

import com.basetool.bpextractor.config.AppConfig
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.FakeCredentialStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises the [SendController] "remember me" path (epic krt-profit/basetool#639, #648) against a
 * single local stand-in for Keycloak + the ingest gateway (JDK [HttpServer]) — no real credentials,
 * no real network. Proves the silent refresh skips the browser and re-persists the rotated token,
 * and that a dead stored token is dropped.
 */
class SendControllerTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val deviceCalls = AtomicInteger(0)
    private var browseCount = 0

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            deviceCalls.incrementAndGet()
            respond(ex, 400, """{"error":"unauthorized_client"}""") // fallback path: fail fast, no poll
        }
        server.createContext("/v1/refinery-extract") { ex ->
            respond(ex, 200, """{"handoffId":"H1","kind":"REFINERY","frontendUrl":"https://app/x?handoff=H1"}""")
        }
        server.createContext("/v1/blueprint-preview") { ex ->
            respond(ex, 200, """{"handoffId":"B1","kind":"BLUEPRINT","frontendUrl":"https://app/bp?handoff=B1"}""")
        }
        server.start()
        base = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** A consented config store pointing the ingest base URL at the local stand-in. */
    private fun consentedConfig(): AppConfigStore {
        val dir = Files.createTempDirectory("sc-send-test").toFile()
        val store = AppConfigStore(dir)
        store.save(AppConfig(ingestBaseUrl = base, consentGiven = true))
        return store
    }

    private fun controller(store: FakeCredentialStore) =
        SendController(
            configStore = consentedConfig(),
            deviceGrant = DeviceGrantClient(issuer = base),
            credentialStore = store,
            browse = { browseCount++ },
        )

    @Test
    fun `a stored token sends silently and re-persists the rotated token`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"Bearer","expires_in":300}""")
        }
        val store = FakeCredentialStore("RT-STORED")
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertTrue(controller.state is SendState.Done, "expected Done, was ${controller.state}")
        assertEquals("https://app/x?handoff=H1", (controller.state as SendState.Done).frontendUrl)
        assertEquals("RT-ROTATED", store.stored, "the rotated refresh token must be persisted")
        assertEquals(1, store.saveCount)
        assertEquals(0, deviceCalls.get(), "the silent path must not start a device grant")
        assertEquals(0, browseCount, "the silent path must not open the browser")
    }

    @Test
    fun `a dead stored token is cleared before falling back to a fresh login`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"invalid_grant"}""") // the stored refresh token is dead
        }
        val store = FakeCredentialStore("RT-DEAD")
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertNull(store.stored, "the dead token must be dropped")
        assertEquals(1, deviceCalls.get(), "it must fall back to a device grant")
        assertTrue(controller.state is SendState.Error, "the stubbed device grant fails, so we end in Error")
    }

    @Test
    fun `a blueprint send posts to the blueprint endpoint`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"Bearer","expires_in":300}""")
        }
        val controller = controller(FakeCredentialStore("RT-STORED"))

        runBlocking { controller.request(this, SendKind.BLUEPRINT, """{"schemaVersion":1}""", "en") }

        // The BLUEPRINT kind routes to /v1/blueprint-preview, whose stand-in returns the B1 handoff.
        assertTrue(controller.state is SendState.Done, "expected Done, was ${controller.state}")
        assertEquals("https://app/bp?handoff=B1", (controller.state as SendState.Done).frontendUrl)
    }
}
