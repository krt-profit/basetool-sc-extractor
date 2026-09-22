package com.basetool.bpextractor.net.auth

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID

/**
 * Where the persistent DPoP keys live. A key that has to survive the process — because the refresh
 * token bound to it does — is **created inside** a store and never leaves it; the credential record
 * keeps only its [DpopKey.keyName]. Implementations must be **fail-safe** like [CredentialStore]:
 * an unavailable store answers `null`/`false`, never throws, so the send flow falls back to a
 * session key and an interactive login.
 */
interface DpopKeyStore {

    /**
     * Creates a new persistent, non-exportable P-256 key under a fresh unique name.
     *
     * @return the key (with its [DpopKey.keyName] set), or `null` when no key could be created
     */
    fun create(): DpopKey?

    /**
     * Opens a key [create] made earlier.
     *
     * @param keyName the name from the credential record
     * @return the key, or `null` when it no longer exists or the store is unavailable
     */
    fun open(keyName: String): DpopKey?

    /**
     * Deletes a key for good (disconnect, a dead credential, a replaced login).
     *
     * @param keyName the key to delete
     * @return `true` when nothing by that name remains (already-absent counts as success)
     */
    fun delete(keyName: String): Boolean
}

/**
 * [DpopKeyStore] on Windows CNG (`ncrypt.dll`), bound with the JDK Foreign Function &amp; Memory API
 * like [WinCredentialStore] — no native dependency, no JNI, no extra jlink module.
 *
 * <p>**Which provider.** [PLATFORM_PROVIDER] (the TPM via the Microsoft Platform Crypto Provider) is
 * tried first: a TPM key's private half physically never leaves the chip. Where that fails — no TPM,
 * a TPM 1.2 without ECDSA, a policy that blocks it — [SOFTWARE_PROVIDER] (the Microsoft Software Key
 * Storage Provider) is used, with the export policy set to **0** (`NCRYPT_ALLOW_EXPORT_FLAG` not
 * set): Windows then refuses every private-key export, and signing happens inside the key-isolation
 * service rather than in this JVM. That is weaker than the TPM — an attacker already running code as
 * this user on this machine can still ask Windows to sign, and one with administrator rights can
 * patch key isolation — but either way a **copied** Credential Manager record is useless on another
 * machine, which is what `REQ-INGEST-012`'s sender-constraint is for.
 *
 * <p>Keys are per-user (no `NCRYPT_MACHINE_KEY_FLAG`), live in the user profile's key storage — never
 * the install directory (guardrail 2) — and every call that could prompt passes `NCRYPT_SILENT_FLAG`,
 * so no Windows dialog can ever appear. `NCryptDeleteKey` alone gets no flags: the TPM provider
 * rejects the silent flag there (the key then stayed behind — found by the regression test), and
 * deleting never prompts. On any other OS every method degrades to "no key".
 *
 * @param providers the providers to try, in order — injectable so a test can pin the software one
 */
class CngDpopKeyStore(private val providers: List<String> = listOf(PLATFORM_PROVIDER, SOFTWARE_PROVIDER)) :
    DpopKeyStore {

    override fun create(): DpopKey? {
        if (!WINDOWS) return null
        for (provider in providers) {
            val name = "$KEY_NAME_PREFIX${UUID.randomUUID()}"
            val key = try {
                createIn(provider, name)
            } catch (_: Throwable) {
                null
            }
            if (key != null) return key
        }
        return null
    }

    override fun open(keyName: String): DpopKey? {
        if (!WINDOWS) return null
        for (provider in providers) {
            try {
                val publicKey = withKey(provider, keyName) { _, key -> exportPublicKey(key) } ?: continue
                return DpopKey(CngSigner(provider, keyName, publicKey), keyName)
            } catch (_: Throwable) {
                // Not in this provider (or the provider is unusable) — try the next one.
            }
        }
        return null
    }

    override fun delete(keyName: String): Boolean {
        if (!WINDOWS) return true
        var remaining = false
        for (provider in providers) {
            try {
                val deleted = withProvider(provider) { arena, prov ->
                    val out = arena.allocate(ValueLayout.JAVA_LONG)
                    val opened =
                        Nc.openKey.invokeExact(prov, out, wide(arena, keyName), 0, NCRYPT_SILENT_FLAG) as Int
                    if (opened != 0) {
                        true // not in this provider
                    } else {
                        // NCryptDeleteKey frees the handle on success; free it ourselves on failure.
                        // No NCRYPT_SILENT_FLAG: the TPM provider refuses the delete with it.
                        val key = out.get(ValueLayout.JAVA_LONG, 0L)
                        val status = Nc.deleteKey.invokeExact(key, 0) as Int
                        if (status != 0) Nc.freeObject.invokeExact(key) as Int
                        status == 0
                    }
                } ?: true
                if (!deleted) remaining = true
            } catch (_: Throwable) {
                remaining = true
            }
        }
        return !remaining
    }

    /**
     * Whether Windows would hand out the private half of [keyName] — the property this store exists
     * to rule out. Asks for both private-key blob formats CNG knows for ECDSA; a non-exportable key
     * refuses both. Exposed for the regression test, which must be able to prove it on real CNG.
     *
     * @param keyName the key to probe
     * @return `true` if any private export succeeded (a defect), `false` otherwise
     */
    internal fun isPrivateKeyExportable(keyName: String): Boolean {
        if (!WINDOWS) return false
        return providers.any { provider ->
            try {
                withKey(provider, keyName) { arena, key ->
                    listOf(BCRYPT_ECCPRIVATE_BLOB, NCRYPT_PKCS8_PRIVATE_KEY_BLOB).any { type ->
                        // The full two-step export, so a provider that answers the size query but
                        // refuses the data is judged by what it actually hands out.
                        val blobType = wide(arena, type)
                        val size = arena.allocate(ValueLayout.JAVA_INT)
                        val probe = Nc.exportKey.invokeExact(
                            key, 0L, blobType, MemorySegment.NULL, MemorySegment.NULL, 0, size,
                            NCRYPT_SILENT_FLAG,
                        ) as Int
                        val length = size.get(ValueLayout.JAVA_INT, 0L)
                        if (probe != 0 || length <= 0) return@any false
                        val out = arena.allocate(length.toLong())
                        val status = Nc.exportKey.invokeExact(
                            key, 0L, blobType, MemorySegment.NULL, out, length, size, NCRYPT_SILENT_FLAG,
                        ) as Int
                        status == 0
                    }
                } ?: false
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** Creates, locks down and finalizes one key in [provider]; `null` when the provider refuses. */
    private fun createIn(provider: String, name: String): DpopKey? =
        withProvider(provider) { arena, prov ->
            val out = arena.allocate(ValueLayout.JAVA_LONG)
            val created = Nc.createPersistedKey.invokeExact(
                prov, out, wide(arena, ECDSA_P256), wide(arena, name), 0, 0,
            ) as Int
            if (created != 0) return@withProvider null
            val key = out.get(ValueLayout.JAVA_LONG, 0L)
            try {
                // Export policy 0: no NCRYPT_ALLOW_EXPORT_FLAG, no plaintext export — set explicitly
                // rather than relying on the provider default. The TPM provider cannot export a
                // private key at all and may not accept the property; there a refusal is no defect.
                val policy = arena.allocate(ValueLayout.JAVA_INT)
                policy.set(ValueLayout.JAVA_INT, 0L, 0)
                val set = Nc.setProperty.invokeExact(
                    key, wide(arena, NCRYPT_EXPORT_POLICY_PROPERTY), policy, 4, NCRYPT_SILENT_FLAG,
                ) as Int
                val policyOk = set == 0 || provider == PLATFORM_PROVIDER
                val finalized = if (policyOk) Nc.finalizeKey.invokeExact(key, NCRYPT_SILENT_FLAG) as Int else set
                if (finalized != 0) {
                    // Discard the half-made key (NCryptDeleteKey also frees the handle on success).
                    val deleted = Nc.deleteKey.invokeExact(key, 0) as Int
                    if (deleted != 0) Nc.freeObject.invokeExact(key) as Int
                    return@withProvider null
                }
                val publicKey = exportPublicKey(key)
                Nc.freeObject.invokeExact(key) as Int
                DpopKey(CngSigner(provider, name, publicKey), name)
            } catch (t: Throwable) {
                runCatching { Nc.freeObject.invokeExact(key) as Int }
                throw t
            }
        }

    /** The signer for a persisted key: reopens it per signature, so no handle outlives a call. */
    private inner class CngSigner(
        private val provider: String,
        private val keyName: String,
        override val publicKey: ECPublicKey,
    ) : DpopSigner {

        override fun signEs256(input: ByteArray): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256").digest(input)
            return withKey(provider, keyName) { arena, key ->
                val hashSeg = arena.allocate(hash.size.toLong())
                MemorySegment.copy(hash, 0, hashSeg, ValueLayout.JAVA_BYTE, 0L, hash.size)
                val signature = arena.allocate(P256_SIGNATURE_BYTES.toLong())
                val written = arena.allocate(ValueLayout.JAVA_INT)
                val status = Nc.signHash.invokeExact(
                    key, MemorySegment.NULL, hashSeg, hash.size, signature, P256_SIGNATURE_BYTES, written,
                    NCRYPT_SILENT_FLAG,
                ) as Int
                check(status == 0) { "NCryptSignHash failed: 0x${Integer.toHexString(status)}" }
                val length = written.get(ValueLayout.JAVA_INT, 0L)
                check(length == P256_SIGNATURE_BYTES) { "unexpected ECDSA signature length $length" }
                // CNG's ECDSA output is already the raw R‖S that JWS ES256 wants.
                signature.toArray(ValueLayout.JAVA_BYTE)
            } ?: error("the DPoP key $keyName is no longer available")
        }
    }

    /** Opens [provider], runs [block], and always frees the provider handle. */
    private fun <T> withProvider(provider: String, block: (Arena, Long) -> T?): T? =
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.JAVA_LONG)
            val opened = Nc.openStorageProvider.invokeExact(out, wide(arena, provider), 0) as Int
            if (opened != 0) return null
            val prov = out.get(ValueLayout.JAVA_LONG, 0L)
            try {
                block(arena, prov)
            } finally {
                Nc.freeObject.invokeExact(prov) as Int
            }
        }

    /** Opens [keyName] in [provider], runs [block], and always frees the key handle. */
    private fun <T> withKey(provider: String, keyName: String, block: (Arena, Long) -> T?): T? =
        withProvider(provider) { arena, prov ->
            val out = arena.allocate(ValueLayout.JAVA_LONG)
            val opened = Nc.openKey.invokeExact(prov, out, wide(arena, keyName), 0, NCRYPT_SILENT_FLAG) as Int
            if (opened != 0) return@withProvider null
            val key = out.get(ValueLayout.JAVA_LONG, 0L)
            try {
                block(arena, key)
            } finally {
                Nc.freeObject.invokeExact(key) as Int
            }
        }

    /**
     * Reads the public half as a `BCRYPT_ECCPUBLIC_BLOB` — `{ULONG magic; ULONG cbKey;} X Y`, little-
     * endian header, big-endian coordinates — which CNG releases for any key, exportable or not.
     */
    private fun exportPublicKey(key: Long): ECPublicKey =
        Arena.ofConfined().use { arena ->
            val blobType = wide(arena, BCRYPT_ECCPUBLIC_BLOB)
            val size = arena.allocate(ValueLayout.JAVA_INT)
            val probe = Nc.exportKey.invokeExact(
                key, 0L, blobType, MemorySegment.NULL, MemorySegment.NULL, 0, size, NCRYPT_SILENT_FLAG,
            ) as Int
            check(probe == 0) { "NCryptExportKey(size) failed: 0x${Integer.toHexString(probe)}" }
            val length = size.get(ValueLayout.JAVA_INT, 0L)
            val out = arena.allocate(length.toLong())
            val status = Nc.exportKey.invokeExact(
                key, 0L, blobType, MemorySegment.NULL, out, length, size, NCRYPT_SILENT_FLAG,
            ) as Int
            check(status == 0) { "NCryptExportKey failed: 0x${Integer.toHexString(status)}" }
            publicKeyFromBlob(out.toArray(ValueLayout.JAVA_BYTE))
        }

    /** The lazily bound `ncrypt.dll` downcalls — touched only on Windows. */
    private object Nc {
        private val linker = Linker.nativeLinker()
        private val lookup = SymbolLookup.libraryLookup("ncrypt.dll", Arena.global())
        private val I = ValueLayout.JAVA_INT
        private val L = ValueLayout.JAVA_LONG
        private val A = ValueLayout.ADDRESS

        private fun bind(name: String, result: ValueLayout, vararg args: java.lang.foreign.MemoryLayout): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, *args))

        /** `NCryptOpenStorageProvider(NCRYPT_PROV_HANDLE*, LPCWSTR, DWORD)`. */
        val openStorageProvider = bind("NCryptOpenStorageProvider", I, A, A, I)

        /** `NCryptCreatePersistedKey(hProv, NCRYPT_KEY_HANDLE*, LPCWSTR alg, LPCWSTR name, DWORD, DWORD)`. */
        val createPersistedKey = bind("NCryptCreatePersistedKey", I, L, A, A, A, I, I)

        /** `NCryptSetProperty(hObject, LPCWSTR, PBYTE, DWORD, DWORD)`. */
        val setProperty = bind("NCryptSetProperty", I, L, A, A, I, I)

        /** `NCryptFinalizeKey(hKey, DWORD)`. */
        val finalizeKey = bind("NCryptFinalizeKey", I, L, I)

        /** `NCryptOpenKey(hProv, NCRYPT_KEY_HANDLE*, LPCWSTR, DWORD legacySpec, DWORD)`. */
        val openKey = bind("NCryptOpenKey", I, L, A, A, I, I)

        /** `NCryptExportKey(hKey, hExportKey, LPCWSTR, NCryptBufferDesc*, PBYTE, DWORD, DWORD*, DWORD)`. */
        val exportKey = bind("NCryptExportKey", I, L, L, A, A, A, I, A, I)

        /** `NCryptSignHash(hKey, void* padding, PBYTE hash, DWORD, PBYTE sig, DWORD, DWORD*, DWORD)`. */
        val signHash = bind("NCryptSignHash", I, L, A, A, I, A, I, A, I)

        /** `NCryptDeleteKey(hKey, DWORD)` — also frees the handle on success. */
        val deleteKey = bind("NCryptDeleteKey", I, L, I)

        /** `NCryptFreeObject(hObject)`. */
        val freeObject = bind("NCryptFreeObject", I, L)
    }

    companion object {
        /** `MS_PLATFORM_CRYPTO_PROVIDER` — the TPM. */
        const val PLATFORM_PROVIDER = "Microsoft Platform Crypto Provider"

        /** `MS_KEY_STORAGE_PROVIDER` — software keys, protected by the key-isolation service. */
        const val SOFTWARE_PROVIDER = "Microsoft Software Key Storage Provider"

        /** Every key this app creates starts with this; the rest is a random UUID. */
        const val KEY_NAME_PREFIX = "Basetool SC Extractor DPoP "

        private const val ECDSA_P256 = "ECDSA_P256"
        private const val NCRYPT_EXPORT_POLICY_PROPERTY = "Export Policy"
        private const val BCRYPT_ECCPUBLIC_BLOB = "ECCPUBLICBLOB"
        private const val BCRYPT_ECCPRIVATE_BLOB = "ECCPRIVATEBLOB"
        private const val NCRYPT_PKCS8_PRIVATE_KEY_BLOB = "PKCS8_PRIVATEKEY"

        /** `NCRYPT_SILENT_FLAG` — fail rather than ever show a Windows dialog. */
        private const val NCRYPT_SILENT_FLAG = 0x40

        /** `BCRYPT_ECDSA_PUBLIC_P256_MAGIC` ("ECS1"). */
        private const val ECDSA_PUBLIC_P256_MAGIC = 0x31534345

        private const val P256_COORDINATE_BYTES = 32
        private const val P256_SIGNATURE_BYTES = 64

        private val WINDOWS: Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        /**
         * Decodes a `BCRYPT_ECCPUBLIC_BLOB` for P-256 into a JDK public key.
         *
         * @param blob the exported bytes
         * @return the public key
         * @throws IllegalArgumentException when the blob is not a P-256 ECDSA public blob
         */
        internal fun publicKeyFromBlob(blob: ByteArray): ECPublicKey {
            val header = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            require(blob.size == 8 + 2 * P256_COORDINATE_BYTES) { "unexpected public blob size ${blob.size}" }
            require(header.getInt(0) == ECDSA_PUBLIC_P256_MAGIC) { "not an ECDSA P-256 public blob" }
            require(header.getInt(4) == P256_COORDINATE_BYTES) { "unexpected coordinate size" }
            val x = BigInteger(1, blob.copyOfRange(8, 8 + P256_COORDINATE_BYTES))
            val y = BigInteger(1, blob.copyOfRange(8 + P256_COORDINATE_BYTES, blob.size))
            val params =
                AlgorithmParameters.getInstance("EC")
                    .apply { init(ECGenParameterSpec("secp256r1")) }
                    .getParameterSpec(ECParameterSpec::class.java)
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        }

        /** A null-terminated UTF-16LE (`LPCWSTR`) copy of [s] in [arena]. */
        private fun wide(arena: Arena, s: String): MemorySegment {
            val bytes = s.toByteArray(StandardCharsets.UTF_16LE)
            val seg = arena.allocate(bytes.size.toLong() + 2L, 2L)
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
            return seg
        }
    }
}
