package com.basetool.bpextractor.net.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exchange login against a local stand-in for Keycloak: when a stored login is refreshed, when it
 * is replaced by a device login, what that login requests, and that no key is ever held in memory only.
 */
class ExchangeLoginTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** Every form body the stand-in received, by endpoint suffix. */
    private val forms = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    /** The token endpoint's answer. */
    private var tokenAnswer: Pair<Int, String> = 200 to bound("AT", "RT-NEW")

    /** The reasons the login reported before a device login. */
    private val reasons = mutableListOf<LoginReason>()

    private val keys = FakeDpopKeyStore()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            record("device", ex)
            respond(ex, 200, """{"device_code":"DC","user_code":"UC","verification_uri":"https://kc/device","expires_in":60,"interval":1}""")
        }
        server.createContext("/protocol/openid-connect/token") { ex ->
            record("token", ex)
            respond(ex, tokenAnswer.first, tokenAnswer.second)
        }
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            record("revoke", ex)
            respond(ex, 200, "")
        }
        server.start()
        base = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun bound(access: String, refresh: String) =
        """{"access_token":"$access","refresh_token":"$refresh","token_type":"DPoP","expires_in":300,"scope":"exchange.connect"}"""

    private fun record(endpoint: String, ex: HttpExchange) {
        val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
        val form =
            body.split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
        forms += endpoint to form
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    private fun login(store: CredentialStore, keyStore: DpopKeyStore = keys) =
        ExchangeLogin(DeviceGrantClient(issuer = base), store, keyStore)

    private fun obtain(login: ExchangeLogin, scopes: Set<String> = DeviceGrantClient.BASE_SCOPES) =
        login.obtain(scopes) { _, reason -> reasons += reason }

    private fun storedWith(scope: String?): Pair<FakeCredentialStore, String> {
        val key = assertNotNull(keys.create())
        val name = assertNotNull(key.keyName)
        return FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-OLD", name, scope))) to name
    }

    private fun requests(endpoint: String) = forms.filter { it.first == endpoint }.map { it.second }

    @Test
    fun `a stored login that covers the scopes is refreshed silently with its key`() {
        val (store, name) = storedWith(DeviceGrantClient.BASE_SCOPES.joinToString(" "))

        val grant = obtain(login(store))

        assertFalse(grant.fresh)
        assertEquals(name, grant.key.keyName)
        assertTrue(requests("device").isEmpty())
        assertEquals("refresh_token", requests("token").single()["grant_type"])
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `a login from before the exchange is revoked with its key and replaced by a device login`() {
        val (store, name) = storedWith(null)

        val grant = obtain(login(store))

        assertTrue(grant.fresh)
        assertEquals(listOf(LoginReason.SCOPE_UPGRADE), reasons)
        assertEquals("RT-OLD", requests("revoke").single()["token"])
        assertTrue(name in keys.deleted)
        assertNull(store.stored)
        val scope = requests("device").single().getValue("scope").split(' ').toSet()
        assertEquals(DeviceGrantClient.BASE_SCOPES, scope)
        assertFalse("extractor-ingest" in scope)
        assertFalse("openid" in scope)
        assertTrue("offline_access" in scope && "exchange.connect" in scope)
    }

    @Test
    fun `switching the sync on asks for its scopes with one more device login`() {
        val (store, _) = storedWith(DeviceGrantClient.BASE_SCOPES.joinToString(" "))

        obtain(login(store), DeviceGrantClient.BASE_SCOPES + DeviceGrantClient.SYNC_SCOPES)

        assertEquals(listOf(LoginReason.SCOPE_UPGRADE), reasons)
        val scope = requests("device").single().getValue("scope").split(' ').toSet()
        assertTrue(scope.containsAll(DeviceGrantClient.SYNC_SCOPES))
    }

    @Test
    fun `a stored login bound to no key is replaced even when it names the scopes`() {
        val store = FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-OLD", null, DeviceGrantClient.BASE_SCOPES.joinToString(" "))))

        val grant = obtain(login(store))

        assertTrue(grant.fresh)
        assertEquals(listOf(LoginReason.SCOPE_UPGRADE), reasons)
    }

    @Test
    fun `a record with an exported key is retired as the key upgrade`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val encoder = Base64.getEncoder()
        val exported = encoder.encodeToString(pair.private.encoded) + "." + encoder.encodeToString(pair.public.encoded)
        val store = FakeCredentialStore("""{"refreshToken":"RT-LEGACY","dpopKey":"$exported"}""")

        obtain(login(store))

        assertEquals(listOf(LoginReason.KEY_UPGRADE), reasons)
        assertEquals("RT-LEGACY", requests("revoke").single()["token"])
    }

    @Test
    fun `without key storage there is no login at all`() {
        val store = FakeCredentialStore()

        assertFailsWith<NoPersistentKeyException> { obtain(login(store, FakeDpopKeyStore(available = false))) }

        assertTrue(requests("device").isEmpty())
    }

    @Test
    fun `an unbound token from the device login is refused and its key deleted`() {
        tokenAnswer = 200 to """{"access_token":"AT","refresh_token":"RT","token_type":"Bearer","expires_in":300}"""
        val store = FakeCredentialStore()

        assertFailsWith<UnboundTokenException> { obtain(login(store)) }

        assertTrue(keys.keys.isEmpty())
        assertEquals(1, keys.deleted.size)
    }

    @Test
    fun `a dead stored login is dropped and a device login follows`() {
        var calls = 0
        server.removeContext("/protocol/openid-connect/token")
        server.createContext("/protocol/openid-connect/token") { ex ->
            record("token", ex)
            calls++
            if (calls == 1) respond(ex, 400, """{"error":"invalid_grant"}""") else respond(ex, 200, bound("AT", "RT-NEW"))
        }
        val (store, name) = storedWith(DeviceGrantClient.BASE_SCOPES.joinToString(" "))

        val grant = obtain(login(store))

        assertTrue(grant.fresh)
        assertTrue(name in keys.deleted)
        assertEquals(listOf(LoginReason.NONE), reasons)
    }

    @Test
    fun `a proof rejection keeps the stored login`() {
        tokenAnswer = 400 to """{"error":"invalid_dpop_proof"}"""
        val (store, name) = storedWith(DeviceGrantClient.BASE_SCOPES.joinToString(" "))

        assertFailsWith<DeviceGrantException> { obtain(login(store)) }

        assertNotNull(store.stored)
        assertFalse(name in keys.deleted)
    }

    @Test
    fun `remember stores the token, the key's name and the requested scopes, never the key`() {
        val store = FakeCredentialStore()
        val login = login(store)
        val grant = obtain(login, DeviceGrantClient.BASE_SCOPES + DeviceGrantClient.SYNC_SCOPES)

        login.remember(grant)

        val stored = assertNotNull(StoredCredential.decode(assertNotNull(store.stored)))
        assertEquals("RT-NEW", stored.refreshToken)
        assertEquals(grant.key.keyName, stored.dpopKeyName)
        assertTrue(stored.covers(DeviceGrantClient.BASE_SCOPES + DeviceGrantClient.SYNC_SCOPES))
        assertFalse(store.stored!!.contains("BEGIN") || store.stored!!.contains("dpopKey\""))
    }

    @Test
    fun `forget drops the stored login and its key`() {
        val (store, name) = storedWith(DeviceGrantClient.BASE_SCOPES.joinToString(" "))

        login(store).forget()

        assertNull(store.stored)
        assertTrue(name in keys.deleted)
    }
}
