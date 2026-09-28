package com.basetool.bpextractor.ui

import com.basetool.bpextractor.config.AppConfig
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.Backoff
import com.basetool.bpextractor.net.Codes
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DpopProofs
import com.basetool.bpextractor.net.auth.FakeCredentialStore
import com.basetool.bpextractor.net.auth.FakeDpopKeyStore
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.net.auth.NoPersistentKeyException
import com.basetool.bpextractor.net.auth.StoredCredential
import com.basetool.bpextractor.ui.i18n.StringsDe
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The send flow against one local stand-in for Keycloak and the exchange gateway ([HttpServer]):
 * consent with the installation label, the fresh login that labels the installation, the silent send
 * that does not, the two draft routes, and what a disconnect in the Basetool does to the stored login.
 */
class SendControllerTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** The exchange requests the gateway stand-in received: path, `Authorization`, `DPoP`, body. */
    private data class Seen(val path: String, val auth: String?, val proof: String?, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()

    /** Per-test override of the draft answer. */
    private var draftAnswer: ((HttpExchange) -> Unit)? = null

    private val keys = FakeDpopKeyStore()
    private var browsed = 0
    private var serviceDocuments = 0

    private val baseScope = DeviceGrantClient.BASE_SCOPES.joinToString(" ")

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            ex.requestBody.readAllBytes()
            respond(ex, 200, """{"device_code":"DC","user_code":"WXYZ","verification_uri":"https://kc/device","expires_in":60,"interval":1}""")
        }
        server.createContext("/protocol/openid-connect/token") { ex ->
            ex.requestBody.readAllBytes()
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-NEW","token_type":"DPoP","expires_in":300}""")
        }
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            ex.requestBody.readAllBytes()
            respond(ex, 200, "{}")
        }
        server.createContext("/exchange/v1") { ex ->
            serviceDocuments++
            respond(
                ex,
                200,
                """{"capabilities":["exchange.connect","exchange.drafts.blueprints","exchange.drafts.refinery"],"minClientVersion":"1.0.0"}""",
            )
        }
        server.createContext("/exchange/v1/me/installation") { ex ->
            record(ex)
            respond(ex, 200, """{"label":"Spiele-PC","installationId":"inst-1"}""")
        }
        server.createContext("/exchange/v1/me/drafts/blueprints") { ex ->
            record(ex)
            draftAnswer?.invoke(ex)
                ?: respond(ex, 200, """{"frontendUrl":"https://app/bp?handoff=B1","handoffId":"B1","kind":"BLUEPRINT"}""")
        }
        server.createContext("/exchange/v1/me/drafts/refinery-orders") { ex ->
            record(ex)
            respond(ex, 200, """{"frontendUrl":"https://app/rf?handoff=R1","handoffId":"R1","kind":"REFINERY"}""")
        }
        server.start()
        base = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun record(ex: HttpExchange) {
        seen +=
            Seen(
                ex.requestURI.path,
                ex.requestHeaders.getFirst("Authorization"),
                ex.requestHeaders.getFirst("DPoP"),
                ex.requestBody.readAllBytes().toString(Charsets.UTF_8),
            )
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun config(consent: Boolean, label: String?): AppConfigStore {
        val store = AppConfigStore(Files.createTempDirectory("sc-send-test").toFile())
        store.save(AppConfig(ingestBaseUrl = base, consentGiven = consent, installationLabel = label))
        return store
    }

    private fun controller(
        store: FakeCredentialStore,
        config: AppConfigStore = config(true, "Spiele-PC"),
        keyStore: FakeDpopKeyStore = keys,
    ) = SendController(
        configStore = config,
        deviceGrant = DeviceGrantClient(issuer = base),
        credentialStore = store,
        keyStore = keyStore,
        backoff = Backoff(),
        browse = { browsed++ },
    )

    private fun stored(): Pair<FakeCredentialStore, String> {
        val name = assertNotNull(assertNotNull(keys.create()).keyName)
        return FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-OLD", name, baseScope))) to name
    }

    @Test
    fun `the first send asks for consent and a label before anything leaves the machine`() {
        val config = config(false, null)
        val controller = controller(FakeCredentialStore(), config)

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        assertEquals(SendState.Consent("Windows-PC"), controller.state)
        assertTrue(seen.isEmpty())
        assertEquals(0, browsed)
    }

    @Test
    fun `an invalid label is refused on the consent step and nothing is saved`() {
        val config = config(false, null)
        val controller = controller(FakeCredentialStore(), config)
        runBlocking {
            controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC")
            controller.editLabel(" <script>")
            controller.confirmConsent(this)
        }

        assertEquals(SendState.Consent(" <script>", labelInvalid = true), controller.state)
        assertTrue(!config.load().consentGiven)
        assertNull(config.load().installationLabel)
    }

    @Test
    fun `a first send logs in, labels the new installation, then stages the draft`() {
        val config = config(false, null)
        val credentials = FakeCredentialStore()
        val controller = controller(credentials, config)
        runBlocking {
            controller.request(this, SendKind.BLUEPRINT, """{"format":"basetool.blueprints"}""", "de", "Windows-PC")
            controller.editLabel("Spiele-PC")
            controller.confirmConsent(this)
        }

        assertEquals(SendState.Done("https://app/bp?handoff=B1"), controller.state)
        assertEquals("Spiele-PC", config.load().installationLabel)
        assertEquals(listOf("/exchange/v1/me/installation", "/exchange/v1/me/drafts/blueprints"), seen.map { it.path })
        assertEquals("""{"label":"Spiele-PC"}""", seen[0].body)
        assertEquals("""{"format":"basetool.blueprints"}""", seen[1].body)
        assertEquals("DPoP AT", seen[1].auth)
        assertEquals(DpopProofs.expectedAth("AT"), DpopProofs.claim(assertNotNull(seen[1].proof), "ath"))
        val remembered = assertNotNull(StoredCredential.decode(assertNotNull(credentials.stored)))
        assertEquals("RT-NEW", remembered.refreshToken)
        assertTrue(remembered.covers(DeviceGrantClient.BASE_SCOPES))
        assertEquals(1, browsed)
    }

    @Test
    fun `a stored login sends silently and does not relabel`() {
        val (store, name) = stored()
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"schemaVersion":1}""", "de", "Windows-PC") }

        assertEquals(SendState.Done("https://app/rf?handoff=R1"), controller.state)
        assertEquals(listOf("/exchange/v1/me/drafts/refinery-orders"), seen.map { it.path })
        assertEquals(name, StoredCredential.decode(assertNotNull(store.stored))?.dpopKeyName)
        assertEquals(0, browsed)
    }

    @Test
    fun `a login from before the exchange is replaced once, and the overlay says why`() {
        val name = assertNotNull(assertNotNull(keys.create()).keyName)
        val store = FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-OLD", name)))
        val reasons = mutableListOf<LoginReason>()
        lateinit var controller: SendController
        controller =
            SendController(
                configStore = config(true, "Spiele-PC"),
                deviceGrant = DeviceGrantClient(issuer = base),
                credentialStore = store,
                keyStore = keys,
                backoff = Backoff(),
                browse = { (controller.state as? SendState.Authenticating)?.let { reasons += it.reason } },
            )

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        assertEquals(listOf(LoginReason.SCOPE_UPGRADE), reasons)
        assertTrue(controller.state is SendState.Done)
        assertTrue(name in keys.deleted)
    }

    @Test
    fun `a disconnected installation drops the stored login and its key`() {
        draftAnswer = { ex ->
            respond(ex, 401, """{"status":401,"code":"INSTALLATION_REVOKED","detail":"Diese Installation wurde getrennt."}""")
        }
        val (store, name) = stored()
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        val error = controller.state as SendState.Error
        assertEquals(Codes.INSTALLATION_REVOKED, error.code)
        assertNull(store.stored)
        assertTrue(name in keys.deleted)
    }

    @Test
    fun `without key storage the send stops before any sign-in`() {
        val controller = controller(FakeCredentialStore(), keyStore = FakeDpopKeyStore(available = false))

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        assertEquals(NoPersistentKeyException.CODE, (controller.state as SendState.Error).code)
        assertEquals(0, browsed)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a refusal reaches the overlay with its code and the server's detail`() {
        draftAnswer = { ex ->
            respond(ex, 403, """{"status":403,"code":"CLIENT_VERSION_UNSUPPORTED","detail":"Bitte aktualisieren."}""")
        }
        val (store, _) = stored()
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        val error = controller.state as SendState.Error
        assertEquals(SendState.Error("Bitte aktualisieren.", Codes.CLIENT_VERSION_UNSUPPORTED, status = 403), error)
        assertNotNull(store.stored)
    }

    @Test
    fun `every send reads the service document first`() {
        val (store, _) = stored()

        runBlocking { controller(store).request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        assertEquals(1, serviceDocuments)
    }

    @Test
    fun `an unauthenticated answer is met with one refreshed retry`() {
        var calls = 0
        draftAnswer = { ex ->
            if (calls++ == 0) {
                respond(ex, 401, """{"status":401,"code":"UNAUTHENTICATED","detail":"The token is not valid."}""")
            } else {
                respond(ex, 200, """{"frontendUrl":"https://app/bp?handoff=B2","handoffId":"B2","kind":"BLUEPRINT"}""")
            }
        }
        val (store, _) = stored()
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        assertEquals(SendState.Done("https://app/bp?handoff=B2"), controller.state)
        assertEquals(2, calls)
        assertEquals(0, browsed)
    }

    @Test
    fun `an unauthenticated answer after the retry is not retried again`() {
        var calls = 0
        draftAnswer = { ex ->
            calls++
            ex.responseHeaders.add("X-Correlation-Id", "req-7f3a")
            respond(ex, 401, """{"status":401,"code":"UNAUTHENTICATED","detail":"The token is not valid."}""")
        }
        val (store, _) = stored()
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        val error = controller.state as SendState.Error
        assertEquals(Codes.UNAUTHENTICATED, error.code)
        assertEquals("req-7f3a", error.reference)
        assertEquals(2, calls)
        assertNotNull(store.stored, "the stored login stays; only a dead refresh token ends it")
    }

    @Test
    fun `a slow-down carries the server's wait to the overlay`() {
        draftAnswer = { ex ->
            ex.responseHeaders.add("Retry-After", "42")
            respond(ex, 429, """{"status":429,"code":"RATE_LIMITED","detail":"Too many requests."}""")
        }
        val (store, _) = stored()
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        assertEquals(42L, (controller.state as SendState.Error).retryAfterSeconds)
    }

    @Test
    fun `a second press while the back-off runs sends nothing`() {
        draftAnswer = { ex ->
            ex.responseHeaders.add("Retry-After", "42")
            respond(ex, 503, """{"status":503,"code":"EXCHANGE_DISABLED","detail":"The exchange is switched off."}""")
        }
        val (store, _) = stored()
        val controller = controller(store)
        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }
        val requests = seen.size
        val documents = serviceDocuments

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        val error = controller.state as SendState.Error
        assertEquals(Codes.BACKING_OFF, error.code)
        assertTrue(assertNotNull(error.retryAfterSeconds) >= 42, "never below the server's Retry-After")
        assertEquals(requests, seen.size)
        assertEquals(documents, serviceDocuments)
        assertTrue(sendErrorText(StringsDe.send, error).contains("${error.retryAfterSeconds}"))
    }

    @Test
    fun `a code the overlay does not know is explained by its status`() {
        draftAnswer = { ex -> respond(ex, 503, """{"status":503,"code":"SOMETHING_NEW","detail":"New."}""") }
        val (store, _) = stored()
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.BLUEPRINT, "{}", "de", "Windows-PC") }

        val error = controller.state as SendState.Error
        assertEquals(503, error.status)
        assertEquals(StringsDe.send.errorUnavailable(null), sendErrorText(StringsDe.send, error))
    }

    @Test
    fun `a locally refused payload is shown as it is and nothing is sent`() {
        val controller = controller(stored().first)

        controller.refuse("Zu viele.")

        val error = controller.state as SendState.Error
        assertEquals("Zu viele.", sendErrorText(StringsDe.send, error))
        assertTrue(seen.isEmpty())
    }
}
