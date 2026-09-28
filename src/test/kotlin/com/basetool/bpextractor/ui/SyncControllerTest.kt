package com.basetool.bpextractor.ui

import com.basetool.bpextractor.config.AppConfig
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.model.BlueprintItem
import com.basetool.bpextractor.model.ItemRef
import com.basetool.bpextractor.model.Provenance
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.FakeCredentialStore
import com.basetool.bpextractor.net.auth.FakeDpopKeyStore
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.net.auth.StoredCredential
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The sync flow against one local stand-in for Keycloak and the gateway: the one-time opt-in, the
 * re-login a login without the sync scopes needs, and what each answer of the account check does.
 */
class SyncControllerTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val paths = CopyOnWriteArrayList<String>()
    private val deviceScopes = CopyOnWriteArrayList<String>()
    private var accountCheck = """{"result":"match"}"""
    private var serviceDocument =
        """{"capabilities":["exchange.connect","exchange.blueprints.read","exchange.blueprints.write"],"minClientVersion":"1.0.0"}"""
    private val serviceDocuments = AtomicInteger(0)
    private val keys = FakeDpopKeyStore()
    private val syncScope = (DeviceGrantClient.BASE_SCOPES + DeviceGrantClient.SYNC_SCOPES).sorted().joinToString(" ")

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            val form = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            deviceScopes += URLDecoder.decode(form.substringAfter("scope=").substringBefore('&'), Charsets.UTF_8)
            respond(ex, """{"device_code":"DC","user_code":"UC","verification_uri":"https://kc/d","expires_in":60,"interval":1}""")
        }
        server.createContext("/protocol/openid-connect/token") { ex ->
            ex.requestBody.readAllBytes()
            respond(ex, """{"access_token":"AT","refresh_token":"RT","token_type":"DPoP","expires_in":300}""")
        }
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            ex.requestBody.readAllBytes()
            respond(ex, "{}")
        }
        server.createContext("/exchange/v1") { ex ->
            serviceDocuments.incrementAndGet()
            respond(ex, serviceDocument)
        }
        server.createContext("/exchange/v1/") { ex ->
            ex.requestBody.readAllBytes()
            paths += ex.requestURI.path
            when (ex.requestURI.path) {
                "/exchange/v1/me/account-check" -> respond(ex, accountCheck)
                "/exchange/v1/me/installation" -> respond(ex, """{"label":"Spiele-PC"}""")
                "/exchange/v1/me/blueprints" -> respond(ex, """{"items":[],"removed":[],"nextCursor":"f1.1.1","hasMore":false}""")
                "/exchange/v1/catalog/resolve" ->
                    respond(ex, """{"results":[{"index":0,"status":"resolved","ref":{"bt":"bt-1","name":"New Pistol"}}]}""")
                "/exchange/v1/me/blueprints/changes" ->
                    respond(ex, """{"dryRun":false,"applied":1,"unchanged":0,"notApplied":0,"results":[]}""")
                else -> respond(ex, "{}")
            }
        }
        server.start()
        base = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun config(enabled: Boolean): AppConfigStore {
        val store = AppConfigStore(Files.createTempDirectory("sc-sync-test").toFile())
        store.save(AppConfig(ingestBaseUrl = base, consentGiven = true, installationLabel = "Spiele-PC", blueprintSyncEnabled = enabled))
        return store
    }

    private fun storedSyncLogin(): FakeCredentialStore {
        val name = assertNotNull(assertNotNull(keys.create()).keyName)
        return FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-OLD", name, syncScope)))
    }

    private fun controller(config: AppConfigStore, store: FakeCredentialStore) =
        SyncController(
            configStore = config,
            deviceGrant = DeviceGrantClient(issuer = base),
            credentialStore = store,
            keyStore = keys,
            browse = {},
        )

    private val items =
        listOf(BlueprintItem(ItemRef(name = "New Pistol"), "2026-01-01T10:00:00.000Z", Provenance(Provenance.LOG)))

    private fun handle() = "Pilot_" + UUID.randomUUID().toString().take(8)

    @Test
    fun `the first sync asks for the opt-in and remembers it`() {
        val config = config(enabled = false)
        val controller = controller(config, storedSyncLogin())
        runBlocking {
            controller.request(this, items, handle(), "de", "Windows-PC")
            assertEquals(SyncState.Consent(null), controller.state)
            controller.confirmConsent(this)
        }

        assertTrue(config.load().blueprintSyncEnabled)
        assertEquals(1, (controller.state as SyncState.Done).report?.added)
    }

    @Test
    fun `a login without the sync scopes is replaced by one that has them`() {
        val name = assertNotNull(assertNotNull(keys.create()).keyName)
        val base = DeviceGrantClient.BASE_SCOPES.joinToString(" ")
        val store = FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-OLD", name, base)))
        val reasons = mutableListOf<LoginReason>()
        lateinit var controller: SyncController
        controller =
            SyncController(
                configStore = config(enabled = true),
                deviceGrant = DeviceGrantClient(issuer = this.base),
                credentialStore = store,
                keyStore = keys,
                browse = { (controller.state as? SyncState.Authenticating)?.let { reasons += it.reason } },
            )

        runBlocking { controller.request(this, items, handle(), "de", "Windows-PC") }

        assertEquals(listOf(LoginReason.SCOPE_UPGRADE), reasons)
        assertTrue(deviceScopes.single().split(' ').containsAll(DeviceGrantClient.SYNC_SCOPES))
        assertTrue(StoredCredential.decode(assertNotNull(store.stored))!!.covers(DeviceGrantClient.SYNC_SCOPES))
        assertTrue("/exchange/v1/me/installation" in paths, "a new installation is labelled")
    }

    @Test
    fun `a mismatching account stops before anything is read or written`() {
        accountCheck = """{"result":"mismatch"}"""
        val account = handle()
        val controller = controller(config(enabled = true), storedSyncLogin())

        runBlocking { controller.request(this, items, account, "de", "Windows-PC") }

        assertEquals(SyncState.AccountMismatch(account), controller.state)
        assertEquals(listOf("/exchange/v1/me/account-check"), paths)

        runBlocking { controller.continueWithAccount(this) }

        assertEquals(1, (controller.state as SyncState.Done).report?.added)
        assertTrue("/exchange/v1/me/blueprints/changes" in paths)
    }

    @Test
    fun `an account the profile does not name is synced only after the member confirms it`() {
        accountCheck = """{"result":"unknown"}"""
        val account = handle()
        val controller = controller(config(enabled = true), storedSyncLogin())

        runBlocking { controller.request(this, items, account, "de", "Windows-PC") }

        assertEquals(SyncState.AccountUnconfirmed(account), controller.state)
        assertEquals(listOf("/exchange/v1/me/account-check"), paths)

        runBlocking { controller.continueWithAccount(this) }

        assertEquals(1, (controller.state as SyncState.Done).report?.added)
        assertEquals(1, paths.count { it == "/exchange/v1/me/account-check" }, "the answer holds for the session")

        runBlocking { controller.request(this, items, account, "de", "Windows-PC") }

        assertTrue(controller.state is SyncState.Done, "a confirmed account is not asked about again")
    }

    @Test
    fun `a service document without the write capability stops before the sync`() {
        serviceDocument = """{"capabilities":["exchange.connect","exchange.blueprints.read"]}"""
        val controller = controller(config(enabled = true), storedSyncLogin())

        runBlocking { controller.request(this, items, null, "de", "Windows-PC") }

        assertEquals("SCOPE_MISSING", (controller.state as SyncState.Error).error.code)
        assertTrue(paths.none { it.startsWith("/exchange/v1/me/blueprints") })
    }

    @Test
    fun `a version below the registry's minimum stops before the sync`() {
        serviceDocument =
            """{"capabilities":["exchange.connect","exchange.blueprints.read","exchange.blueprints.write"],"minClientVersion":"99.0.0"}"""
        val controller = controller(config(enabled = true), storedSyncLogin())

        runBlocking { controller.request(this, items, null, "de", "Windows-PC") }

        assertEquals("CLIENT_VERSION_UNSUPPORTED", (controller.state as SyncState.Error).error.code)
        assertTrue(paths.none { it.startsWith("/exchange/v1/me/blueprints") })
        assertEquals(1, serviceDocuments.get())
    }

    @Test
    fun `a checked account is not checked again in the same session`() {
        val account = handle()
        runBlocking { controller(config(enabled = true), storedSyncLogin()).request(this, items, account, "de", "Windows-PC") }
        runBlocking { controller(config(enabled = true), storedSyncLogin()).request(this, items, account, "de", "Windows-PC") }

        assertEquals(1, paths.count { it == "/exchange/v1/me/account-check" })
    }

    @Test
    fun `files naming no account are synced without an account check`() {
        val controller = controller(config(enabled = true), storedSyncLogin())

        runBlocking { controller.request(this, items, null, "de", "Windows-PC") }

        assertTrue("/exchange/v1/me/account-check" !in paths)
        assertTrue(controller.state is SyncState.Done)
    }

    @Test
    fun `an account without blueprints has nothing to sync`() {
        val controller = controller(config(enabled = true), storedSyncLogin())

        runBlocking { controller.request(this, emptyList(), handle(), "de", "Windows-PC") }

        assertEquals(SyncState.Done(null), controller.state)
        assertTrue(paths.none { it.startsWith("/exchange/v1/me/blueprints") })
    }
}
