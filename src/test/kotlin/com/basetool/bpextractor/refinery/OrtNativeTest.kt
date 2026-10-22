package com.basetool.bpextractor.refinery

import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [OrtNative]: the bundled-library path and the sweep of ONNX Runtime's unpacked copies. */
class OrtNativeTest {

    private val dirs = mutableListOf<File>()
    private val previousPath: String? = System.getProperty(OrtNative.NATIVE_PATH_PROPERTY)

    private fun tempDir(): File = Files.createTempDirectory("ort-native-test").toFile().also { dirs += it }

    @AfterTest
    fun restore() {
        dirs.forEach { it.deleteRecursively() }
        if (previousPath == null) {
            System.clearProperty(OrtNative.NATIVE_PATH_PROPERTY)
        } else {
            System.setProperty(OrtNative.NATIVE_PATH_PROPERTY, previousPath)
        }
    }

    @Test
    fun `the resources folder with every library becomes the library path`() {
        System.clearProperty(OrtNative.NATIVE_PATH_PROPERTY)
        val resources = tempDir()
        OrtNative.LIBRARIES.forEach { File(resources, it).writeText("dll") }

        assertEquals(resources.absoluteFile, OrtNative.useBundledLibraries(resources.path)?.absoluteFile)
        assertEquals(resources.absolutePath, System.getProperty(OrtNative.NATIVE_PATH_PROPERTY))
    }

    @Test
    fun `a dev run without the libraries or a path already set changes nothing`() {
        System.clearProperty(OrtNative.NATIVE_PATH_PROPERTY)
        val partial = tempDir().also { File(it, OrtNative.LIBRARIES.first()).writeText("dll") }

        assertNull(OrtNative.useBundledLibraries(partial.path))
        assertNull(OrtNative.useBundledLibraries(null))
        assertNull(System.getProperty(OrtNative.NATIVE_PATH_PROPERTY))

        System.setProperty(OrtNative.NATIVE_PATH_PROPERTY, "C:\\elsewhere")
        val full = tempDir().also { d -> OrtNative.LIBRARIES.forEach { File(d, it).writeText("dll") } }
        assertNull(OrtNative.useBundledLibraries(full.path))
        assertEquals("C:\\elsewhere", System.getProperty(OrtNative.NATIVE_PATH_PROPERTY))
    }

    @Test
    fun `only stale unpacked copies are swept`() {
        val root = tempDir()
        val now = Instant.parse("2026-10-22T12:00:00Z")
        fun folder(name: String, age: Duration) = File(root, name).apply {
            mkdirs()
            File(this, "onnxruntime.dll").writeText("dll")
            setLastModified(now.minus(age).toEpochMilli())
        }
        val stale = folder("onnxruntime-java1234", Duration.ofDays(3))
        val fresh = folder("onnxruntime-java5678", Duration.ofHours(2))
        val foreign = folder("someone-else", Duration.ofDays(30))

        assertEquals(1, OrtNative.sweepStaleExtractions(root, now))
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(foreign.exists())
    }
}
