package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.net.auth.CngDpopKeyStore
import com.basetool.bpextractor.net.auth.CredentialRecord
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopKeyStore
import com.basetool.bpextractor.net.auth.WinCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "remember me" account surface: reflects whether a refresh token is stored and runs the
 * "Vom Basetool trennen" disconnect, revoking the token at Keycloak (best-effort) and deleting it and
 * its DPoP key. A Compose state holder whose revoke and delete run off the UI thread.
 */
class AccountController(
    private val credentialStore: CredentialStore = WinCredentialStore(),
    private val deviceGrant: DeviceGrantClient = DeviceGrantClient(),
    private val keyStore: DpopKeyStore = CngDpopKeyStore(),
) {

    /** Whether a stored token exists (the connected/disconnected indicator). */
    var connected by mutableStateOf(credentialStore.exists())
        private set

    /** Whether the disconnect-confirmation modal is showing. */
    var confirming by mutableStateOf(false)
        private set

    /** Whether a disconnect (revoke + delete) is in flight. */
    var working by mutableStateOf(false)
        private set

    /** Re-reads the store so the bar reflects a token a send persisted since composition. */
    fun refresh() {
        connected = credentialStore.exists()
    }

    /** Opens the disconnect confirmation (no native dialog — a KRT scrim modal). */
    fun requestDisconnect() {
        confirming = true
    }

    /** Dismisses the disconnect confirmation without disconnecting. */
    fun cancelDisconnect() {
        confirming = false
    }

    /**
     * Confirms the disconnect: revokes the stored refresh token at Keycloak (best-effort), deletes
     * it locally together with its DPoP key, then updates [connected]. Runs the I/O off the UI
     * thread.
     *
     * @param scope the UI coroutine scope to run the disconnect on
     */
    fun confirmDisconnect(scope: CoroutineScope) {
        confirming = false
        working = true
        scope.launch {
            withContext(Dispatchers.IO) {
                when (val record = credentialStore.loadRecord()) {
                    is CredentialRecord.Current -> {
                        val keyName = record.credential.dpopKeyName
                        deviceGrant.revoke(record.credential.refreshToken, keyName?.let(keyStore::open))
                        credentialStore.clear()
                        keyName?.let(keyStore::delete)
                    }
                    is CredentialRecord.LegacyExportedKey -> {
                        deviceGrant.revoke(record.refreshToken, DpopKey.fromLegacyExport(record.exportedKey))
                        credentialStore.clear()
                    }
                    null -> credentialStore.clear()
                }
            }
            connected = credentialStore.exists()
            working = false
        }
    }
}
