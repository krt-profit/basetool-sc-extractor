package com.basetool.bpextractor.net.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the device-grant flow against a local stand-in for Keycloak (JDK [HttpServer]) — no
 * real credentials, no real network (CLAUDE.md test rule). Covers the device-code request, the
 * authorization_pending → success poll, and the denial path.
 */
class DeviceGrantClientTest {

    private lateinit var server: HttpServer
    private lateinit var issuer: String
    private val tokenCalls = AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            respond(
                ex,
                200,
                """{"device_code":"DEV-1","user_code":"WXYZ-1234",
                   "verification_uri":"https://kc/device",
                   "verification_uri_complete":"https://kc/device?user_code=WXYZ-1234",
                   "expires_in":600,"interval":1}""",
            )
        }
        server.start()
        issuer = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun client() = DeviceGrantClient(issuer = issuer)

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.trimIndent().toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun requestsDeviceCode() {
        val device = client().requestDeviceCode()
        assertEquals("DEV-1", device.deviceCode)
        assertEquals("WXYZ-1234", device.userCode)
        assertEquals("https://kc/device?user_code=WXYZ-1234", device.browserUrl())
    }

    @Test
    fun pollsThroughPendingThenSucceeds() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            if (tokenCalls.getAndIncrement() == 0) {
                respond(ex, 400, """{"error":"authorization_pending"}""")
            } else {
                respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"Bearer","expires_in":300}""")
            }
        }
        val device = client().requestDeviceCode()
        val token = client().pollForToken(device, sleep = {}, nowMillis = { 1_000L })
        assertEquals("AT", token.accessToken)
        assertEquals("RT", token.refreshToken)
        assertTrue(tokenCalls.get() >= 2)
    }

    @Test
    fun deniedPollFails() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"access_denied"}""")
        }
        val device = client().requestDeviceCode()
        assertFailsWith<DeviceGrantException> {
            client().pollForToken(device, sleep = {}, nowMillis = { 1_000L })
        }
    }

    @Test
    fun rejectsNonHttpsIssuer() {
        assertFailsWith<IllegalArgumentException> { DeviceGrantClient(issuer = "http://evil.example") }
    }
}
