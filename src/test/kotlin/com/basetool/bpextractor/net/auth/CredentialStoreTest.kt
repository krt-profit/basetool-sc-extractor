package com.basetool.bpextractor.net.auth

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the [CredentialStore] contract via the in-memory fake, plus a Windows-only round-trip through
 * the real [WinCredentialStore] on a unique throwaway target that is deleted afterwards.
 */
class CredentialStoreTest {

    @Test
    fun `fake store round-trips, overwrites and clears`() {
        val store = FakeCredentialStore()
        assertNull(store.load())
        assertFalse(store.exists())

        assertTrue(store.save("token-1"))
        assertEquals("token-1", store.load())
        assertTrue(store.exists())

        store.save("token-2")
        assertEquals("token-2", store.load())

        assertTrue(store.clear())
        assertNull(store.load())
        assertFalse(store.exists())
    }

    @Test
    fun `a credential round-trips the refresh token together with its DPoP key name`() {
        val store = FakeCredentialStore()
        val keys = FakeDpopKeyStore()
        val key = assertNotNull(keys.create())

        store.saveCredential(StoredCredential("RT-1", key.keyName))
        val loaded = assertNotNull(store.loadCredential())

        assertEquals("RT-1", loaded.refreshToken)
        assertEquals(
            key.thumbprint,
            assertNotNull(keys.open(assertNotNull(loaded.dpopKeyName))).thumbprint,
            "the key that comes back has to be the same key",
        )
    }

    @Test
    fun `the stored credential holds no private key material`() {
        val keys = FakeDpopKeyStore()
        val key = assertNotNull(keys.create())
        val blob = StoredCredential.encode(StoredCredential("RT-1", key.keyName))

        val json = Json.parseToJsonElement(blob).jsonObject
        assertEquals(setOf("refreshToken", "dpopKeyName"), json.keys)
        assertEquals(key.keyName, json["dpopKeyName"]?.jsonPrimitive?.content)
        assertTrue(assertNotNull(key.keyName).startsWith(CngDpopKeyStore.KEY_NAME_PREFIX))
        assertEquals(
            setOf("refreshToken", "dpopKeyName"),
            StoredCredential::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `a legacy bare refresh token is still readable and simply carries no key`() {
        val store = FakeCredentialStore("eyJhbGciOiJIUzI1NiJ9.legacy-refresh-token")

        val loaded = assertNotNull(store.loadCredential())

        assertEquals("eyJhbGciOiJIUzI1NiJ9.legacy-refresh-token", loaded.refreshToken)
        assertNull(loaded.dpopKeyName)
    }

    @Test
    fun `a record with an exported key inside is recognised as legacy and never read as usable`() {
        val store = FakeCredentialStore("""{"refreshToken":"RT-OLD","dpopKey":"cHJpdmF0ZQ.cHVibGlj"}""")

        assertNull(store.loadCredential())
        val record = store.loadRecord()
        assertTrue(record is CredentialRecord.LegacyExportedKey, "was $record")
        assertEquals("RT-OLD", record.refreshToken)
        assertEquals("cHJpdmF0ZQ.cHVibGlj", record.exportedKey)
        assertFalse(record.toString().contains("RT-OLD"))
        assertFalse(record.toString().contains("cHJpdmF0ZQ"))
    }

    @Test
    fun `a corrupt record reads as absent rather than as a garbage token`() {
        assertNull(FakeCredentialStore("""{"refreshToken":""}""").loadCredential())
        assertNull(FakeCredentialStore("""{"refreshToken":"RT-1",""").loadCredential())
        assertNull(FakeCredentialStore("""{}""").loadCredential())
    }

    @Test
    fun `windows credential manager round-trips a unicode secret`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            return
        }
        val target = "Basetool SC Extractor TEST ${UUID.randomUUID()}"
        val store = WinCredentialStore(target)
        try {
            assertNull(store.load(), "a fresh target must start empty")

            val secret = "refresh-Öß-${UUID.randomUUID()}"
            assertTrue(store.save(secret), "CredWriteW should succeed")
            assertEquals(secret, store.load(), "CredReadW should return the exact bytes written")
            assertTrue(store.exists())

            assertTrue(store.save("rotated"), "overwrite should succeed")
            assertEquals("rotated", store.load())
        } finally {
            store.clear()
            assertNull(store.load(), "the test entry must be gone after clear")
        }
    }
}
