package com.basetool.bpextractor.ui

import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DpopProofs
import com.basetool.bpextractor.net.auth.FakeCredentialStore
import com.basetool.bpextractor.net.auth.FakeDpopKeyStore
import com.basetool.bpextractor.net.auth.StoredCredential
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises the "remember me" disconnect against a local stand-in for Keycloak's revocation endpoint
 * ([HttpServer]) and an in-memory credential store: disconnect revokes server-side and deletes the
 * local token.
 */
class AccountControllerTest {

    private lateinit var server: HttpServer
    private lateinit var issuer: String
    private val revokedBody = AtomicReference<String>("")
    private val revokeProof = AtomicReference<String?>(null)
    private val keys = FakeDpopKeyStore()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            revokedBody.set(ex.requestBody.readBytes().decodeToString())
            revokeProof.set(ex.requestHeaders.getFirst("DPoP"))
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
        assertFalse(AccountController(FakeCredentialStore(), DeviceGrantClient(issuer = issuer), keys).connected)
        assertTrue(AccountController(FakeCredentialStore("RT"), DeviceGrantClient(issuer = issuer), keys).connected)
    }

    @Test
    fun `disconnect revokes the token, deletes it locally and flips connected`() {
        val store = FakeCredentialStore("RT-STORED")
        val account = AccountController(store, DeviceGrantClient(issuer = issuer), keys)
        assertTrue(account.connected)

        account.requestDisconnect()
        assertTrue(account.confirming)

        runBlocking { account.confirmDisconnect(this) }

        assertTrue(revokedBody.get().contains("token=RT-STORED"), "the stored token must be revoked")
        assertNull(store.stored, "the local token must be deleted")
        assertFalse(account.connected)
        assertFalse(account.confirming)
    }

    @Test
    fun `disconnect revokes with the stored key's proof and deletes that key too`() {
        val key = assertNotNull(keys.create())
        val store = FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-BOUND", key.keyName)))
        val account = AccountController(store, DeviceGrantClient(issuer = issuer), keys)

        runBlocking { account.confirmDisconnect(this) }

        assertTrue(revokedBody.get().contains("token=RT-BOUND"))
        assertEquals(key.thumbprint, DpopProofs.thumbprint(assertNotNull(revokeProof.get())))
        assertNull(store.stored)
        assertTrue(keys.keys.isEmpty(), "the non-exportable key must be deleted from the key storage")
    }

    @Test
    fun `disconnect of a legacy exported-key record revokes it and deletes it`() {
        val pair =
            java.security.KeyPairGenerator.getInstance("EC")
                .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val export =
            java.util.Base64.getEncoder().encodeToString(pair.private.encoded) + "." +
                java.util.Base64.getEncoder().encodeToString(pair.public.encoded)
        val store = FakeCredentialStore("""{"refreshToken":"RT-OLD","dpopKey":"$export"}""")
        val account = AccountController(store, DeviceGrantClient(issuer = issuer), keys)
        assertTrue(account.connected)

        runBlocking { account.confirmDisconnect(this) }

        assertTrue(revokedBody.get().contains("token=RT-OLD"))
        assertNotNull(revokeProof.get(), "revoked with a proof from the legacy key")
        assertNull(store.stored)
        assertFalse(account.connected)
    }
}
