package com.basetool.bpextractor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the Windows installer icon: `app.ico` exists and carries the frame sizes Explorer, the taskbar
 * and the installer ask for. The ICO directory is parsed by hand (6-byte header, 16-byte entries).
 */
class AppIconTest {

    /** Sizes generated from `assets/basetool-extractor-icon-512.png`, smallest to largest. */
    private val expectedSizes = setOf(16, 24, 32, 48, 64, 128, 256)

    @Test
    fun appIconShipsOnTheClasspath() {
        val bytes = javaClass.getResourceAsStream("/app.ico")?.readBytes()
        assertNotNull(bytes, "app.ico must ship in src/main/resources — packaging skips it silently when absent")
        assertTrue(bytes.size > 1024, "app.ico looks truncated (${bytes.size} bytes)")
    }

    @Test
    fun appIconCarriesEveryFrameWindowsAsksFor() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/app.ico")?.readBytes())

        assertEquals(0, bytes[0].toInt() and 0xFF, "ICO header byte 0 must be 0")
        assertEquals(0, bytes[1].toInt() and 0xFF, "ICO header byte 1 must be 0")
        assertEquals(1, bytes[2].toInt() and 0xFF, "ICO type must be 1 (icon, not cursor)")
        val count = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)

        val sizes = (0 until count).map { i ->
            val entry = 6 + i * 16
            val width = (bytes[entry].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val height = (bytes[entry + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
            assertEquals(width, height, "frame $i is not square (${width}x$height)")
            width
        }.toSet()

        assertEquals(expectedSizes, sizes, "app.ico frame set drifted")
    }
}
