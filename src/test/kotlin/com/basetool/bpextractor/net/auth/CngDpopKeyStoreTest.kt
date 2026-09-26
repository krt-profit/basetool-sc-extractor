package com.basetool.bpextractor.net.auth

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The non-exportable DPoP key storage (SIB-SEC-04). The blob decoder runs everywhere; the rest talks
 * to real Windows CNG and is skipped on other operating systems. Every key a test creates carries a
 * random name and is deleted in `finally`, so nothing is left in the developer's key storage and no
 * production key is ever touched.
 */
class CngDpopKeyStoreTest {

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Verifies [proof]'s ES256 signature under [publicKey] — what the gateway and Keycloak do. */
    private fun verifies(proof: String, publicKey: java.security.PublicKey): Boolean {
        val (header, claims, signature) = proof.split('.')
        return Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(publicKey)
            update("$header.$claims".toByteArray(Charsets.US_ASCII))
            verify(Base64.getUrlDecoder().decode(signature))
        }
    }

    /** Creates a key in [store], runs [block], and always deletes the key again. */
    private fun withKey(store: CngDpopKeyStore, block: (DpopKey) -> Unit) {
        val key = assertNotNull(store.create(), "CNG must be able to create a key on Windows")
        try {
            block(key)
        } finally {
            assertTrue(store.delete(assertNotNull(key.keyName)))
        }
    }

    @Test
    fun `a software-provider key signs valid proofs, reopens by name and refuses every private export`() {
        if (!windows) return
        val store = CngDpopKeyStore(listOf(CngDpopKeyStore.SOFTWARE_PROVIDER))
        withKey(store) { key ->
            val name = assertNotNull(key.keyName)
            assertTrue(name.startsWith(CngDpopKeyStore.KEY_NAME_PREFIX))
            assertTrue(key.persistent)

            val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract", accessToken = "AT")
            val reopened = assertNotNull(store.open(name), "the name in the record must open the key")
            assertEquals(key.thumbprint, reopened.thumbprint, "reopened by name it is the same key")
            val publicKey = publicKeyOf(key)
            assertTrue(verifies(proof, publicKey))
            assertTrue(verifies(reopened.proof("POST", "https://ingest.example/v1/x"), publicKey))

            assertFalse(store.isPrivateKeyExportable(name), "the DPoP key must not be exportable")
        }
    }

    @Test
    fun `the default provider order creates a working key, TPM when present, software otherwise`() {
        if (!windows) return
        val store = CngDpopKeyStore()
        withKey(store) { key ->
            val name = assertNotNull(key.keyName)
            assertTrue(verifies(key.proof("POST", "https://sso.example/token"), publicKeyOf(key)))
            assertFalse(store.isPrivateKeyExportable(name))
            assertNotNull(store.open(name))
        }
    }

    @Test
    fun `a deleted key is gone and deleting it again still succeeds`() {
        if (!windows) return
        val store = CngDpopKeyStore(listOf(CngDpopKeyStore.SOFTWARE_PROVIDER))
        val key = assertNotNull(store.create())
        val name = assertNotNull(key.keyName)
        assertTrue(store.delete(name))
        assertNull(store.open(name), "a deleted key must not open")
        assertTrue(store.delete(name), "already absent counts as deleted")
        assertFailsWith<IllegalStateException> { key.proof("POST", "https://x/y") }
    }

    @Test
    fun `an unknown key name opens as nothing`() {
        if (!windows) return
        assertNull(CngDpopKeyStore().open("${CngDpopKeyStore.KEY_NAME_PREFIX}does-not-exist"))
    }

    @Test
    fun `a CNG public blob decodes to the same P-256 point`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val public = pair.public as ECPublicKey
        val blob = ByteArray(8 + 64)
        blob[0] = 0x45; blob[1] = 0x43; blob[2] = 0x53; blob[3] = 0x31
        blob[4] = 32
        fixed(public.w.affineX).copyInto(blob, 8)
        fixed(public.w.affineY).copyInto(blob, 40)

        val decoded = CngDpopKeyStore.publicKeyFromBlob(blob)

        assertContentEquals(public.encoded, decoded.encoded)
        assertFailsWith<IllegalArgumentException> { CngDpopKeyStore.publicKeyFromBlob(blob.copyOf(40)) }
        val wrongMagic = blob.copyOf().also { it[3] = 0x32 }
        assertFailsWith<IllegalArgumentException> { CngDpopKeyStore.publicKeyFromBlob(wrongMagic) }
    }

    /** The key's public half, rebuilt from its JWK — the same thing a server does. */
    private fun publicKeyOf(key: DpopKey): java.security.PublicKey {
        val jwk = key.publicJwk
        fun coordinate(name: String) =
            Base64.getUrlDecoder().decode(jwk[name].toString().trim('"'))
        val blob = ByteArray(8 + 64)
        blob[0] = 0x45; blob[1] = 0x43; blob[2] = 0x53; blob[3] = 0x31
        blob[4] = 32
        coordinate("x").copyInto(blob, 8)
        coordinate("y").copyInto(blob, 40)
        return CngDpopKeyStore.publicKeyFromBlob(blob)
    }

    /** A coordinate as exactly 32 big-endian bytes. */
    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) raw.copyInto(out, 0, raw.size - 32, raw.size) else raw.copyInto(out, 32 - raw.size)
        return out
    }
}
