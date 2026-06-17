package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.BasetoolIngestClient
import com.basetool.bpextractor.net.IngestException
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DeviceGrantException
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one-click-send overlay state machine (epic krt-iri/basetool#639). */
sealed interface SendState {
    /** No send in progress; the overlay is hidden. */
    data object Idle : SendState

    /** First-time consent before any data leaves the machine. */
    data object Consent : SendState

    /** Browser opened; waiting for the user to approve the shown code. */
    data class Authenticating(val userCode: String, val browserUrl: String) : SendState

    /** Token obtained; uploading the export to the gateway. */
    data object Sending : SendState

    /** Done; [frontendUrl] opens the pre-filled basetool page. */
    data class Done(val frontendUrl: String) : SendState

    /** A safe-to-show failure message (auth / network / rejected). */
    data class Error(val message: String) : SendState
}

/**
 * Drives the "An Basetool senden" flow for the refinery export: one-time consent → Keycloak device
 * grant (show the user code, open the browser, poll) → upload to the ingest gateway → open the
 * pre-filled basetool page. A Compose state holder ([state]); the heavy work runs on
 * [Dispatchers.IO]. The collaborators are injected so the net layer stays pure and the controller
 * is exercisable without a real Keycloak/gateway.
 */
class SendController(
    private val configStore: AppConfigStore = AppConfigStore(),
    private val deviceGrant: DeviceGrantClient = DeviceGrantClient(),
    private val ingestClientFor: (String) -> BasetoolIngestClient = { BasetoolIngestClient(it) },
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

    /**
     * Entry point from the export step: stashes the payload, then shows the consent overlay on the
     * first ever send or starts the flow directly once consent was given.
     *
     * @param scope the UI coroutine scope to run the flow on
     * @param extractJson the serialized RefineryExtract to send
     * @param lang the UI locale tag to relay as Accept-Language
     */
    fun request(scope: CoroutineScope, extractJson: String, lang: String) {
        pendingJson = extractJson
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
                val device = withContext(Dispatchers.IO) { deviceGrant.requestDeviceCode() }
                state = SendState.Authenticating(device.userCode, device.browserUrl())
                browse(device.browserUrl())
                val token = withContext(Dispatchers.IO) { deviceGrant.pollForToken(device) }
                state = SendState.Sending
                val response =
                    withContext(Dispatchers.IO) {
                        ingestClientFor(baseUrl)
                            .sendRefinery(token.accessToken, pendingJson, pendingLang)
                    }
                state = SendState.Done(response.frontendUrl)
            } catch (e: DeviceGrantException) {
                state = SendState.Error(e.message ?: "authentication failed")
            } catch (e: IngestException) {
                state = SendState.Error(e.message ?: "send failed")
            } catch (e: Exception) {
                state = SendState.Error(e.message ?: "send failed")
            }
        }
    }
}
