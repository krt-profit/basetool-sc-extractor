package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.Backoff
import com.basetool.bpextractor.net.Codes
import com.basetool.bpextractor.net.ExchangeClient
import com.basetool.bpextractor.net.ExchangeException
import com.basetool.bpextractor.net.ExchangeSession
import com.basetool.bpextractor.net.InstallationLabel
import com.basetool.bpextractor.net.auth.CngDpopKeyStore
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceCodeResponse
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DeviceGrantException
import com.basetool.bpextractor.net.auth.DpopKeyStore
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
     * @param reference the request's `X-Correlation-Id`, shown so the member can quote it in a report
     * @param retryAfterSeconds how long the server asked to wait, or the back-off has left, when known
     * @param status the HTTP status of an exchange refusal, for a code the overlay does not know; `0` otherwise
     */
    data class Error(
        val message: String,
        val code: String = "",
        val clockOffsetSeconds: Long = 0,
        val reference: String = "",
        val retryAfterSeconds: Long? = null,
        val status: Int = 0,
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
    private val backoff: Backoff = Backoff.SHARED,
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

    /**
     * Shows [message] as a failure without sending anything, for a payload the Basetool would refuse.
     *
     * @param message the already-localized reason
     */
    fun refuse(message: String) {
        state = SendState.Error(message, REFUSED_LOCALLY)
    }

    private fun run(scope: CoroutineScope) {
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { configStore.load() }
                val client = exchangeClientFor(config.ingestBaseUrl)
                val session = ExchangeSession(login, client, pendingLang, config.installationLabel, backoff = backoff)
                val required = setOf(
                    DeviceGrantClient.CONNECT_SCOPE,
                    if (pendingKind == SendKind.REFINERY) DeviceGrantClient.DRAFTS_REFINERY_SCOPE else DeviceGrantClient.DRAFTS_BLUEPRINTS_SCOPE,
                )
                val response =
                    withContext(Dispatchers.IO) {
                        session.run(DeviceGrantClient.BASE_SCOPES, required, ::onDeviceCode) { credentials ->
                            state = SendState.Sending
                            when (pendingKind) {
                                SendKind.REFINERY -> client.draftRefinery(credentials, pendingJson, pendingLang)
                                SendKind.BLUEPRINT -> client.draftBlueprints(credentials, pendingJson, pendingLang)
                            }
                        }
                    }
                state = SendState.Done(response.frontendUrl)
            } catch (e: Exception) {
                state = failureOf(e)
            }
        }
    }

    private fun onDeviceCode(device: DeviceCodeResponse, reason: LoginReason) {
        state = SendState.Authenticating(device.userCode, device.browserUrl(), reason)
        browse(device.browserUrl())
    }

    companion object {
        /** The code of a failure raised before anything was sent; its message is shown as it is. */
        const val REFUSED_LOCALLY = "REFUSED_LOCALLY"
    }
}

/**
 * The failure the overlay shows for [e]: its code, the request's reference and the server's wait for an
 * exchange refusal, the clock offset for a sign-in failure.
 *
 * @param e what went wrong
 * @return the failure state
 */
fun failureOf(e: Exception): SendState.Error =
    when (e) {
        is DeviceGrantException -> SendState.Error(e.message ?: "authentication failed", "", e.clockOffsetSeconds)
        is NoPersistentKeyException -> SendState.Error(e.message.orEmpty(), NoPersistentKeyException.CODE)
        is UnboundTokenException -> SendState.Error(e.message.orEmpty(), UnboundTokenException.CODE)
        is ExchangeException ->
            SendState.Error(
                e.message ?: "send failed",
                e.code,
                reference = e.correlationId,
                retryAfterSeconds = e.retryAfterSeconds,
                status = e.status,
            )
        else -> SendState.Error(e.message ?: "send failed")
    }
