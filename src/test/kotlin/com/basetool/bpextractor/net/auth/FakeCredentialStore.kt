package com.basetool.bpextractor.net.auth

/**
 * In-memory [CredentialStore] for tests — no OS vault, no native calls. Mirrors the contract: a
 * single secret, overwritten on save, gone after clear.
 *
 * @param initial the token present before the test (or {@code null} for "not connected")
 */
class FakeCredentialStore(initial: String? = null) : CredentialStore {

    /** The currently stored secret, exposed so tests can assert on rotation. */
    var stored: String? = initial

    /** Records how many times [save] was called (to assert a rotated token was re-persisted). */
    var saveCount: Int = 0

    override fun save(secret: String): Boolean {
        stored = secret
        saveCount++
        return true
    }

    override fun load(): String? = stored

    override fun clear(): Boolean {
        stored = null
        return true
    }
}
