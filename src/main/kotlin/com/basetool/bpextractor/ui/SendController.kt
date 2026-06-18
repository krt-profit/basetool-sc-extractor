package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.BasetoolIngestClient
import com.basetool.bpextractor.net.IngestException
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DeviceGrantException
import com.basetool.bpextractor.net.auth.TokenResponse
import com.basetool.bpextractor.net.auth.WinCredentialStore
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one-click-send overlay state machine (epic krt-profit/basetool#639). */
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

/** Which ingest endpoint a send targets — the refinery extract or the blueprint export. */
enum class SendKind {
    /** {@code POST /v1/refinery-extract} — a {@code RefineryExtract} document. */
    REFINERY,

    /** {@code POST /v1/blueprint-preview} — a {@code BlueprintExport} document. */
    BLUEPRINT,
}

/**
 * Drives the "An Basetool senden" flow for a workflow export (refinery extract or blueprint):
 * one-time consent → Keycloak device grant (show the user code, open the browser, poll) → upload to
 * the ingest gateway → open the pre-filled basetool page. A Compose state holder ([state]); the
 * heavy work runs on
 * [Dispatchers.IO]. The collaborators are injected so the net layer stays pure and the controller
 * is exercisable without a real Keycloak/gateway.
 */
class SendController(
    private val configStore: AppConfigStore = AppConfigStore(),
    private val deviceGrant: DeviceGrantClient = DeviceGrantClient(),
    private val credentialStore: CredentialStore = WinCredentialStore(),
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
    private var pendingKind: SendKind = SendKind.REFINERY

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
                val token = withContext(Dispatchers.IO) { obtainToken() }
                // Persist (the possibly rotated) refresh token for the next silent send (#648).
                withContext(Dispatchers.IO) {
                    if (token.refreshToken.isNotBlank()) credentialStore.save(token.refreshToken)
                }
                state = SendState.Sending
                val response =
                    withContext(Dispatchers.IO) {
                        val client = ingestClientFor(baseUrl)
                        when (pendingKind) {
                            SendKind.REFINERY ->
                                client.sendRefinery(token.accessToken, pendingJson, pendingLang)
                            SendKind.BLUEPRINT ->
                                client.sendBlueprint(token.accessToken, pendingJson, pendingLang)
                        }
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

    /**
     * Obtains an access token: the "remember me" silent refresh first (no browser, no overlay
     * step), falling back to an interactive device grant when there is no stored token or the
     * refresh is rejected (expired / revoked / reuse-detected). Runs on the calling IO context.
     *
     * @return a token answer whose {@code refreshToken} is the one to re-persist
     * @throws DeviceGrantException when the interactive grant ultimately fails
     */
    private fun obtainToken(): TokenResponse {
        credentialStore.load()?.let { stored ->
            try {
                return deviceGrant.refreshAccessToken(stored)
            } catch (_: DeviceGrantException) {
                credentialStore.clear() // the stored token is dead — drop it and log in afresh
            }
        }
        val device = deviceGrant.requestDeviceCode()
        state = SendState.Authenticating(device.userCode, device.browserUrl())
        browse(device.browserUrl())
        return deviceGrant.pollForToken(device)
    }
}
