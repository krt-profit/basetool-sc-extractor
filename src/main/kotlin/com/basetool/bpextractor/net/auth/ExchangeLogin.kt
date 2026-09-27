package com.basetool.bpextractor.net.auth

import com.basetool.bpextractor.net.ExchangeCredentials

/** Why a login needs the browser instead of a silent refresh; the UI explains the two upgrades. */
enum class LoginReason {
    /** No usable stored login. */
    NONE,

    /** The stored login held an exportable DPoP key and was retired. */
    KEY_UPGRADE,

    /** The stored login lacks scopes this call needs — from before the exchange, or before the sync was on. */
    SCOPE_UPGRADE,
}

/**
 * The exchange cannot be used on this machine: Windows offered no key storage for a persistent,
 * non-exportable DPoP key, and a key held in memory would make every launch a new installation.
 */
class NoPersistentKeyException : Exception("no persistent DPoP key storage is available on this machine") {
    companion object {
        /** The client-side code the UI keys its explanation off. */
        const val CODE = "NO_PERSISTENT_KEY"
    }
}

/**
 * The login's token came back unbound, although the exchange requires a DPoP-bound one (REQ-XCH-006).
 */
class UnboundTokenException : Exception("the sign-in returned a token that is not bound to the key") {
    companion object {
        /** The client-side code the UI keys its explanation off. */
        const val CODE = "TOKEN_NOT_BOUND"
    }
}

/**
 * A login the exchange can use.
 *
 * @param token the token answer
 * @param key the persistent key the token is bound to
 * @param requested the scopes the login asked for
 * @param stored the credential this grant was redeemed from, or `null` for a fresh device login
 */
class ExchangeGrant internal constructor(
    val token: TokenResponse,
    val key: DpopKey,
    val requested: Set<String>,
    val stored: StoredCredential?,
) {
    /** Whether this came from a device login, i.e. a new key and so a new installation. */
    val fresh: Boolean get() = stored == null

    /** The token and key, as the exchange client takes them. */
    fun credentials(): ExchangeCredentials = ExchangeCredentials(token.accessToken, key)
}

/**
 * Obtains and keeps the member's exchange login: a silent refresh of the stored credential when it covers
 * the requested scopes, else a device login bound to a fresh persistent CNG key. Never falls back to a key
 * held in memory (REQ-XCH-027): without key storage it throws [NoPersistentKeyException].
 *
 * @param deviceGrant the Keycloak device-grant client
 * @param credentialStore the one stored credential
 * @param keyStore the persistent key storage
 */
class ExchangeLogin(
    private val deviceGrant: DeviceGrantClient,
    private val credentialStore: CredentialStore,
    private val keyStore: DpopKeyStore,
) {

    /**
     * A login for [scopes]. Runs blocking I/O on the calling thread.
     *
     * @param scopes the scopes the calls need
     * @param onDeviceCode called with the device code and why the browser is needed, before polling
     * @return the grant; [ExchangeGrant.fresh] tells whether a new installation began
     * @throws DeviceGrantException when the device login fails
     * @throws NoPersistentKeyException when no persistent key can be created
     * @throws UnboundTokenException when the server issued an unbound token
     */
    fun obtain(scopes: Set<String>, onDeviceCode: (DeviceCodeResponse, LoginReason) -> Unit): ExchangeGrant {
        var reason = LoginReason.NONE
        when (val record = credentialStore.loadRecord()) {
            is CredentialRecord.LegacyExportedKey -> {
                DpopKey.fromLegacyExport(record.exportedKey)?.let { deviceGrant.revoke(record.refreshToken, it) }
                credentialStore.clear()
                reason = LoginReason.KEY_UPGRADE
            }
            is CredentialRecord.Current -> {
                val stored = record.credential
                if (stored.covers(scopes)) {
                    refresh(stored, scopes)?.let { return it }
                } else {
                    retire(stored)
                    reason = LoginReason.SCOPE_UPGRADE
                }
            }
            null -> Unit
        }
        val key = keyStore.create() ?: throw NoPersistentKeyException()
        try {
            val device = deviceGrant.requestDeviceCode(scopes)
            onDeviceCode(device, reason)
            val token = deviceGrant.pollForToken(device, dpopKey = key)
            if (!token.isDpopBound()) throw UnboundTokenException()
            return ExchangeGrant(token, key, scopes, null)
        } catch (t: Throwable) {
            discard(key)
            throw t
        }
    }

    /**
     * Stores [grant] for the next silent login: the refresh token, the key's name and the requested
     * scopes. A key no record names afterwards is deleted.
     *
     * @param grant the grant to keep
     */
    fun remember(grant: ExchangeGrant) {
        val keyName = grant.key.keyName
        val saved =
            grant.token.refreshToken.isNotBlank() && keyName != null &&
                credentialStore.saveCredential(
                    StoredCredential(grant.token.refreshToken, keyName, grant.requested.sorted().joinToString(" ")),
                )
        if (!saved && keyName != null && grant.stored?.dpopKeyName != keyName) keyStore.delete(keyName)
    }

    /** Drops the stored login and its key, as after the member disconnected this installation or client. */
    fun forget() {
        val stored = credentialStore.loadCredential()
        credentialStore.clear()
        stored?.dpopKeyName?.let(keyStore::delete)
    }

    /**
     * The silent path.
     *
     * @return the grant, or `null` when the credential turned out dead and was dropped
     * @throws DeviceGrantException when the refresh failed for a reason that says nothing about the
     *   credential; it is kept
     */
    private fun refresh(stored: StoredCredential, scopes: Set<String>): ExchangeGrant? {
        val key = stored.dpopKeyName?.let(keyStore::open)
        if (key == null) {
            credentialStore.clear()
            return null
        }
        val token =
            try {
                deviceGrant.refreshAccessToken(stored.refreshToken, key)
            } catch (e: DeviceGrantException) {
                if (e.oauthError !in DeviceGrantException.TOKEN_REJECTED) throw e
                drop(stored)
                return null
            }
        if (!token.isDpopBound()) {
            drop(stored)
            return null
        }
        return ExchangeGrant(token, key, scopes, stored)
    }

    /** Revokes a stored login that cannot serve the call (best-effort), then drops it with its key. */
    private fun retire(stored: StoredCredential) {
        deviceGrant.revoke(stored.refreshToken, stored.dpopKeyName?.let(keyStore::open))
        drop(stored)
    }

    private fun drop(stored: StoredCredential) {
        credentialStore.clear()
        stored.dpopKeyName?.let(keyStore::delete)
    }

    private fun discard(key: DpopKey) {
        key.keyName?.let(keyStore::delete)
    }
}
