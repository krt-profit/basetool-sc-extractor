package com.basetool.bpextractor.refinery

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the capture-time strategy: file-name timestamps (which survive copies/downloads) beat the
 * file modified time, every observed field naming scheme parses, and implausible digit runs never
 * produce a bogus instant. Name timestamps are zone-less local time — tests pin UTC explicitly.
 */
class CaptureTimeTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `parses the Windows Snipping Tool name scheme`() {
        assertEquals(
            Instant.parse("2026-06-01T21:38:23Z"),
            CaptureTime.fromName("Screenshot 2026-06-01 213823.png", utc),
        )
    }

    @Test
    fun `parses the Star Citizen client name scheme`() {
        assertEquals(
            Instant.parse("2026-06-06T15:50:53Z"),
            CaptureTime.fromName("ScreenShot-2026-06-06_15-50-53-C28.jpg", utc),
        )
    }

    @Test
    fun `honours the zone the name timestamp is interpreted in`() {
        assertEquals(
            Instant.parse("2026-06-01T19:38:23Z"),
            CaptureTime.fromName("Screenshot 2026-06-01 213823.png", ZoneId.of("Europe/Berlin")),
        )
    }

    @Test
    fun `rejects names without a timestamp`() {
        assertNull(CaptureTime.fromName("image.png", utc))
        assertNull(CaptureTime.fromName("image2.png", utc))
        assertNull(CaptureTime.fromName("panel-crop-final.jpg", utc))
    }

    @Test
    fun `rejects out-of-range candidates instead of guessing`() {
        // Month 13, hour 25, minute 71 — shapes that match the regex but no calendar.
        assertNull(CaptureTime.fromName("Screenshot 2026-13-01 213823.png", utc))
        assertNull(CaptureTime.fromName("Screenshot 2026-06-01 253823.png", utc))
        assertNull(CaptureTime.fromName("Screenshot 2026-06-01 217123.png", utc))
        // Time digits embedded in a longer run must not be clipped into a timestamp.
        assertNull(CaptureTime.fromName("export 2026-06-01 2138234567890.png", utc))
    }

    @Test
    fun `name timestamp wins over the file modified time`() {
        // The exact name matters (createTempFile would append digits), so build it by hand.
        val dir = java.nio.file.Files.createTempDirectory("capture-time").toFile().apply { deleteOnExit() }
        val file = File(dir, "Screenshot 2026-06-01 213823.png").apply {
            createNewFile()
            deleteOnExit()
            setLastModified(Instant.parse("2026-06-05T10:53:05Z").toEpochMilli())
        }

        assertEquals(Instant.parse("2026-06-01T21:38:23Z"), CaptureTime.of(file, utc))
    }

    @Test
    fun `falls back to the file modified time when the name carries no timestamp`() {
        val modified = Instant.parse("2026-06-06T21:51:06Z")
        val file = File.createTempFile("image", ".png").apply {
            deleteOnExit()
            setLastModified(modified.toEpochMilli())
        }

        assertEquals(modified, CaptureTime.of(file, utc))
    }

    @Test
    fun `returns null for a vanished file without a name timestamp`() {
        val gone = File(System.getProperty("java.io.tmpdir"), "definitely-missing-image.png")

        assertNull(CaptureTime.of(gone, utc))
    }
}
