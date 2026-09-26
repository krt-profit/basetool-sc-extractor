package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.BasetoolIngestClient
import com.basetool.bpextractor.net.IngestException
import com.basetool.bpextractor.net.IngestProblem
import com.basetool.bpextractor.net.auth.CngDpopKeyStore
import com.basetool.bpextractor.net.auth.CredentialRecord
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DeviceGrantException
import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopKeyStore
import com.basetool.bpextractor.net.auth.DpopNonce
import com.basetool.bpextractor.net.auth.StoredCredential
import com.basetool.bpextractor.net.auth.TokenResponse
import com.basetool.bpextractor.net.auth.WinCredentialStore
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The state machine of the one-click-send overlay. */
sealed interface SendState {
    /** No send in progress; the overlay is hidden. */
    data object Idle : SendState

    /** First-time consent before any data leaves the machine. */
    data object Consent : SendState

    /**
     * Browser opened; waiting for the user to approve the shown code.
     *
     * @param userCode the code the user confirms in the browser
     * @param browserUrl the verification URL that was opened
     * @param keyUpgrade `true` for the one-time re-login after a stored login with an exportable DPoP key
     *   ([com.basetool.bpextractor.net.auth.CredentialRecord.LegacyExportedKey]) was discarded
     */
    data class Authenticating(
        val userCode: String,
        val browserUrl: String,
        val keyUpgrade: Boolean = false,
    ) : SendState

    /** Token obtained; uploading the export to the gateway. */
    data object Sending : SendState

    /** Done; [frontendUrl] opens the pre-filled basetool page. */
    data class Done(val frontendUrl: String) : SendState

    /**
     * A safe-to-show failure message (auth, network or rejected).
     *
     * @param message the already-localized detail to put in front of the user
     * @param code a machine-readable reason the overlay explains in plain language: the gateway's RFC 7807
     *   `code` (e.g. [IngestProblem.CLIENT_NOT_ALLOWED]) or [DpopNonce.CODE]; empty otherwise
     * @param clockOffsetSeconds the measured clock deviation from the server, as in
     *   [DeviceGrantException.clockOffsetSeconds]; zero otherwise
     */
    data class Error(
        val message: String,
        val code: String = "",
        val clockOffsetSeconds: Long = 0,
    ) : SendState
}

/** Which ingest endpoint a send targets — the refinery extract or the blueprint export. */
enum class SendKind {
    /** {@code POST /v1/refinery-extract} — a {@code RefineryExtract} document. */
    REFINERY,

    /** {@code POST /v1/blueprint-preview} — a {@code BlueprintExport} document. */
    BLUEPRINT,
}

/**
 * Drives the "An Basetool senden" flow for a workflow export: one-time consent, Keycloak device grant,
 * upload to the ingest gateway, then opening the pre-filled basetool page. A Compose state holder
 * ([state]) whose heavy work runs on [Dispatchers.IO].
 */
class SendController(
    private val configStore: AppConfigStore = AppConfigStore(),
    private val deviceGrant: DeviceGrantClient = DeviceGrantClient(),
    private val credentialStore: CredentialStore = WinCredentialStore(),
    private val ingestClientFor: (String) -> BasetoolIngestClient = { BasetoolIngestClient(it) },
    private val keyStore: DpopKeyStore = CngDpopKeyStore(),
    private val browse: (String) -> Unit = { url ->
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    },
) {

    var state by mutableStateOf<SendState>(SendState.Idle)
        private set

    private var pendingJson: String = ""
    private var pendingLang: String = "de"
    private var pendingKind: SendKind = SendKind.REFINERY

    /**
     * The in-memory fallback DPoP key for this process — see [newKey]. Never logged, never persisted.
     */
    private var sessionKey: DpopKey? = null

    /**
     * Entry point from a workflow's export/summary step: stashes the payload + which ingest
     * endpoint it targets, then shows the consent overlay on the first ever send or starts the flow
     * directly once consent was given.
     *
     * @param scope the UI coroutine scope to run the flow on
     * @param kind which ingest endpoint to send to (refinery extract vs blueprint export)
     * @param exportJson the serialized export to send (RefineryExtract or BlueprintExport)
     * @param lang the UI locale tag to relay as Accept-Language
     */
    fun request(scope: CoroutineScope, kind: SendKind, exportJson: String, lang: String) {
        pendingKind = kind
        pendingJson = exportJson
        pendingLang = lang
        if (configStore.load().consentGiven) run(scope) else state = SendState.Consent
    }

    /** Records consent (persisted, non-secret) and starts the flow. */
    fun confirmConsent(scope: CoroutineScope) {
        val config = configStore.load()
        configStore.save(config.copy(consentGiven = true))
        run(scope)
    }

    /** Hides the overlay (cancel / close / dismiss an error). */
    fun dismiss() {
        state = SendState.Idle
    }

    /** Re-opens the verification URL if the browser did not come up the first time. */
    fun reopenBrowser() {
        (state as? SendState.Authenticating)?.let { browse(it.browserUrl) }
    }

    /** Opens the pre-filled basetool page after a successful send. */
    fun openResult() {
        (state as? SendState.Done)?.let { browse(it.frontendUrl) }
    }

    private fun run(scope: CoroutineScope) {
        scope.launch {
            try {
                val baseUrl = withContext(Dispatchers.IO) { configStore.load().ingestBaseUrl }
                val grant = withContext(Dispatchers.IO) { obtainToken() }
                withContext(Dispatchers.IO) { remember(grant) }
                state = SendState.Sending
                val boundKey = grant.key.takeIf { grant.token.isDpopBound() }
                val response =
                    withContext(Dispatchers.IO) {
                        val client = ingestClientFor(baseUrl)
                        when (pendingKind) {
                            SendKind.REFINERY ->
                                client.sendRefinery(
                                    grant.token.accessToken, pendingJson, pendingLang, boundKey)
                            SendKind.BLUEPRINT ->
                                client.sendBlueprint(
                                    grant.token.accessToken, pendingJson, pendingLang, boundKey)
                        }
                    }
                state = SendState.Done(response.frontendUrl)
            } catch (e: DeviceGrantException) {
                state =
                    SendState.Error(
                        e.message ?: "authentication failed",
                        if (e.oauthError == DpopNonce.USE_DPOP_NONCE) DpopNonce.CODE else "",
                        e.clockOffsetSeconds,
                    )
            } catch (e: IngestException) {
                state = SendState.Error(e.message ?: "send failed", e.code)
            } catch (e: Exception) {
                state = SendState.Error(e.message ?: "send failed")
            }
        }
    }

    /**
     * A token together with the DPoP key its proofs are (and its refresh token stays) signed by.
     *
     * @param token the token answer
     * @param key the key the token is bound to
     * @param stored the credential this grant was redeemed from, or `null` for a fresh login
     */
    private data class Grant(val token: TokenResponse, val key: DpopKey, val stored: StoredCredential? = null)

    /**
     * Persists [grant] for the next silent send: a token bound to a persistent key is stored with the
     * key's name, an unbound token alone. A token bound to the in-memory [sessionKey] is not stored, and
     * any previous record is left as it is. A key no record names is deleted.
     */
    private fun remember(grant: Grant) {
        val token = grant.token
        val keyName = grant.key.keyName
        val saved =
            when {
                token.refreshToken.isBlank() -> false
                keyName != null -> credentialStore.saveCredential(StoredCredential(token.refreshToken, keyName))
                !token.isDpopBound() -> credentialStore.saveCredential(StoredCredential(token.refreshToken))
                else -> false
            }
        if (!saved && keyName != null && grant.stored?.dpopKeyName != keyName) keyStore.delete(keyName)
    }

    /**
     * Obtains an access token by silent refresh of the stored credential, falling back to an interactive
     * device grant when none is usable or the refresh is rejected. Runs on the calling IO context.
     *
     * The stored credential's DPoP key is opened by name from the [keyStore]; a missing key drops the
     * credential, a bare refresh token is bound to a fresh key, and a record with an exported key is
     * retired so the member signs in again ([SendState.Authenticating.keyUpgrade]).
     *
     * @return the token answer plus its key; the token's `refreshToken` is the one to persist
     * @throws DeviceGrantException when the interactive grant ultimately fails
     */
    private fun obtainToken(): Grant {
        var keyUpgrade = false
        when (val record = credentialStore.loadRecord()) {
            is CredentialRecord.LegacyExportedKey -> {
                retire(record)
                keyUpgrade = true
            }
            is CredentialRecord.Current -> refresh(record.credential)?.let { return it }
            null -> Unit
        }
        val device = deviceGrant.requestDeviceCode()
        val key = newKey()
        try {
            state = SendState.Authenticating(device.userCode, device.browserUrl(), keyUpgrade)
            browse(device.browserUrl())
            return Grant(deviceGrant.pollForToken(device, dpopKey = key), key)
        } catch (t: Throwable) {
            discard(key)
            throw t
        }
    }

    /**
     * The silent path for a usable stored credential.
     *
     * @return the grant, or `null` when the credential turned out dead and was dropped — the caller
     *   then continues with an interactive login
     * @throws DeviceGrantException when the refresh failed for a reason that says nothing about the
     *   credential (a proof or transport problem) — it is kept, and the send fails
     */
    private fun refresh(stored: StoredCredential): Grant? {
        val keyName = stored.dpopKeyName
        val key = if (keyName == null) newKey() else keyStore.open(keyName)
        if (key == null) {
            forget(stored)
            return null
        }
        try {
            return Grant(deviceGrant.refreshAccessToken(stored.refreshToken, key), key, stored)
        } catch (e: DeviceGrantException) {
            if (keyName == null) discard(key)
            if (e.oauthError !in DeviceGrantException.TOKEN_REJECTED) throw e
            forget(stored)
            return null
        }
    }

    /**
     * Destroys a record that still carries an exported DPoP key: revokes its refresh token with that key
     * first (best-effort), then deletes the record.
     */
    private fun retire(record: CredentialRecord.LegacyExportedKey) {
        DpopKey.fromLegacyExport(record.exportedKey)?.let { legacyKey ->
            deviceGrant.revoke(record.refreshToken, legacyKey)
        }
        credentialStore.clear()
    }

    /** Drops a stored credential together with its persistent key. */
    private fun forget(stored: StoredCredential) {
        credentialStore.clear()
        stored.dpopKeyName?.let(keyStore::delete)
    }

    /** Deletes [key] from the key storage when it is a persistent one nobody will use. */
    private fun discard(key: DpopKey) {
        key.keyName?.let(keyStore::delete)
    }

    /**
     * A key for a new binding: a fresh persistent, non-exportable key from the [keyStore] when one can be
     * created, else the in-memory session key, generated once per process. A session-key token is not
     * remembered ([remember]).
     */
    private fun newKey(): DpopKey =
        keyStore.create() ?: sessionKey ?: DpopKey.generate().also { sessionKey = it }
}
