package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the deterministic markdown→[PanelRead] reformat (the Kotlin port of the Phase 0 spike's
 * `_parse_markdown`) against synthetic answers modelled on the golden-set shapes.
 */
class MarkdownPanelParserTest {

    private val quotedAnswer = """
        METHOD: FERRON EXCHANGE
        QUOTED: YES
        IN_MANIFEST: 32295
        TO_REFINE: 32,295
        TOTAL_COST: 48928.00
        PROCESSING_TIME: 20H 58M
        CTA: CONFIRM

        | MATERIAL | QUALITY | QTY | YIELD | REFINE |
        |---|---|---|---|---|
        | LINDINIUM (ORE) | 618 | 957 | 448 | ON |
        | TUNGSTEN (ORE) | 530 | 1431 | 695 | ON |
        | INERT MATERIALS | 0 | 5449 | 0 | OFF |
        | TORITE (ORE) PARTIAL | 385 | 76 | 30 | ON |
    """.trimIndent()

    @Test
    fun `parses a quoted panel with header fields and rows`() {
        val read = MarkdownPanelParser.parse(quotedAnswer)!!

        assertEquals("FERRON EXCHANGE", read.method)
        assertTrue(read.quoted)
        assertEquals("32295", read.inManifest)
        // Grouping commas are dropped by the cell normalizer.
        assertEquals("32295", read.toRefine)
        // A trailing ".00" is display formatting, not data.
        assertEquals("48928", read.totalCost)
        assertEquals("20H 58M", read.processingTime)
        assertEquals("CONFIRM", read.cta)
        assertEquals(4, read.rows.size)
        assertEquals("LINDINIUM (ORE)", read.rows[0].name)
        assertEquals("618", read.rows[0].quality)
        assertEquals("957", read.rows[0].qty)
        assertEquals("448", read.rows[0].yield_)
        assertEquals("ON", read.rows[0].refine)
        assertFalse(read.rows[0].partial)
    }

    @Test
    fun `marks edge-cut rows as partial and strips the marker from the name`() {
        val read = MarkdownPanelParser.parse(quotedAnswer)!!

        assertTrue(read.rows[3].partial)
        assertEquals("TORITE (ORE)", read.rows[3].name)
    }

    @Test
    fun `parses the un-quoted GET-QUOTE state with double-dash yields`() {
        val answer = """
            METHOD: FERRON EXCHANGE
            QUOTED: NO
            IN_MANIFEST: 19440
            TO_REFINE: 19440
            TOTAL_COST: --
            PROCESSING_TIME: --
            CTA: GET QUOTE

            | MATERIAL | QUALITY | QTY | YIELD | REFINE |
            |---|---|---|---|---|
            | LINDINIUM (ORE) | 505 | 2837 | -- | ON |
        """.trimIndent()

        val read = MarkdownPanelParser.parse(answer)!!

        assertFalse(read.quoted)
        // "--" cost/time mean "not quoted yet" — normalized to absent.
        assertNull(read.totalCost)
        assertNull(read.processingTime)
        // A "--" yield cell stays verbatim: validation distinguishes the marker (un-quoted or
        // refine-OFF) from an unreadable cell (null).
        assertEquals("--", read.rows[0].yield_)
    }

    @Test
    fun `question-mark placeholders become nulls`() {
        val answer = """
            METHOD: ?
            QUOTED: YES
            IN_MANIFEST: ?
            TO_REFINE: 1161
            TOTAL_COST: 980
            PROCESSING_TIME: ?
            CTA: ?

            | MATERIAL | QUALITY | QTY | YIELD | REFINE |
            |---|---|---|---|---|
            | SAVRILIUM (ORE) | 927 | 150 | 120 | ON |
        """.trimIndent()

        val read = MarkdownPanelParser.parse(answer)!!

        assertNull(read.method)
        assertNull(read.inManifest)
        assertNull(read.processingTime)
        assertNull(read.cta)
    }

    @Test
    fun `an off-script answer without any anchor yields null`() {
        assertNull(MarkdownPanelParser.parse("I cannot read this image, sorry."))
    }

    @Test
    fun `a literal PARTIAL ghost row is dropped`() {
        // The 4b's real failure shape on Auftrag 7/8: the edge-cut marker emitted as a row of
        // its own instead of a name suffix — no material is named PARTIAL, drop it.
        val answer = quotedAnswer + "\n| PARTIAL | ? | ? | ? | ? |"

        val read = MarkdownPanelParser.parse(answer)!!

        assertEquals(4, read.rows.size)
        assertTrue(read.rows.none { it.name.uppercase() == "PARTIAL" })
    }

    @Test
    fun `table header and separator lines are not rows`() {
        val read = MarkdownPanelParser.parse(quotedAnswer)!!

        assertTrue(read.rows.none { it.name.uppercase() == "MATERIAL" })
        assertTrue(read.rows.none { it.name.contains("---") })
    }
}

/** Pins the numeric interpretation seam ([PanelValues]) incl. the HUD bleed-through case. */
class PanelValuesTest {

    @Test
    fun `quantities parse plain integers and reject HUD bleed-through`() {
        assertEquals(48928L, PanelValues.toQuantity("48928"))
        // The Phase 0 golden set's real overlay artefact: an AR distance marker read as a cell.
        assertNull(PanelValues.toQuantity("2.1KM"))
        assertNull(PanelValues.toQuantity("--"))
        assertNull(PanelValues.toQuantity(null))
    }

    @Test
    fun `costs parse decimals and tolerate a aUEC suffix`() {
        assertEquals(48928.0, PanelValues.toCost("48928"))
        assertEquals(48928.5, PanelValues.toCost("48928.5"))
        assertEquals(48928.0, PanelValues.toCost("48928 aUEC"))
        assertNull(PanelValues.toCost("--"))
        assertNull(PanelValues.toCost("? AUEC"))
    }

    @Test
    fun `durations parse hour-minute and day-hour-minute forms`() {
        assertEquals(20L * 60 + 58, PanelValues.toDurationMinutes("20H 58M"))
        assertEquals(58L, PanelValues.toDurationMinutes("58m"))
        assertEquals(26L * 60 + 3, PanelValues.toDurationMinutes("1D 2H 3M"))
        assertNull(PanelValues.toDurationMinutes("--"))
        assertNull(PanelValues.toDurationMinutes("soon"))
    }

    @Test
    fun `quality parses plain integers only`() {
        assertEquals(618, PanelValues.toQuality("618"))
        assertEquals(0, PanelValues.toQuality("0"))
        assertNull(PanelValues.toQuality("2.1KM"))
    }
}
