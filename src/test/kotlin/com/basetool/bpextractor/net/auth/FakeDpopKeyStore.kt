package com.basetool.bpextractor.net.auth

import java.util.UUID

/**
 * In-memory [DpopKeyStore] for tests — the non-Windows seam: no CNG, no TPM, no native calls. Keys
 * are JDK keys held in a map under a generated name, which is exactly the contract the send flow
 * relies on (create → name in the record → open by name → delete).
 *
 * @param available `false` simulates a machine where no persistent key can be created
 */
class FakeDpopKeyStore(private val available: Boolean = true) : DpopKeyStore {

    /** The live keys by name — exposed so tests can assert that nothing leaks. */
    val keys: MutableMap<String, DpopKey> = linkedMapOf()

    /** Every name ever passed to [delete], in order. */
    val deleted: MutableList<String> = mutableListOf()

    override fun create(): DpopKey? {
        if (!available) return null
        val name = "${CngDpopKeyStore.KEY_NAME_PREFIX}${UUID.randomUUID()}"
        return DpopKey(JdkDpopSigner.generate(), name).also { keys[name] = it }
    }

    override fun open(keyName: String): DpopKey? = keys[keyName]

    override fun delete(keyName: String): Boolean {
        deleted += keyName
        keys.remove(keyName)
        return true
    }
}
