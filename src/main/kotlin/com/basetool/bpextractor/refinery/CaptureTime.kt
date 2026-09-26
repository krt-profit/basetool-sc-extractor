package com.basetool.bpextractor.refinery

import java.io.File
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Derives a screenshot's capture instant for the contract's per-image `capturedAt`.
 *
 * First hit wins:
 * 1. a timestamp in the file name (`yyyy-MM-dd` plus `HHmmss`, e.g. `Screenshot 2026-06-01 213823.png`
 *    or `ScreenShot-2026-06-06_15-50-53-C28.jpg`), interpreted in [ZoneId.systemDefault];
 * 2. the file's last-modified time.
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
