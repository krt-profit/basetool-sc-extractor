package com.basetool.bpextractor.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Round-trips the non-secret app config through a throwaway dir (never the install dir). */
class AppConfigStoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("bpext-config-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun returnsDefaultsWhenMissing() {
        val config = AppConfigStore(dir).load()
        assertEquals(AppConfig.DEFAULT_INGEST_BASE_URL, config.ingestBaseUrl)
        assertFalse(config.consentGiven)
    }

    @Test
    fun savesAndReloads() {
        val store = AppConfigStore(dir)
        store.save(AppConfig(ingestBaseUrl = "https://ingest.example", consentGiven = true))
        assertTrue(File(dir, "config.json").isFile)

        val reloaded = AppConfigStore(dir).load()
        assertEquals("https://ingest.example", reloaded.ingestBaseUrl)
        assertTrue(reloaded.consentGiven)
    }

    @Test
    fun fallsBackToDefaultsOnCorruptFile() {
        File(dir, "config.json").writeText("{ not valid json")
        val config = AppConfigStore(dir).load()
        assertEquals(AppConfig.DEFAULT_INGEST_BASE_URL, config.ingestBaseUrl)
    }
}
