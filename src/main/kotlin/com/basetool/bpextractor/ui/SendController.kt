package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.Codes
import com.basetool.bpextractor.net.ExchangeClient
import com.basetool.bpextractor.net.ExchangeException
import com.basetool.bpextractor.net.InstallationLabel
import com.basetool.bpextractor.net.auth.CngDpopKeyStore
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DeviceGrantException
import com.basetool.bpextractor.net.auth.DpopKeyStore
import com.basetool.bpextractor.net.auth.ExchangeGrant
import com.basetool.bpextractor.net.auth.ExchangeLogin
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.net.auth.NoPersistentKeyException
import com.basetool.bpextractor.net.auth.UnboundTokenException
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

    /**
     * Consent before any data leaves the machine, together with the label this installation carries in
     * the Basetool; shown on the first send and whenever no label was chosen yet.
     *
     * @param label the label as the member is typing it
     * @param labelInvalid whether the last confirmation was refused because of the label
     */
    data class Consent(val label: String, val labelInvalid: Boolean = false) : SendState

    /**
     * Browser opened; waiting for the user to approve the shown code.
     *
     * @param userCode the code the user confirms in the browser
     * @param browserUrl the verification URL that was opened
     * @param reason why the browser is needed instead of the stored login
     */
    data class Authenticating(
        val userCode: String,
        val browserUrl: String,
        val reason: LoginReason = LoginReason.NONE,
    ) : SendState

    /** Token obtained; uploading the export to the gateway. */
    data object Sending : SendState

    /** Done; [frontendUrl] opens the pre-filled basetool page. */
    data class Done(val frontendUrl: String) : SendState

    /**
     * A safe-to-show failure message (auth, network or rejected).
     *
     * @param message the already-localized detail to put in front of the user
     * @param code a machine-readable reason the overlay explains in plain language: the exchange's
     *   problem `code` (see [Codes]), [NoPersistentKeyException.CODE] or [UnboundTokenException.CODE];
     *   empty otherwise
     * @param clockOffsetSeconds the measured clock deviation from the server, as in
     *   [DeviceGrantException.clockOffsetSeconds]; zero otherwise
     */
    data class Error(
        val message: String,
        val code: String = "",
        val clockOffsetSeconds: Long = 0,
    ) : SendState
}

/** Which draft route a send targets — the refinery extract or the blueprint envelope. */
enum class SendKind {
    /** `POST /exchange/v1/me/drafts/refinery-orders` — a `RefineryExtract` document. */
    REFINERY,

    /** `POST /exchange/v1/me/drafts/blueprints` — a `basetool.blueprints` envelope. */
    BLUEPRINT,
}

/**
 * Drives the "An Basetool senden" flow for a workflow export: consent with the installation label, the
 * exchange login, the draft upload, then opening the pre-filled basetool page. A Compose state holder
 * ([state]) whose heavy work runs on [Dispatchers.IO].
 */
class SendController(
    private val configStore: AppConfigStore = AppConfigStore(),
    deviceGrant: DeviceGrantClient = DeviceGrantClient(),
    credentialStore: CredentialStore = WinCredentialStore(),
    private val exchangeClientFor: (String) -> ExchangeClient = { ExchangeClient(it) },
    keyStore: DpopKeyStore = CngDpopKeyStore(),
    private val browse: (String) -> Unit = { url ->
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    },
) {

    private val login = ExchangeLogin(deviceGrant, credentialStore, keyStore)

    var state by mutableStateOf<SendState>(SendState.Idle)
        private set

    private var pendingJson: String = ""
    private var pendingLang: String = "de"
    private var pendingKind: SendKind = SendKind.REFINERY

    /**
     * Entry point from a workflow's export/summary step: stashes the payload and which draft route it
     * targets, then shows the consent step when it is due, or starts the flow directly.
     *
     * @param scope the UI coroutine scope to run the flow on
     * @param kind which draft route to send to
     * @param exportJson the serialized export to send (RefineryExtract or blueprint envelope)
     * @param lang the UI locale tag to relay as Accept-Language
     * @param defaultLabel the label to offer when the member has not chosen one yet
     */
    fun request(scope: CoroutineScope, kind: SendKind, exportJson: String, lang: String, defaultLabel: String) {
        pendingKind = kind
        pendingJson = exportJson
        pendingLang = lang
        val config = configStore.load()
        if (config.consentGiven && config.installationLabel != null) {
            run(scope)
        } else {
            state = SendState.Consent(config.installationLabel ?: defaultLabel)
        }
    }

    /** Takes the label the member is typing on the consent step. */
    fun editLabel(label: String) {
        (state as? SendState.Consent)?.let { state = it.copy(label = label, labelInvalid = false) }
    }

    /** Records consent and the installation label (both non-secret) and starts the flow. */
    fun confirmConsent(scope: CoroutineScope) {
        val consent = state as? SendState.Consent ?: return
        val label = consent.label.trim()
        if (!InstallationLabel.isValid(label)) {
            state = consent.copy(labelInvalid = true)
            return
        }
        val config = configStore.load()
        configStore.save(config.copy(consentGiven = true, installationLabel = label))
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
                val config = withContext(Dispatchers.IO) { configStore.load() }
                val grant = withContext(Dispatchers.IO) { obtain(DeviceGrantClient.BASE_SCOPES) }
                withContext(Dispatchers.IO) { login.remember(grant) }
                state = SendState.Sending
                val response =
                    withContext(Dispatchers.IO) {
                        val client = exchangeClientFor(config.ingestBaseUrl)
                        val credentials = grant.credentials()
                        if (grant.fresh) label(client, grant, config.installationLabel)
                        when (pendingKind) {
                            SendKind.REFINERY -> client.draftRefinery(credentials, pendingJson, pendingLang)
                            SendKind.BLUEPRINT -> client.draftBlueprints(credentials, pendingJson, pendingLang)
                        }
                    }
                state = SendState.Done(response.frontendUrl)
            } catch (e: DeviceGrantException) {
                state = SendState.Error(e.message ?: "authentication failed", "", e.clockOffsetSeconds)
            } catch (e: NoPersistentKeyException) {
                state = SendState.Error(e.message.orEmpty(), NoPersistentKeyException.CODE)
            } catch (e: UnboundTokenException) {
                state = SendState.Error(e.message.orEmpty(), UnboundTokenException.CODE)
            } catch (e: ExchangeException) {
                if (e.code == Codes.INSTALLATION_REVOKED || e.code == Codes.CLIENT_REVOKED) {
                    withContext(Dispatchers.IO) { login.forget() }
                }
                state = SendState.Error(e.message ?: "send failed", e.code)
            } catch (e: Exception) {
                state = SendState.Error(e.message ?: "send failed")
            }
        }
    }

    private fun obtain(scopes: Set<String>): ExchangeGrant =
        login.obtain(scopes) { device, reason ->
            state = SendState.Authenticating(device.userCode, device.browserUrl(), reason)
            browse(device.browserUrl())
        }

    /**
     * Labels a new installation with the member's label. Best-effort: an installation without a label
     * still works, and the draft call that follows reports any refusal that matters.
     */
    private fun label(client: ExchangeClient, grant: ExchangeGrant, label: String?) {
        if (label == null) return
        try {
            client.labelInstallation(grant.credentials(), label, pendingLang)
        } catch (_: ExchangeException) {
        }
    }
}
