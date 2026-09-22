package com.basetool.bpextractor.ui

import com.basetool.bpextractor.config.AppConfig
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopProofs
import com.basetool.bpextractor.net.auth.FakeCredentialStore
import com.basetool.bpextractor.net.auth.FakeDpopKeyStore
import com.basetool.bpextractor.net.auth.StoredCredential
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /** The `Authorization` / `DPoP` headers the gateway stand-in last saw (DPoP assertions). */
    private var ingestAuth: String? = null
    private var ingestProof: String? = null

    /** Per-test override for the gateway answer — the contexts are registered once, in [setUp]. */
    private var ingestHandler: ((HttpExchange, String) -> Unit)? = null

    /** Per-test override for the device-authorization answer (default: fail fast, no poll). */
    private var deviceHandler: ((HttpExchange) -> Unit)? = null

    /** The persistent-key storage stand-in — in memory, no CNG (the non-Windows test seam). */
    private val keys = FakeDpopKeyStore()

    /** The send state at the moment the browser was opened (the Authenticating step). */
    private var stateAtBrowse: SendState? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            deviceCalls.incrementAndGet()
            val override = deviceHandler
            if (override != null) {
                override(ex)
            } else {
                respond(ex, 400, """{"error":"unauthorized_client"}""") // fallback path: fail fast, no poll
            }
        }
        server.createContext("/v1/refinery-extract") { ex ->
            ingest(ex, """{"handoffId":"H1","kind":"REFINERY","frontendUrl":"https://app/x?handoff=H1"}""")
        }
        server.createContext("/v1/blueprint-preview") { ex ->
            ingest(ex, """{"handoffId":"B1","kind":"BLUEPRINT","frontendUrl":"https://app/bp?handoff=B1"}""")
        }
        server.start()
        base = "http://localhost:${server.address.port}"
    }

    /** Records what the send actually presented, then answers [ok] unless a test overrode it. */
    private fun ingest(ex: HttpExchange, ok: String) {
        ingestAuth = ex.requestHeaders.getFirst("Authorization")
        ingestProof = ex.requestHeaders.getFirst("DPoP")
        val override = ingestHandler
        if (override != null) override(ex, ok) else respond(ex, 200, ok)
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

    private fun controller(store: FakeCredentialStore, keyStore: FakeDpopKeyStore = keys): SendController {
        lateinit var controller: SendController
        controller =
            SendController(
                configStore = consentedConfig(),
                deviceGrant = DeviceGrantClient(issuer = base),
                credentialStore = store,
                keyStore = keyStore,
                browse = {
                    browseCount++
                    stateAtBrowse = controller.state
                },
            )
        return controller
    }

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
        // The vault blob is a StoredCredential record now (token + its DPoP key), not a bare token.
        assertEquals(
            "RT-ROTATED",
            StoredCredential.decode(assertNotNull(store.stored))?.refreshToken,
            "the rotated refresh token must be persisted",
        )
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
        assertTrue(keys.keys.isEmpty(), "no persistent key may be left behind by a failed login")
    }

    @Test
    fun `a dead token bound to a persistent key takes that key with it`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"invalid_grant"}""")
        }
        val key = assertNotNull(keys.create())
        val store = FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-DEAD", key.keyName)))

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertNull(store.stored)
        assertTrue(key.keyName in keys.deleted, "the dead credential's key must be deleted too")
        assertTrue(keys.keys.isEmpty())
    }

    @Test
    fun `a credential whose key is gone is dropped instead of redeemed`() {
        // A cleared TPM or a profile copied onto another machine: the name no longer opens, so the
        // bound token can never be redeemed — no refresh is even attempted.
        val tokenCalls = AtomicInteger(0)
        server.createContext("/protocol/openid-connect/token") { ex ->
            tokenCalls.incrementAndGet()
            respond(ex, 400, """{"error":"invalid_grant"}""")
        }
        val store =
            FakeCredentialStore(StoredCredential.encode(StoredCredential("RT-ORPHAN", "Basetool SC Extractor DPoP gone")))

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertNull(store.stored)
        assertEquals(0, tokenCalls.get(), "an unredeemable token is not sent anywhere")
        assertEquals(1, deviceCalls.get(), "the member signs in afresh")
    }

    // --- DPoP (RFC 9449, REQ-INGEST-012) -------------------------------------------------------

    @Test
    fun `a bound token goes to the gateway under DPoP with a proof, and its key is persisted`() {
        // ADR-0129: the gateway VALIDATES the proof now instead of relaying the token onward, so a
        // sender-constrained token finally pays — the party that checks the proof is the party that
        // consumes it. 2.7.x sent this same bound token as a plain bearer, which a resource server
        // refuses outright; that is what broke every send from 2026-08-03.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"DPoP","expires_in":300}""")
        }
        val store = FakeCredentialStore("RT-STORED")

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertEquals("DPoP AT", ingestAuth, "a bound token goes out under the DPoP scheme")
        assertNotNull(ingestProof, "and carries the proof the gateway validates")

        // The refresh token and the NAME of the key that redeems it are stored together; the key
        // itself stays in the key storage (a bound refresh token needs its own key).
        val stored = assertNotNull(StoredCredential.decode(assertNotNull(store.stored)))
        assertEquals("RT-ROTATED", stored.refreshToken)
        val key = assertNotNull(keys.open(assertNotNull(stored.dpopKeyName)))
        assertEquals(
            key.thumbprint,
            DpopProofs.thumbprint(assertNotNull(ingestProof)),
            "the gateway proof is signed by the stored key",
        )
        assertEquals(1, keys.keys.size, "exactly one persistent key")
    }

    @Test
    fun `a bound token with no persistent key available is used but not remembered`() {
        // No key storage (another OS, a broken CNG): the session key signs this send, but a token
        // bound to a key that dies with the process must not be persisted — and neither may the key.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"DPoP","expires_in":300}""")
        }
        val store = FakeCredentialStore("RT-STORED")

        runBlocking {
            controller(store, FakeDpopKeyStore(available = false))
                .request(this, SendKind.REFINERY, """{"x":1}""", "de")
        }

        assertEquals("DPoP AT", ingestAuth, "the send itself still works under DPoP")
        // The new token dies with the process, so it is not stored; the stored one is left as it
        // was (rotation is off realm-wide, so it is still valid).
        assertEquals("RT-STORED", store.stored, "the unredeemable-after-exit token is not stored")
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `an unbound token keeps the plain bearer — the extractor follows the server`() {
        // Still load-bearing, and the reason the key stays optional: a Keycloak with DPoP off
        // answers token_type=Bearer, and presenting such a token under the DPoP scheme is a hard 401
        // — Spring's JwkThumbprintValidator requires cnf.jkt and says "jkt claim is required". The
        // extractor must follow the server, not its own wish to use DPoP. It is also the transition
        // safety net: the gateway keeps .jwt() alongside .dPoP(), so this path stays accepted.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"Bearer","expires_in":300}""")
        }

        runBlocking { controller(FakeCredentialStore("RT-STORED")).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertEquals("Bearer AT", ingestAuth)
        assertNull(ingestProof, "an unbound token must not be accompanied by a proof")
    }

    @Test
    fun `a legacy stored token still refreshes and is upgraded to a keyed record`() {
        // Users of the already-released build have a bare refresh token in the vault. Keycloak binds
        // the newly issued pair to the proof's key, so the very next send upgrades the credential
        // in place — no forced re-login.
        val proofs = mutableListOf<String?>()
        server.createContext("/protocol/openid-connect/token") { ex ->
            proofs.add(ex.requestHeaders.getFirst("DPoP"))
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-BOUND","token_type":"DPoP","expires_in":300}""")
        }
        val store = FakeCredentialStore("plain-legacy-refresh-token")

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertNotNull(proofs.single(), "the refresh of a legacy token still carries a proof")
        assertEquals(0, deviceCalls.get(), "and must not force an interactive login")
        val stored = assertNotNull(StoredCredential.decode(assertNotNull(store.stored)))
        assertEquals("RT-BOUND", stored.refreshToken)
        assertNotNull(keys.open(assertNotNull(stored.dpopKeyName, "the record is rewritten in the keyed shape")))
    }

    @Test
    fun `the stored key is the one reused on the next send`() {
        // Reusing the key is the whole point: a refresh token bound to key A cannot be redeemed with
        // key B, so a controller that minted a fresh key per send would lock the user out.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"DPoP","expires_in":300}""")
        }
        val store = FakeCredentialStore("RT-STORED")

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }
        val firstKey = assertNotNull(StoredCredential.decode(assertNotNull(store.stored))?.dpopKeyName)
        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }
        val secondKey = assertNotNull(StoredCredential.decode(assertNotNull(store.stored))?.dpopKeyName)

        assertEquals(firstKey, secondKey, "a persisted key must be reused, never regenerated")
        assertEquals(setOf(firstKey), keys.keys.keys, "and no second key is created along the way")
    }

    // --- migration off the exportable key (SIB-SEC-04) --------------------------------------------

    @Test
    fun `a record with an exported key is revoked, deleted, and the member signs in once more`() {
        // Records written before the non-exportable key carry the private key next to the token, so
        // a copy of the record was the login. The token is bound to that exported key and cannot
        // move onto a non-exportable one: it is revoked (with a proof from the old key, so a copy
        // dies too), the record is deleted, and the member goes through one device grant — told why.
        val legacyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val legacyExport =
            Base64.getEncoder().encodeToString(legacyPair.private.encoded) + "." +
                Base64.getEncoder().encodeToString(legacyPair.public.encoded)
        val store = FakeCredentialStore("""{"refreshToken":"RT-OLD","dpopKey":"$legacyExport"}""")
        var revokedBody = ""
        var revokeProof: String? = null
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            revokedBody = ex.requestBody.readBytes().decodeToString()
            revokeProof = ex.requestHeaders.getFirst("DPoP")
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        val refreshed = mutableListOf<String>()
        server.createContext("/protocol/openid-connect/token") { ex ->
            refreshed += ex.requestBody.readBytes().decodeToString()
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-NEW","token_type":"DPoP","expires_in":300}""")
        }
        deviceHandler = { ex ->
            respond(
                ex,
                200,
                """{"device_code":"DC","user_code":"ABCD-EFGH","verification_uri":"https://sso.example/device",""" +
                    """"expires_in":60,"interval":1}""",
            )
        }
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        // The old token was revoked, with a proof from the old (exported) key.
        assertTrue(revokedBody.contains("token=RT-OLD"), "the legacy token must be revoked")
        val legacyThumbprint = assertNotNull(DpopKey.fromLegacyExport(legacyExport)).thumbprint
        assertEquals(legacyThumbprint, DpopProofs.thumbprint(assertNotNull(revokeProof)))
        // It was never redeemed — only the device-code grant hit the token endpoint.
        assertTrue(refreshed.none { it.contains("RT-OLD") }, "the legacy token must not be refreshed")
        assertEquals(1, deviceCalls.get(), "the member signs in once via the device grant")
        // And the overlay said why.
        val shown = stateAtBrowse
        assertTrue(shown is SendState.Authenticating && shown.keyUpgrade, "was $shown")
        assertTrue(controller.state is SendState.Done, "was ${controller.state}")
        // The new record names a non-exportable key and holds no key material.
        val blob = assertNotNull(store.stored)
        assertFalse(blob.contains(legacyExport.substringBefore('.')))
        assertFalse(blob.contains("\"dpopKey\""))
        val stored = assertNotNull(StoredCredential.decode(blob))
        assertEquals("RT-NEW", stored.refreshToken)
        assertNotNull(keys.open(assertNotNull(stored.dpopKeyName)))
    }

    @Test
    fun `an ordinary interactive login does not claim to be the key upgrade`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"DPoP","expires_in":300}""")
        }
        deviceHandler = { ex ->
            respond(
                ex,
                200,
                """{"device_code":"DC","user_code":"ABCD-EFGH","verification_uri":"https://sso.example/device",""" +
                    """"expires_in":60,"interval":1}""",
            )
        }

        runBlocking { controller(FakeCredentialStore()).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        val shown = stateAtBrowse
        assertTrue(shown is SendState.Authenticating && !shown.keyUpgrade, "was $shown")
    }

    @Test
    fun `a rejected proof does not cost the user their stored login`() {
        // Keycloak validates the DPoP proof BEFORE the grant and reports every proof defect as a
        // generic invalid_request — a clock more than 15s fast is enough. That says nothing about
        // the refresh token, so it must not be deleted, and there is no point opening a browser for
        // a device grant whose polls carry the very same proof.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"invalid_request","error_description":"DPoP proof is not active"}""")
        }
        val store = FakeCredentialStore("RT-STILL-VALID")
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertEquals("RT-STILL-VALID", store.stored, "a proof error must not destroy the credential")
        assertTrue(keys.keys.isEmpty(), "the key minted for the refresh attempt is not left behind")
        assertEquals(0, deviceCalls.get(), "and must not start a doomed interactive grant")
        assertEquals(0, browseCount)
        val state = controller.state
        assertTrue(state is SendState.Error, "expected Error, was $state")
        // The server's own words, so a clock-skew failure is diagnosable instead of a bare HTTP 400.
        assertTrue(
            state.message.contains("DPoP proof is not active"),
            "the reason must reach the user, was: ${state.message}",
        )
    }

    @Test
    fun `a 403 CLIENT_NOT_ALLOWED ends the flow with its code and no second attempt`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"Bearer","expires_in":300}""")
        }
        val attempts = AtomicInteger(0)
        ingestHandler = { ex, _ ->
            attempts.incrementAndGet()
            respond(
                ex,
                403,
                """{"title":"Client not allowed","detail":"This client is not approved for the basetool """ +
                    """ingest path.","status":403,"code":"CLIENT_NOT_ALLOWED"}""",
            )
        }
        val controller = controller(FakeCredentialStore("RT-STORED"))

        runBlocking { controller.request(this, SendKind.BLUEPRINT, """{"x":1}""", "de") }

        val state = controller.state
        assertTrue(state is SendState.Error, "expected Error, was $state")
        // The code is what lets the overlay say "this build is not approved" instead of echoing an
        // English server sentence that reads like something a retry could fix.
        assertEquals("CLIENT_NOT_ALLOWED", state.code)
        assertEquals(1, attempts.get(), "a permanent refusal is never re-sent")
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
