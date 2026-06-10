package com.basetool.bpextractor.refinery

/**
 * The intermediate result of reading ONE screenshot's SETUP panel — the deterministic reformat of
 * the VLM's freeform markdown answer (Phase 0 frozen read strategy, see
 * `docs/refinery-extractor/PHASE0_FINDINGS.md` §4). All cells stay verbatim strings here; numeric
 * interpretation happens in [PanelValues] so a mis-read like `2.1KM` (HUD-marker bleed-through)
 * surfaces as a validation flag instead of a crash.
 */
data class PanelRead(
    /** Refining method as read, e.g. `FERRON EXCHANGE`; null when the model answered `?`. */
    val method: String?,
    /** `true` when the panel shows yields + CONFIRM; `false` in the GET-QUOTE state. */
    val quoted: Boolean,
    /** Verbatim `IN MANIFEST` header cell; null when absent/unreadable. */
    val inManifest: String?,
    /** Verbatim `TO REFINE` header cell. */
    val toRefine: String?,
    /** Verbatim `TOTAL COST` cell; `--` (un-quoted) is normalized to null. */
    val totalCost: String?,
    /** Verbatim `PROCESSING TIME` cell, e.g. `20H 58M`; null when absent or `--`. */
    val processingTime: String?,
    /** The bottom CTA label (`CONFIRM` / `GET QUOTE`); validation input only. */
    val cta: String?,
    /** The visible table rows, top to bottom. */
    val rows: List<PanelRow>,
)

/** One visible row of the materials table, cells verbatim as transcribed. */
data class PanelRow(
    /** MATERIALS SELECTED cell, verbatim (incl. `(ORE)`/`(RAW)`, UI truncation). */
    val name: String,
    /** QUALITY cell; numeric-looking string or null. */
    val quality: String?,
    /** QTY cell. */
    val qty: String?,
    /** YIELD cell; null when the panel renders `--` (un-quoted). */
    val yield_: String?,
    /** REFINE toggle as read: `ON`/`OFF` (uppercased). */
    val refine: String,
    /** True when the model marked the row as cut off at the viewport edge. */
    val partial: Boolean,
)

/**
 * Parses the VLM's markdown-layout answer into a [PanelRead] — the Kotlin port of the Phase 0
 * spike's `_parse_markdown` (deterministic, ~no heuristics: the prompt pins the exact layout).
 * Returns null only when the answer carries none of the expected anchors at all (a truncated or
 * off-script response); partial answers parse into a partial [PanelRead] and are caught by the
 * validation layer instead.
 */
object MarkdownPanelParser {

    private val KEY_VALUE = Regex("""^([A-Z_]+):\s*(.*)$""", RegexOption.MULTILINE)

    /** Parse [text]; null on a response without any recognizable anchor. */
    fun parse(text: String): PanelRead? {
        val kv = KEY_VALUE.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
        val rows = parseRows(text)
        if (kv.isEmpty() && rows.isEmpty()) {
            return null
        }
        return PanelRead(
            method = kv["METHOD"]?.takeUnless { it == "?" || it.isEmpty() },
            quoted = kv["QUOTED"]?.uppercase() == "YES",
            inManifest = cleanNum(kv["IN_MANIFEST"]),
            toRefine = cleanNum(kv["TO_REFINE"]),
            totalCost = cleanNum(kv["TOTAL_COST"])?.takeUnless { it == "--" },
            processingTime = kv["PROCESSING_TIME"]?.takeUnless { it == "?" || it.isEmpty() || it == "--" },
            cta = kv["CTA"]?.takeUnless { it == "?" || it.isEmpty() },
            rows = rows,
        )
    }

    private fun parseRows(text: String): List<PanelRow> {
        val rows = mutableListOf<PanelRow>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("|")) continue
            // Skip the separator line (only pipes, dashes, colons, spaces).
            if (trimmed.replace("|", "").trim().trimStart('-', ':', ' ').isEmpty()) continue
            val cells = trimmed.trim('|').split("|").map { it.trim() }
            if (cells.size != 5 || cells[0].uppercase() == "MATERIAL") continue
            var name = cells[0]
            val partial = name.endsWith(" PARTIAL")
            if (partial) {
                name = name.removeSuffix(" PARTIAL").trim()
            }
            rows += PanelRow(
                name = name,
                quality = cleanNum(cells[1])?.takeUnless { it == "--" },
                qty = cleanNum(cells[2])?.takeUnless { it == "--" },
                yield_ = cleanNum(cells[3])?.takeUnless { it == "--" },
                refine = cells[4].uppercase(),
                partial = partial,
            )
        }
        return rows
    }

    /**
     * Normalizes a transcribed numeric-ish cell: trims, drops grouping commas and a trailing
     * `.00`, maps the placeholder spellings (`?`, empty, `null`) to null while keeping the
     * meaningful absent-marker `--` (the un-quoted state) as the literal string `--`.
     */
    fun cleanNum(value: String?): String? {
        if (value == null) return null
        val s = value.trim().trimEnd('.')
        if (s == "--") return "--"
        if (s == "?" || s.isEmpty() || s.equals("null", ignoreCase = true)) return null
        return s.replace(",", "").removeSuffix(".00").ifEmpty { null }
    }
}

/**
 * Interprets [PanelRead]'s verbatim cells as numbers — the validation seam of the Phase 0
 * confidence policy (`PHASE0_FINDINGS.md` §6 rule 1): a cell that should be numeric but is not
 * (e.g. `2.1KM` from an AR-marker bleed-through) parses to null, and the caller flags the row.
 */
object PanelValues {

    private val DURATION = Regex("""(?:(\d+)\s*D)?\s*(?:(\d+)\s*H)?\s*(?:(\d+)\s*M)?""", RegexOption.IGNORE_CASE)

    /** Parse an integer-quantity cell (`48928`, `48 928`); null on `--`, null or non-numeric. */
    fun toQuantity(cell: String?): Long? {
        val s = cell?.takeUnless { it == "--" }?.replace(" ", "") ?: return null
        return s.toLongOrNull()
    }

    /** Parse a cost cell (`48928`, `48928.5`, `48928 AUEC`); null on `--`/non-numeric. */
    fun toCost(cell: String?): Double? {
        val s = cell?.takeUnless { it == "--" } ?: return null
        val cleaned = s.uppercase().removeSuffix("AUEC").trim()
        return cleaned.toDoubleOrNull()
    }

    /** Parse a `20H 58M` / `1D 2H 3M` style duration into total minutes; null when unparseable. */
    fun toDurationMinutes(cell: String?): Long? {
        val s = cell?.trim()?.takeUnless { it.isEmpty() || it == "--" } ?: return null
        val m = DURATION.matchEntire(s.uppercase().replace(Regex("\\s+"), " ")) ?: return null
        val (d, h, min) = m.destructured
        if (d.isEmpty() && h.isEmpty() && min.isEmpty()) return null
        return (d.ifEmpty { "0" }.toLong() * 24 + h.ifEmpty { "0" }.toLong()) * 60 + min.ifEmpty { "0" }.toLong()
    }

    /** Parse a QUALITY cell to an int; null on non-numeric (caller flags out-of-range itself). */
    fun toQuality(cell: String?): Int? = cell?.takeUnless { it == "--" }?.toIntOrNull()
}
