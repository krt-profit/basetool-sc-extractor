package com.basetool.bpextractor.net

import com.basetool.bpextractor.BuildInfo
import com.basetool.bpextractor.net.auth.DeviceCodeResponse
import com.basetool.bpextractor.net.auth.ExchangeGrant
import com.basetool.bpextractor.net.auth.ExchangeLogin
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.update.UpdateChecker

/**
 * One exchange action on the member's behalf, with the rules every action shares: a usable login,
 * the label for a new installation, the service document's checks, one refresh and retry after
 * `UNAUTHENTICATED`, and a disconnect that drops the stored login.
 *
 * @param login the member's exchange login
 * @param client the exchange client
 * @param acceptLanguage the UI locale to relay
 * @param label the member's installation label, or `null` when none was chosen
 * @param clientVersion this release's version, compared with the registry's minimum
 */
class ExchangeSession(
    private val login: ExchangeLogin,
    private val client: ExchangeClient,
    private val acceptLanguage: String,
    private val label: String?,
    private val clientVersion: String = BuildInfo.VERSION,
) {

    /**
     * Runs [action] with a login for [scopes]. Before it, the service document must grant every one of
     * [required] and accept this version. An `UNAUTHENTICATED` anywhere is answered once with a
     * refreshed login and a second run; a disconnect drops the stored login and its key.
     *
     * @param scopes the scopes to sign in with
     * @param required the capabilities the action needs
     * @param onDeviceCode called when the browser is needed, with why
     * @param action the calls to make
     * @return what [action] returned
     * @throws ExchangeException on a refusal; `CLIENT_VERSION_UNSUPPORTED` or `SCOPE_MISSING` when the
     *   service document rules the action out
     */
    fun <T> run(
        scopes: Set<String>,
        required: Set<String>,
        onDeviceCode: (DeviceCodeResponse, LoginReason) -> Unit,
        action: (ExchangeCredentials) -> T,
    ): T =
        try {
            try {
                attempt(scopes, required, onDeviceCode, action)
            } catch (e: ExchangeException) {
                if (e.code != Codes.UNAUTHENTICATED) throw e
                attempt(scopes, required, onDeviceCode, action)
            }
        } catch (e: ExchangeException) {
            if (e.code == Codes.INSTALLATION_REVOKED || e.code == Codes.CLIENT_REVOKED) login.forget()
            throw e
        }

    private fun <T> attempt(
        scopes: Set<String>,
        required: Set<String>,
        onDeviceCode: (DeviceCodeResponse, LoginReason) -> Unit,
        action: (ExchangeCredentials) -> T,
    ): T {
        val grant = login.obtain(scopes, onDeviceCode)
        login.remember(grant)
        val credentials = grant.credentials()
        if (grant.fresh) label(grant)
        check(client.serviceDocument(credentials, acceptLanguage), required)
        return action(credentials)
    }

    /**
     * Labels a new installation with the member's label. Best-effort: an installation without a label
     * still works, and the calls that follow report any refusal that matters.
     */
    private fun label(grant: ExchangeGrant) {
        if (label == null) return
        try {
            client.labelInstallation(grant.credentials(), label, acceptLanguage)
        } catch (_: ExchangeException) {
        }
    }

    private fun check(document: ServiceDocument, required: Set<String>) {
        val minimum = document.minClientVersion
        if (minimum != null && UpdateChecker.isNewerVersion(minimum, clientVersion)) {
            throw ExchangeException("this version is $clientVersion, the Basetool needs $minimum", 403, Codes.CLIENT_VERSION_UNSUPPORTED)
        }
        val missing = required - document.capabilities.toSet()
        if (missing.isNotEmpty()) {
            throw ExchangeException("not granted: ${missing.sorted().joinToString(", ")}", 403, Codes.SCOPE_MISSING)
        }
    }
}
