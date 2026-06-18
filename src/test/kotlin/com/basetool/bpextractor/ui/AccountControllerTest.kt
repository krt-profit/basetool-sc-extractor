package com.basetool.bpextractor.ui

import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.FakeCredentialStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises the "remember me" disconnect (epic krt-profit/basetool#639, #648) against a local stand-in
 * for Keycloak's revocation endpoint (JDK [HttpServer]) and an in-memory credential store — no real
 * credentials, no real network. Confirms the connected indicator and that disconnect both revokes
 * server-side and deletes the local token.
 */
class AccountControllerTest {

    private lateinit var server: HttpServer
    private lateinit var issuer: String
    private val revokedBody = AtomicReference<String>("")

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            revokedBody.set(ex.requestBody.readBytes().decodeToString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        issuer = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `connected reflects a stored token`() {
        assertFalse(AccountController(FakeCredentialStore(), DeviceGrantClient(issuer = issuer)).connected)
        assertTrue(AccountController(FakeCredentialStore("RT"), DeviceGrantClient(issuer = issuer)).connected)
    }

    @Test
    fun `disconnect revokes the token, deletes it locally and flips connected`() {
        val store = FakeCredentialStore("RT-STORED")
        val account = AccountController(store, DeviceGrantClient(issuer = issuer))
        assertTrue(account.connected)

        account.requestDisconnect()
        assertTrue(account.confirming)

        runBlocking { account.confirmDisconnect(this) }

        assertTrue(revokedBody.get().contains("token=RT-STORED"), "the stored token must be revoked")
        assertNull(store.stored, "the local token must be deleted")
        assertFalse(account.connected)
        assertFalse(account.confirming)
    }
}
