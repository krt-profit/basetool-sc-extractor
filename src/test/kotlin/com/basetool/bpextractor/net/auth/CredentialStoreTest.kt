package com.basetool.bpextractor.net.auth

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the [CredentialStore] contract via the in-memory fake, plus a Windows-only round-trip
 * through the real [WinCredentialStore] that exercises the {@code Advapi32.dll} FFM marshalling
 * end-to-end (write → read → delete). The real round-trip uses a unique throwaway target and
 * deletes it afterwards, so it never touches the production "Basetool SC Extractor" entry and
 * leaves the developer's Credential Manager clean. No real basetool credentials are involved.
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
    fun `windows credential manager round-trips a unicode secret`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            return // FFM Advapi32 binding only exists on Windows; skip elsewhere.
        }
        val target = "Basetool SC Extractor TEST ${UUID.randomUUID()}"
        val store = WinCredentialStore(target)
        try {
            assertNull(store.load(), "a fresh target must start empty")

            // A non-ASCII secret proves the UTF-8 blob + UTF-16 target marshalling is correct.
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
