package com.basetool.bpextractor.refinery

import java.io.File
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Derives the capture instant of a screenshot file — the metadata source for the contract's
 * per-image `capturedAt` field. The basetool uses the LATEST capture of an order as the
 * refinery-order start time (the user captures the SETUP panel right when starting the order).
 *
 * Strategy, first hit wins:
 * 1. A timestamp embedded in the file NAME. It survives copies and downloads — Discord, browsers
 *    and file sync reset the file times but keep the name (verified on field samples whose
 *    modified time was days after the name timestamp). Recognized shapes:
 *    - Windows Snipping Tool: `Screenshot 2026-06-01 213823.png`
 *    - Star Citizen client:   `ScreenShot-2026-06-06_15-50-53-C28.jpg`
 *    - generally: `yyyy-MM-dd` + `HHmmss` (separators `-`/`:`/`.` optional between time parts)
 * 2. The file's last-modified time — right for clipboard pastes (`image.png` is written by the
 *    intake at capture time) and untouched originals.
 *
 * Name timestamps carry no zone and are interpreted in [ZoneId.systemDefault] — the machine that
 * captured the screenshots is the machine running the extractor. EXIF is intentionally NOT read:
 * neither the SC client nor the Snipping Tool writes a capture tag (verified on field samples).
 */
object CaptureTime {

    /**
     * `yyyy-MM-dd` date, one separator, then six time digits with optional separators. The
     * trailing negative lookahead keeps the seconds from eating into longer digit runs.
     */
    private val NAME_TIMESTAMP =
        Regex("""(\d{4})-(\d{2})-(\d{2})[ _T-](\d{2})[-:.]?(\d{2})[-:.]?(\d{2})(?!\d)""")

    /**
     * The capture instant of [file]: the file-name timestamp when one parses (interpreted in
     * [zone]), else the last-modified time, else null (e.g. the file vanished mid-run).
     */
    fun of(file: File, zone: ZoneId = ZoneId.systemDefault()): Instant? =
        fromName(file.name, zone) ?: fromLastModified(file)

    /**
     * Parses a capture timestamp out of a screenshot file [name], or null when no candidate in
     * the name forms a valid calendar date-time. Candidates are tried left to right; the first
     * valid one wins (a hex suffix like `-C28` can never form a second candidate).
     */
    fun fromName(name: String, zone: ZoneId): Instant? =
        NAME_TIMESTAMP.findAll(name)
            .mapNotNull { match -> toInstant(match, zone) }
            .firstOrNull()

    /** Validates one regex candidate into an [Instant]; null when any field is out of range. */
    private fun toInstant(match: MatchResult, zone: ZoneId): Instant? {
        val g = match.groupValues
        return try {
            LocalDateTime.of(
                LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()),
                LocalTime.of(g[4].toInt(), g[5].toInt(), g[6].toInt()),
            ).atZone(zone).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }

    /** The file's last-modified instant; null when the file is gone (`lastModified() == 0`). */
    private fun fromLastModified(file: File): Instant? {
        val millis = file.lastModified()
        return if (millis > 0) Instant.ofEpochMilli(millis) else null
    }
}
