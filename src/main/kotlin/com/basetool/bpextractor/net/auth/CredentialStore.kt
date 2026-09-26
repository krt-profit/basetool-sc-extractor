package com.basetool.bpextractor.net.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * The persisted "remember me" credential: the refresh token and the name of the non-exportable DPoP
 * key it is bound to (REQ-INGEST-012). The key itself stays in [CngDpopKeyStore], so a copied record
 * cannot redeem the token.
 *
 * @param refreshToken the Keycloak refresh token
 * @param dpopKeyName the [DpopKey.keyName] the token is bound to, or `null` for an unbound token
 */
@Serializable
data class StoredCredential(val refreshToken: String, val dpopKeyName: String? = null) {

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Serializes the record for [CredentialStore.save]. The shape has exactly two fields —
         * `refreshToken` and `dpopKeyName` — and no field that could hold key material.
         *
         * @param credential the record to encode
         * @return the opaque blob to hand to the vault — **secret** (it holds the token), never log it
         */
        fun encode(credential: StoredCredential): String =
            JSON.encodeToString(serializer(), credential)

        /**
         * Decodes a vault blob: a non-JSON blob is a bare refresh token without key, and a record with an
         * exported `dpopKey` is [CredentialRecord.LegacyExportedKey].
         *
         * @param blob whatever [CredentialStore.load] returned
         * @return what the vault entry holds, or `null` when it is corrupt
         */
        fun decodeRecord(blob: String): CredentialRecord? {
            if (!blob.startsWith("{")) return CredentialRecord.Current(StoredCredential(blob))
            val raw = try {
                JSON.decodeFromString(RawRecord.serializer(), blob)
            } catch (_: Exception) {
                return null
            }
            if (raw.refreshToken.isBlank()) return null
            if (raw.dpopKey != null) return CredentialRecord.LegacyExportedKey(raw.refreshToken, raw.dpopKey)
            return CredentialRecord.Current(StoredCredential(raw.refreshToken, raw.dpopKeyName))
        }

        /**
         * The current-shape credential in [blob], or `null` for anything else (a legacy
         * exported-key record included — that one is never usable as it stands).
         *
         * @param blob whatever [CredentialStore.load] returned
         * @return the credential, or `null`
         */
        fun decode(blob: String): StoredCredential? =
            (decodeRecord(blob) as? CredentialRecord.Current)?.credential
    }
}

/**
 * Lenient decoding shape covering every vault-entry format; read only by
 * [StoredCredential.decodeRecord], never written.
 */
@Serializable
private data class RawRecord(
    val refreshToken: String = "",
    val dpopKeyName: String? = null,
    /** The exported PKCS#8 and X.509 key pair of a legacy record, read only to revoke and destroy it. */
    val dpopKey: String? = null,
)

/** What the single vault entry turned out to hold. */
sealed interface CredentialRecord {

    /**
     * A usable credential: the current shape, or a pre-DPoP bare refresh token.
     *
     * @param credential the credential
     */
    data class Current(val credential: StoredCredential) : CredentialRecord

    /**
     * A legacy record holding the DPoP private key in exported form; it is revoked and discarded, and
     * the member signs in again. Not a data class, so no generated `toString` prints the secrets.
     *
     * @param refreshToken the refresh token, kept only to revoke it
     * @param exportedKey the exported key pair, kept only to sign that revocation
     */
    class LegacyExportedKey(val refreshToken: String, val exportedKey: String) : CredentialRecord
}

/**
 * The per-user OS secret store for the single "remember me" credential, under one fixed target.
 * Implementations are fail-safe: an unavailable store returns "no credential" instead of throwing.
 * They handle opaque strings; [saveCredential]/[loadCredential] layer [StoredCredential] on top.
 */
interface CredentialStore {

    /**
     * Persists (overwriting) the refresh token.
     *
     * @param secret the refresh token to store
     * @return {@code true} on success, {@code false} if the store is unavailable or the write failed
     */
    fun save(secret: String): Boolean

    /**
     * Reads the stored refresh token.
     *
     * @return the token, or {@code null} when none is stored or the store is unavailable
     */
    fun load(): String?

    /**
     * Removes the stored refresh token (the "Vom Basetool trennen" action).
     *
     * @return {@code true} if nothing remains stored afterwards (already-absent counts as success)
     */
    fun clear(): Boolean

    /**
     * Whether a token is currently stored — drives the connected/disconnected UI state.
     *
     * @return {@code true} when {@link #load()} would return a non-null value
     */
    fun exists(): Boolean = load() != null

    /**
     * Persists the refresh token together with the name of the DPoP key it is bound to.
     *
     * @param credential the record to store
     * @return {@code true} on success, {@code false} if the store is unavailable or the write failed
     */
    fun saveCredential(credential: StoredCredential): Boolean =
        save(StoredCredential.encode(credential))

    /**
     * Reads the stored credential, treating a bare refresh-token entry as an unbound one; a legacy
     * exported-key record is not returned ([loadRecord]).
     *
     * @return the credential, or `null` when nothing usable is stored
     */
    fun loadCredential(): StoredCredential? = load()?.let { StoredCredential.decode(it) }

    /**
     * Reads what the vault entry holds, legacy shapes included, so a caller can migrate a
     * [CredentialRecord.LegacyExportedKey].
     *
     * @return the record, or `null` when nothing usable is stored
     */
    fun loadRecord(): CredentialRecord? = load()?.let { StoredCredential.decodeRecord(it) }
}

/**
 * Windows Credential Manager-backed [CredentialStore]: a single `CRED_TYPE_GENERIC` entry under
 * [target], protected per-user by the OS and bound to `Advapi32.dll` via the JDK Foreign Function &
 * Memory API. On any other OS every method degrades to "no credential".
 */
class WinCredentialStore(private val target: String = DEFAULT_TARGET) : CredentialStore {

    override fun save(secret: String): Boolean {
        if (!WINDOWS) return false
        return try {
            Arena.ofConfined().use { arena ->
                val blob = secret.toByteArray(StandardCharsets.UTF_8)
                val blobSeg = arena.allocate(blob.size.toLong().coerceAtLeast(1L))
                MemorySegment.copy(blob, 0, blobSeg, ValueLayout.JAVA_BYTE, 0L, blob.size)
                val cred = arena.allocate(CRED_SIZE, 8L)
                cred.set(ValueLayout.JAVA_INT, OFF_TYPE, CRED_TYPE_GENERIC)
                cred.set(ValueLayout.ADDRESS, OFF_TARGET_NAME, wide(arena, target))
                cred.set(ValueLayout.JAVA_INT, OFF_BLOB_SIZE, blob.size)
                cred.set(ValueLayout.ADDRESS, OFF_BLOB, blobSeg)
                cred.set(ValueLayout.JAVA_INT, OFF_PERSIST, CRED_PERSIST_LOCAL_MACHINE)
                cred.set(ValueLayout.ADDRESS, OFF_USER_NAME, wide(arena, target))
                (Win.credWrite.invokeExact(cred, 0) as Int) != 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun load(): String? {
        if (!WINDOWS) return null
        return try {
            Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.ADDRESS)
                val ok = Win.credRead.invokeExact(wide(arena, target), CRED_TYPE_GENERIC, 0, out) as Int
                if (ok == 0) return null
                val credPtr = out.get(ValueLayout.ADDRESS, 0L)
                try {
                    val cred = credPtr.reinterpret(CRED_SIZE)
                    val size = cred.get(ValueLayout.JAVA_INT, OFF_BLOB_SIZE)
                    if (size <= 0) return null
                    val blob = cred.get(ValueLayout.ADDRESS, OFF_BLOB).reinterpret(size.toLong())
                    String(blob.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
                } finally {
                    Win.credFree.invokeExact(credPtr)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun clear(): Boolean {
        if (!WINDOWS) return true
        return try {
            Arena.ofConfined().use { arena ->
                val ok = Win.credDelete.invokeExact(wide(arena, target), CRED_TYPE_GENERIC, 0) as Int
                ok != 0 || load() == null
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Allocates a null-terminated UTF-16LE (Windows {@code LPCWSTR}) copy of [s] in [arena]. */
    private fun wide(arena: Arena, s: String): MemorySegment {
        val bytes = s.toByteArray(StandardCharsets.UTF_16LE)
        val seg = arena.allocate(bytes.size.toLong() + 2L, 2L)
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
        return seg
    }

    /**
     * The lazily-bound {@code Advapi32.dll} downcall handles. Initialized on first use, which only
     * happens on Windows (callers gate on [WINDOWS] first), so the library lookup never runs — and
     * never fails — on other operating systems.
     */
    private object Win {
        private val linker = Linker.nativeLinker()
        private val lookup = SymbolLookup.libraryLookup("Advapi32.dll", Arena.global())

        val credWrite =
            linker.downcallHandle(
                lookup.find("CredWriteW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )
        val credRead =
            linker.downcallHandle(
                lookup.find("CredReadW").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                ),
            )
        val credDelete =
            linker.downcallHandle(
                lookup.find("CredDeleteW").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                ),
            )
        val credFree =
            linker.downcallHandle(
                lookup.find("CredFree").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            )
    }

    companion object {
        /** The Credential Manager target name (one entry for the one basetool account). */
        const val DEFAULT_TARGET = "Basetool SC Extractor"

        /** {@code CRED_TYPE_GENERIC} — an app-private credential not tied to a network resource. */
        private const val CRED_TYPE_GENERIC = 1

        /** {@code CRED_PERSIST_LOCAL_MACHINE} — survives logoff, scoped to this machine + user. */
        private const val CRED_PERSIST_LOCAL_MACHINE = 2

        private const val CRED_SIZE = 80L
        private const val OFF_TYPE = 4L
        private const val OFF_TARGET_NAME = 8L
        private const val OFF_BLOB_SIZE = 32L
        private const val OFF_BLOB = 40L
        private const val OFF_PERSIST = 48L
        private const val OFF_USER_NAME = 72L

        /** Whether the host OS is Windows (the only OS with a Credential Manager binding). */
        private val WINDOWS: Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}
