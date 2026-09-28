package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.model.BlueprintItem
import com.basetool.bpextractor.net.AccountCheckResult
import com.basetool.bpextractor.net.BlueprintSync
import com.basetool.bpextractor.net.ExchangeClient
import com.basetool.bpextractor.net.ExchangeCredentials
import com.basetool.bpextractor.net.ExchangeSession
import com.basetool.bpextractor.net.InstallationLabel
import com.basetool.bpextractor.net.SyncReport
import com.basetool.bpextractor.net.accountCheck
import com.basetool.bpextractor.net.auth.CngDpopKeyStore
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceCodeResponse
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DpopKeyStore
import com.basetool.bpextractor.net.auth.ExchangeLogin
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.net.auth.WinCredentialStore
import com.basetool.bpextractor.net.isCheckableHandle
import java.awt.Desktop
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
     * Browser opened; waiting for the member to type the shown code.
     *
     * @param userCode the code to type
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
     * The member's profile names no RSI handle, so the account check cannot tell; the member confirms.
     *
     * @param handle the log's account
     */
    data class AccountUnconfirmed(val handle: String) : SyncState

    /**
     * Done.
     *
     * @param report what the sync did, `null` when there was nothing to sync
     */
    data class Done(val report: SyncReport?) : SyncState

    /**
     * A failure, as [SendState.Error] describes it.
     *
     * @param error the failure
     */
    data class Error(val error: SendState.Error) : SyncState
}

/**
 * Drives the opt-in direct blueprint sync: the one-time opt-in, a login with the sync scopes, the account
 * check once per account and session (the handle goes to that check only), then [BlueprintSync]. Every
 * action — also the member's answer to a question — signs in afresh through [ExchangeSession]. A Compose
 * state holder whose heavy work runs on [Dispatchers.IO].
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

    /**
     * Syncs after the member confirmed that the account is theirs, following a mismatch or an account
     * the profile does not name. The confirmation holds for the session.
     */
    fun continueWithAccount(scope: CoroutineScope) {
        val account =
            when (val current = state) {
                is SyncState.AccountMismatch -> current.handle
                is SyncState.AccountUnconfirmed -> current.handle
                else -> return
            }
        confirmed += account
        run(scope)
    }

    /** Adds the products the member removed elsewhere after all, after they asked for it. */
    fun addRemovedElsewhere(scope: CoroutineScope) {
        val done = state as? SyncState.Done ?: return
        val report = done.report ?: return
        scope.launch {
            try {
                val client = client()
                val again =
                    withContext(Dispatchers.IO) {
                        session(client).run(SCOPES, REQUIRED, ::onDeviceCode) { credentials ->
                            state = SyncState.Syncing
                            BlueprintSync(client, credentials, lang).addRemovedElsewhere(report.removedElsewhere)
                        }
                    }
                state =
                    SyncState.Done(
                        report.copy(
                            added = report.added + again.added,
                            alreadyOwned = report.alreadyOwned + again.alreadyOwned,
                            removedElsewhere = again.removedElsewhere,
                            refused = report.refused + again.refused,
                        ),
                    )
            } catch (e: Exception) {
                state = SyncState.Error(failureOf(e))
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
            try {
                val client = client()
                state =
                    withContext(Dispatchers.IO) {
                        session(client).run(SCOPES, REQUIRED, ::onDeviceCode) { credentials -> syncWith(client, credentials) }
                    }
            } catch (e: Exception) {
                state = SyncState.Error(failureOf(e))
            }
        }
    }

    /** The account check where it is due, then the sync; answers with the state to show. */
    private fun syncWith(client: ExchangeClient, credentials: ExchangeCredentials): SyncState {
        val account = handle
        if (account != null && isCheckableHandle(account) && account !in confirmed) {
            val result =
                answers[account] ?: run {
                    state = SyncState.CheckingAccount
                    client.accountCheck(credentials, account, lang).result.also { answers[account] = it }
                }
            when (result) {
                AccountCheckResult.MATCH -> confirmed += account
                AccountCheckResult.MISMATCH -> return SyncState.AccountMismatch(account)
                else -> return SyncState.AccountUnconfirmed(account)
            }
        }
        if (items.isEmpty()) return SyncState.Done(null)
        state = SyncState.Syncing
        return SyncState.Done(BlueprintSync(client, credentials, lang).sync(items))
    }

    private fun onDeviceCode(device: DeviceCodeResponse, reason: LoginReason) {
        state = SyncState.Authenticating(device.userCode, device.browserUrl(), reason)
        browse(device.browserUrl())
    }

    private fun session(client: ExchangeClient): ExchangeSession =
        ExchangeSession(login, client, lang, configStore.load().installationLabel)

    private fun client(): ExchangeClient = exchangeClientFor(configStore.load().ingestBaseUrl)

    private companion object {
        /** What the sync signs in with: the base scopes and the blueprint read and write. */
        val SCOPES: Set<String> = DeviceGrantClient.BASE_SCOPES + DeviceGrantClient.SYNC_SCOPES

        /** What the service document must grant before a sync. */
        val REQUIRED: Set<String> = setOf(DeviceGrantClient.CONNECT_SCOPE) + DeviceGrantClient.SYNC_SCOPES

        /**
         * The account check's answer per handle for this process, so each handle is checked once per
         * session. Held in memory only; the handle is never stored.
         */
        val answers: MutableMap<String, String> = ConcurrentHashMap()

        /** The handles the check matched or the member confirmed in this process. */
        val confirmed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }
}
