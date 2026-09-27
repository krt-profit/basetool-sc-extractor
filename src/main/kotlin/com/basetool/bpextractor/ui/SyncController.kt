package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.model.BlueprintItem
import com.basetool.bpextractor.net.AccountCheckResult
import com.basetool.bpextractor.net.BlueprintSync
import com.basetool.bpextractor.net.Codes
import com.basetool.bpextractor.net.ExchangeClient
import com.basetool.bpextractor.net.ExchangeException
import com.basetool.bpextractor.net.InstallationLabel
import com.basetool.bpextractor.net.SyncReport
import com.basetool.bpextractor.net.accountCheck
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
import com.basetool.bpextractor.net.isCheckableHandle
import java.awt.Desktop
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The state machine of the direct blueprint sync. */
sealed interface SyncState {
    /** Nothing in progress; the overlay is hidden. */
    data object Idle : SyncState

    /**
     * The one-time opt-in: what the sync does, plus the installation label when none was chosen yet.
     *
     * @param label the label as the member is typing it, or `null` when one is already stored
     * @param labelInvalid whether the last confirmation was refused because of the label
     */
    data class Consent(val label: String?, val labelInvalid: Boolean = false) : SyncState

    /**
     * Browser opened; waiting for the user to approve the shown code.
     *
     * @param userCode the code to confirm
     * @param browserUrl the verification URL
     * @param reason why the browser is needed
     */
    data class Authenticating(val userCode: String, val browserUrl: String, val reason: LoginReason) : SyncState

    /** Asking the Basetool whether the log's account is the member's. */
    data object CheckingAccount : SyncState

    /** Pulling, resolving and pushing. */
    data object Syncing : SyncState

    /**
     * The account check says the log's account is not the member's; the member decides.
     *
     * @param handle the log's account
     */
    data class AccountMismatch(val handle: String) : SyncState

    /**
     * Done.
     *
     * @param report what the sync did, `null` when there was nothing to sync
     * @param unknownAccount whether the member's profile names no handle, so the account went unchecked
     */
    data class Done(val report: SyncReport?, val unknownAccount: Boolean = false) : SyncState

    /**
     * A failure, as [SendState.Error] describes it.
     *
     * @param error the failure
     */
    data class Error(val error: SendState.Error) : SyncState
}

/**
 * Drives the opt-in direct blueprint sync: the one-time opt-in, a login with the sync scopes, the account
 * check before the first sync of an account in this session (the handle goes to that check only), then
 * [BlueprintSync]. A Compose state holder whose heavy work runs on [Dispatchers.IO].
 */
class SyncController(
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

    var state by mutableStateOf<SyncState>(SyncState.Idle)
        private set

    private var items: List<BlueprintItem> = emptyList()
    private var handle: String? = null
    private var lang: String = "de"
    private var grant: ExchangeGrant? = null

    /**
     * Entry point from the blueprint summary: stashes the selected account's items and handle, then shows
     * the opt-in when it is due, or starts the sync.
     *
     * @param scope the UI coroutine scope
     * @param items the envelope items of the selected account
     * @param handle the selected account's handle, or `null` when its files name none
     * @param lang the UI locale tag to relay
     * @param defaultLabel the label to offer when the member has not chosen one yet
     */
    fun request(scope: CoroutineScope, items: List<BlueprintItem>, handle: String?, lang: String, defaultLabel: String) {
        this.items = items
        this.handle = handle
        this.lang = lang
        val config = configStore.load()
        if (config.blueprintSyncEnabled && config.installationLabel != null) {
            run(scope)
        } else {
            state = SyncState.Consent(if (config.installationLabel == null) defaultLabel else null)
        }
    }

    /** Takes the label the member is typing on the opt-in step. */
    fun editLabel(label: String) {
        (state as? SyncState.Consent)?.let { if (it.label != null) state = it.copy(label = label, labelInvalid = false) }
    }

    /** Records the opt-in (and the label, when asked for) and starts the sync. */
    fun confirmConsent(scope: CoroutineScope) {
        val consent = state as? SyncState.Consent ?: return
        val label = consent.label?.trim()
        if (label != null && !InstallationLabel.isValid(label)) {
            state = consent.copy(labelInvalid = true)
            return
        }
        val config = configStore.load()
        configStore.save(
            config.copy(
                blueprintSyncEnabled = true,
                consentGiven = true,
                installationLabel = label ?: config.installationLabel,
            ),
        )
        run(scope)
    }

    /** Syncs after the member confirmed that a mismatching account is theirs after all. */
    fun continueDespiteMismatch(scope: CoroutineScope) {
        val mismatch = state as? SyncState.AccountMismatch ?: return
        val current = grant ?: return
        checked += mismatch.handle
        scope.launch { guarded { sync(current, unknownAccount = false) } }
    }

    /** Adds the products the member removed elsewhere after all, after they asked for it. */
    fun addRemovedElsewhere(scope: CoroutineScope) {
        val done = state as? SyncState.Done ?: return
        val report = done.report ?: return
        val current = grant ?: return
        scope.launch {
            guarded {
                state = SyncState.Syncing
                val again =
                    withContext(Dispatchers.IO) {
                        BlueprintSync(client(), current.credentials(), lang).addRemovedElsewhere(report.removedElsewhere)
                    }
                state =
                    done.copy(
                        report = report.copy(
                            added = report.added + again.added,
                            alreadyOwned = report.alreadyOwned + again.alreadyOwned,
                            removedElsewhere = again.removedElsewhere,
                            refused = report.refused + again.refused,
                        ),
                    )
            }
        }
    }

    /** Hides the overlay. */
    fun dismiss() {
        state = SyncState.Idle
    }

    /** Re-opens the verification URL. */
    fun reopenBrowser() {
        (state as? SyncState.Authenticating)?.let { browse(it.browserUrl) }
    }

    private fun run(scope: CoroutineScope) {
        scope.launch {
            guarded {
                val current =
                    withContext(Dispatchers.IO) {
                        login.obtain(DeviceGrantClient.BASE_SCOPES + DeviceGrantClient.SYNC_SCOPES) { device, reason ->
                            state = SyncState.Authenticating(device.userCode, device.browserUrl(), reason)
                            browse(device.browserUrl())
                        }
                    }
                grant = current
                withContext(Dispatchers.IO) {
                    login.remember(current)
                    if (current.fresh) label(current)
                }
                var unknownAccount = false
                val account = handle
                if (account != null && isCheckableHandle(account) && account !in checked) {
                    state = SyncState.CheckingAccount
                    val result = withContext(Dispatchers.IO) { client().accountCheck(current.credentials(), account, lang) }
                    when (result.result) {
                        AccountCheckResult.MISMATCH -> {
                            state = SyncState.AccountMismatch(account)
                            return@guarded
                        }
                        AccountCheckResult.UNKNOWN -> unknownAccount = true
                        else -> checked += account
                    }
                }
                sync(current, unknownAccount)
            }
        }
    }

    private suspend fun sync(current: ExchangeGrant, unknownAccount: Boolean) {
        if (items.isEmpty()) {
            state = SyncState.Done(null, unknownAccount)
            return
        }
        state = SyncState.Syncing
        val report = withContext(Dispatchers.IO) { BlueprintSync(client(), current.credentials(), lang).sync(items) }
        state = SyncState.Done(report, unknownAccount)
    }

    private fun client(): ExchangeClient = exchangeClientFor(configStore.load().ingestBaseUrl)

    private fun label(current: ExchangeGrant) {
        val label = configStore.load().installationLabel ?: return
        try {
            client().labelInstallation(current.credentials(), label, lang)
        } catch (_: ExchangeException) {
        }
    }

    /** Runs [block] and turns every failure into [SyncState.Error], as the send flow does. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: DeviceGrantException) {
            state = SyncState.Error(SendState.Error(e.message ?: "authentication failed", "", e.clockOffsetSeconds))
        } catch (e: NoPersistentKeyException) {
            state = SyncState.Error(SendState.Error(e.message.orEmpty(), NoPersistentKeyException.CODE))
        } catch (e: UnboundTokenException) {
            state = SyncState.Error(SendState.Error(e.message.orEmpty(), UnboundTokenException.CODE))
        } catch (e: ExchangeException) {
            if (e.code == Codes.INSTALLATION_REVOKED || e.code == Codes.CLIENT_REVOKED) {
                withContext(Dispatchers.IO) { login.forget() }
                grant = null
            }
            state = SyncState.Error(SendState.Error(e.message ?: "sync failed", e.code))
        } catch (e: Exception) {
            state = SyncState.Error(SendState.Error(e.message ?: "sync failed"))
        }
    }

    private companion object {
        /**
         * The handles the account check passed in this process, so it runs before the first sync of each
         * account and not before every one. Held in memory only; the handle is never stored.
         */
        val checked: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }
}
