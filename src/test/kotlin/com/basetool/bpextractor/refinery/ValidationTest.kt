package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Phase 0 confidence policy (`PHASE0_FINDINGS.md` §6: deterministic validation, the
 * two-pass derivation is rejected) and the one-sided header checksum (§7: TO REFINE = Σ QTY of
 * refine-ON rows ±1 per row; the table is a scrolling viewport, so a shortfall is legal).
 */
class ValidationTest {

    private fun stitched(
        rows: List<StitchedRow>,
        quoted: Boolean = true,
        toRefine: String? = null,
        cta: String? = null,
    ) = StitchResult(
        method = "FERRON EXCHANGE",
        quoted = quoted,
        inManifest = toRefine,
        toRefine = toRefine,
        totalCost = "48928",
        processingTime = "20H 58M",
        rows = rows,
        cta = cta,
    )

    private fun cleanRow(name: String = "LINDINIUM (ORE)", qty: String = "957") =
        StitchedRow(name, "618", qty, "448", "ON", "a.png", quotedRead = true)

    @Test
    fun `a clean row gets the full derived confidence`() {
        val order = Validation.validate(stitched(listOf(cleanRow())))

        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertTrue(order.warnings.isEmpty())
        assertEquals(957L, order.goods[0].inputQuantity)
        assertEquals(448L, order.goods[0].outputQuantity)
        assertEquals(618, order.goods[0].quality)
        assertTrue(order.goods[0].refine)
        assertEquals(48928.0, order.expenses)
        assertEquals(20L * 60 + 58, order.durationMinutes)
    }

    @Test
    fun `a HUD bleed-through cell drops the row to implausible`() {
        val row = StitchedRow("LINDINIUM (ORE)", "505", "2837", "2.1KM", "ON", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_IMPLAUSIBLE, order.goods[0].confidence)
        assertTrue(ExtractWarning.IMPLAUSIBLE_CELL in order.warnings)
    }

    @Test
    fun `a yield exceeding the quantity is a guaranteed digit misread and flags the row`() {
        val row = StitchedRow("BORASE (ORE)", "359", "103", "195", "ON", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_IMPLAUSIBLE, order.goods[0].confidence)
        assertTrue(ExtractWarning.IMPLAUSIBLE_CELL in order.warnings)
    }

    @Test
    fun `a grossly mis-read yield is deterministically repaired from the material rate`() {
        val rows = listOf(
            StitchedRow("BORASE (ORE)", "359", "751", "385", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "584", "26", "12", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "892", "591", "287", "ON", "a.png", quotedRead = true),
        )
        val order = Validation.validate(stitched(rows, toRefine = "1368"))

        val repaired = order.goods.single { it.inputQuantity == 751L }
        assertEquals(365L, repaired.outputQuantity, "385 -> 365 via the material rate witness")
        assertEquals(Validation.CONFIDENCE_YIELD_REPAIRED, repaired.confidence)
        assertTrue(ExtractWarning.YIELD_REPAIRED in order.warnings)
    }

    @Test
    fun `a repair the cell's glyphs contradict is held back and flagged`() {
        val rows = listOf(
            StitchedRow("BORASE (ORE)", "359", "751", "385", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "584", "26", "12", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "892", "591", "287", "ON", "a.png", quotedRead = true),
        )
        val asked = mutableListOf<Triple<Int, Long, Long>>()
        val veto = OcrCrossCheck.GlyphVeto { row, from, to -> asked += Triple(row, from, to); true }

        val order = Validation.validate(stitched(rows, toRefine = "1368"), glyphVeto = veto)

        val kept = order.goods.single { it.inputQuantity == 751L }
        assertEquals(385L, kept.outputQuantity)
        assertEquals(Validation.CONFIDENCE_OCR_CONTESTED, kept.confidence)
        assertTrue(ExtractWarning.GLYPH_VETOED in order.warnings)
        assertFalse(ExtractWarning.YIELD_REPAIRED in order.warnings)
        assertEquals(listOf(Triple(0, 385L, 365L)), asked)
    }

    @Test
    fun `a repair the glyphs do not contradict is applied as before`() {
        val rows = listOf(
            StitchedRow("BORASE (ORE)", "359", "751", "385", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "584", "26", "12", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "892", "591", "287", "ON", "a.png", quotedRead = true),
        )
        val order = Validation.validate(stitched(rows, toRefine = "1368"), glyphVeto = { _, _, _ -> false })

        assertEquals(365L, order.goods.single { it.inputQuantity == 751L }.outputQuantity)
        assertFalse(ExtractWarning.GLYPH_VETOED in order.warnings)
    }

    @Test
    fun `a within-noise yield flip is left as read, never repaired`() {
        val rows = listOf(
            StitchedRow("STILERON (ORE)", "330", "6062", "2720", "ON", "a.png", quotedRead = true),
            StitchedRow("STILERON (ORE)", "517", "3193", "1437", "ON", "a.png", quotedRead = true),
            StitchedRow("STILERON (ORE)", "874", "426", "191", "ON", "a.png", quotedRead = true),
            StitchedRow("STILERON (ORE)", "947", "386", "174", "ON", "a.png", quotedRead = true),
        )
        val order = Validation.validate(stitched(rows, toRefine = "10067"))

        assertEquals(2720L, order.goods.single { it.inputQuantity == 6062L }.outputQuantity)
        assertFalse(ExtractWarning.YIELD_REPAIRED in order.warnings)
    }

    @Test
    fun `an unreadable refine toggle defaults to ON at low confidence`() {
        val row = StitchedRow("LINDINIUM (ORE)", "618", "957", "448", "0N?", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertTrue(order.goods[0].refine, "defaults ON so the backend drafts the row for review")
        assertEquals(Validation.CONFIDENCE_REFINE_UNREADABLE, order.goods[0].confidence)
    }

    @Test
    fun `a quoted dash-yield row is corrected to refine OFF even when the toggle read ON`() {
        val row = StitchedRow("BEXALITE (RAW)", "597", "127", "--", "ON", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertFalse(order.goods[0].refine)
        assertEquals(Validation.CONFIDENCE_REFINE_CORRECTED, order.goods[0].confidence)
        assertTrue(ExtractWarning.REFINE_CORRECTED in order.warnings)
    }

    @Test
    fun `a quoted positive-yield row is corrected to refine ON even when the toggle read OFF`() {
        val row = StitchedRow("TUNGSTEN (ORE)", "858", "276", "134", "OFF", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertTrue(order.goods[0].refine)
        assertEquals(Validation.CONFIDENCE_REFINE_CORRECTED, order.goods[0].confidence)
        assertTrue(ExtractWarning.REFINE_CORRECTED in order.warnings)
    }

    @Test
    fun `a yield of zero does not override the toggle - INERT MATERIALS stays OFF`() {
        val row = StitchedRow("INERT MATERIALS", "0", "852", "0", "OFF", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertFalse(order.goods[0].refine)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertFalse(ExtractWarning.REFINE_CORRECTED in order.warnings)
    }

    @Test
    fun `dash yields in an un-quoted order do not override the toggle`() {
        val row = StitchedRow("LINDINIUM (ORE)", "505", "2837", "--", "ON", "a.png", quotedRead = false)

        val order = Validation.validate(stitched(listOf(row), quoted = false))

        assertTrue(order.goods[0].refine)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertFalse(ExtractWarning.REFINE_CORRECTED in order.warnings)
    }

    @Test
    fun `rows surviving from an un-quoted capture keep their toggle even in a quoted order`() {
        val rows = listOf(
            StitchedRow("BEXALITE (RAW)", "302", "4481", "--", "ON", "early.png", quotedRead = false),
            cleanRow(),
        )

        val order = Validation.validate(stitched(rows))

        assertTrue(order.goods[0].refine)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertFalse(ExtractWarning.REFINE_CORRECTED in order.warnings)
    }

    @Test
    fun `corrected refine-OFF rows do not count toward the checksum`() {
        val rows = listOf(
            StitchedRow("BEXALITE (RAW)", "597", "127", "--", "ON", "a.png", quotedRead = true),
            cleanRow(name = "GOLD (ORE)", qty = "46"),
            StitchedRow("LARANITE (RAW)", "510", "105", "--", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "46"))

        assertFalse(ExtractWarning.SUM_MISMATCH in order.warnings)
        assertEquals(listOf(false, true, false), order.goods.map { it.refine })
    }

    @Test
    fun `a CTA contradicting the quoted state flags the order`() {
        val order = Validation.validate(stitched(listOf(cleanRow()), quoted = true, cta = "GET QUOTE"))

        assertTrue(ExtractWarning.CTA_MISMATCH in order.warnings)
    }

    @Test
    fun `a consistent CTA stays silent`() {
        val order = Validation.validate(stitched(listOf(cleanRow()), quoted = true, cta = "CONFIRM"))

        assertFalse(ExtractWarning.CTA_MISMATCH in order.warnings)
    }

    @Test
    fun `the German client's button labels mean the same as the English ones`() {
        assertEquals(true, Validation.ctaMeansQuoted("BESTÄTIGEN"))
        assertEquals(true, Validation.ctaMeansQuoted("Bestatigen"))
        assertEquals(false, Validation.ctaMeansQuoted("ANGEBOT EINHOLEN"))
        assertEquals(true, Validation.ctaMeansQuoted("CONFIRM"))
        assertEquals(false, Validation.ctaMeansQuoted("GET QUOTE"))
        assertNull(Validation.ctaMeansQuoted("CANCEL"))

        val german = Validation.validate(stitched(listOf(cleanRow()), quoted = true, cta = "ANGEBOT EINHOLEN"))
        assertTrue(ExtractWarning.CTA_MISMATCH in german.warnings)
    }

    @Test
    fun `an order with no quoted read carries the unquoted warning`() {
        val row = StitchedRow("LINDINIUM (ORE)", "505", "2837", null, "ON", "a.png", quotedRead = false)

        val order = Validation.validate(stitched(listOf(row), quoted = false))

        assertTrue(order.isUnquoted())
    }

    @Test
    fun `visible quantities exceeding TO REFINE flag the checksum`() {
        val rows = listOf(cleanRow(qty = "1000"), cleanRow(name = "TUNGSTEN (ORE)", qty = "1000"))

        val order = Validation.validate(stitched(rows, toRefine = "1500"))

        assertTrue(ExtractWarning.SUM_MISMATCH in order.warnings)
    }

    @Test
    fun `a shortfall against TO REFINE is legal - scrolled-out rows are normal`() {
        val order = Validation.validate(stitched(listOf(cleanRow(qty = "100")), toRefine = "32295"))

        assertFalse(ExtractWarning.SUM_MISMATCH in order.warnings)
    }

    @Test
    fun `the rounding tolerance of one per row is not a mismatch`() {
        val rows = listOf(cleanRow(qty = "580"), cleanRow(name = "TUNGSTEN (ORE)", qty = "581"))

        val order = Validation.validate(stitched(rows, toRefine = "1160"))

        assertFalse(ExtractWarning.SUM_MISMATCH in order.warnings)
    }

    @Test
    fun `refine-OFF rows do not count toward the checksum`() {
        val rows = listOf(
            cleanRow(qty = "1000"),
            StitchedRow("INERT MATERIALS", "0", "5449", "0", "OFF", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "1001"))

        assertFalse(ExtractWarning.SUM_MISMATCH in order.warnings)
        assertFalse(order.goods[1].refine)
    }

    @Test
    fun `a single row exceeding TO REFINE flags even when the sum tolerance would allow it`() {
        val order = Validation.validate(stitched(listOf(cleanRow(qty = "1502")), toRefine = "1500"))

        assertTrue(ExtractWarning.SUM_MISMATCH in order.warnings)
    }

    @Test
    fun `layout confidence is the mean row confidence, dampened by a checksum flag`() {
        val rows = listOf(
            cleanRow(qty = "2000"),
            StitchedRow("TUNGSTEN (ORE)", "530", "1000", "2.1KM", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "1500"))

        val mean = (Validation.CONFIDENCE_OK + Validation.CONFIDENCE_IMPLAUSIBLE) / 2
        assertEquals(mean * 0.9, order.layoutConfidence, 1e-9)
    }

    @Test
    fun `cross-check outcomes cap row confidence and add the order warnings`() {
        val rows = listOf(cleanRow(), cleanRow(name = "TUNGSTEN (ORE)"))
        val outcome = CrossModelVerify.Outcome(rows, contested = setOf(0), corrected = setOf(1), comparable = true)

        val order = Validation.validate(stitched(rows), outcome)

        assertEquals(Validation.CONFIDENCE_VERIFY_CONTESTED, order.goods[0].confidence)
        assertEquals(Validation.CONFIDENCE_VERIFY_CORRECTED, order.goods[1].confidence)
        assertTrue(ExtractWarning.VERIFY_MISMATCH in order.warnings)
        assertTrue(ExtractWarning.VERIFY_CORRECTED in order.warnings)
    }

    @Test
    fun `a non-comparable cross-check flags the order but leaves row confidence alone`() {
        val rows = listOf(cleanRow())
        val outcome = CrossModelVerify.Outcome(rows, emptySet(), emptySet(), comparable = false)

        val order = Validation.validate(stitched(rows), outcome)

        assertTrue(ExtractWarning.VERIFY_MISMATCH in order.warnings)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
    }

    @Test
    fun `a contested row is capped for review and flags the order`() {
        val row = StitchedRow("TUNGSTEN (ORE)", "858", "858", "413", "ON", "a.png", quotedRead = true, contested = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_STITCH_CONTESTED, order.goods[0].confidence)
        assertTrue(ExtractWarning.STITCH_CONTESTED in order.warnings)
    }

    @Test
    fun `a divergent yield-qty ratio within a material flags both rows`() {
        val rows = listOf(
            StitchedRow("RICCITE (ORE)", "325", "2877", "935", "ON", "a.png", quotedRead = true),
            StitchedRow("RICCITE (ORE)", "965", "261", "117", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "99999"))

        assertTrue(ExtractWarning.YIELD_RATIO_OUTLIER in order.warnings)
        assertEquals(Validation.CONFIDENCE_YIELD_OUTLIER, order.goods[0].confidence)
        assertEquals(Validation.CONFIDENCE_YIELD_OUTLIER, order.goods[1].confidence)
    }

    @Test
    fun `consistent yield-qty ratios across a material stay silent`() {
        val rows = listOf(
            StitchedRow("TUNGSTEN (ORE)", "363", "2171", "1055", "ON", "a.png", quotedRead = true),
            StitchedRow("TUNGSTEN (ORE)", "902", "312", "151", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "99999"))

        assertFalse(ExtractWarning.YIELD_RATIO_OUTLIER in order.warnings)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[1].confidence)
    }

    @Test
    fun `a single-row material is never a ratio outlier`() {
        val rows = listOf(
            cleanRow(name = "GOLD (ORE)", qty = "100").let { it.copy(yield_ = "48") },
            StitchedRow("BORASE (ORE)", "359", "751", "365", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "99999"))

        assertFalse(ExtractWarning.YIELD_RATIO_OUTLIER in order.warnings)
    }

    @Test
    fun `a row that is both contested and a ratio outlier ends at the lower confidence`() {
        val rows = listOf(
            StitchedRow("RICCITE (ORE)", "325", "2877", "935", "ON", "a.png", quotedRead = true, contested = true),
            StitchedRow("RICCITE (ORE)", "965", "261", "117", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "99999"))

        assertEquals(Validation.CONFIDENCE_YIELD_OUTLIER, order.goods[0].confidence)
        assertTrue(ExtractWarning.STITCH_CONTESTED in order.warnings)
        assertTrue(ExtractWarning.YIELD_RATIO_OUTLIER in order.warnings)
    }

    @Test
    fun `validate applies the checksum repair to the exported qty and clears the mismatch`() {
        val rows = listOf(
            StitchedRow("TUNGSTEN (ORE)", "363", "2171", "1055", "ON", "a.png", quotedRead = true),
            StitchedRow("TUNGSTEN (ORE)", "958", "950", "413", "ON", "a.png", quotedRead = true, contested = true),
            StitchedRow("TUNGSTEN (ORE)", "902", "312", "151", "ON", "a.png", quotedRead = true),
        )
        val order = Validation.validate(stitched(rows, toRefine = "3333"))

        val repaired = order.goods.single { it.quality == 958 }
        assertEquals(850L, repaired.inputQuantity)
        assertEquals(Validation.CONFIDENCE_CHECKSUM_REPAIRED, repaired.confidence)
        assertTrue(ExtractWarning.CHECKSUM_REPAIRED in order.warnings)
        assertFalse(ExtractWarning.SUM_MISMATCH in order.warnings)
    }

    @Test
    fun `row indices follow the stitched order`() {
        val rows = listOf(cleanRow(), cleanRow(name = "TUNGSTEN (ORE)"))

        val order = Validation.validate(stitched(rows))

        assertEquals(listOf(0, 1), order.goods.map { it.rowIndex })
    }

    @Test
    fun `the verify model and OCR outvote a primary quality mis-read`() {
        val primary = listOf(StitchedRow("RICCITE (ORE)", "985", "261", "117", "ON", "a.png", quotedRead = true))
        val secondary = listOf(StitchedRow("RICCITE (ORE)", "965", "261", "117", "ON", "a.png", quotedRead = true))
        val outcome = CrossModelVerify.Outcome(primary, emptySet(), emptySet(), comparable = true, secondaryRows = secondary)
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 965L, qty = 261L, yield_ = 117L))

        val order = Validation.validate(stitched(primary, toRefine = "99999"), outcome, ocr)

        assertEquals(965, order.goods[0].quality, "985 -> 965 by 8b/4b/OCR majority")
        assertTrue(ExtractWarning.OCR_CORRECTED in order.warnings)
        assertEquals(Validation.CONFIDENCE_OCR_CORRECTED, order.goods[0].confidence)
    }

    @Test
    fun `a lone OCR quality error is outvoted and never reaches the export`() {
        val primary = listOf(StitchedRow("LARANITE (RAW)", "510", "569", "274", "ON", "a.png", quotedRead = true))
        val secondary = listOf(StitchedRow("LARANITE (RAW)", "510", "569", "274", "ON", "a.png", quotedRead = true))
        val outcome = CrossModelVerify.Outcome(primary, emptySet(), emptySet(), comparable = true, secondaryRows = secondary)
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 518L, qty = 569L, yield_ = 274L))

        val order = Validation.validate(stitched(primary, toRefine = "99999"), outcome, ocr)

        assertEquals(510, order.goods[0].quality)
        assertFalse(ExtractWarning.OCR_CORRECTED in order.warnings)
        assertFalse(ExtractWarning.OCR_CONTESTED in order.warnings)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
    }

    @Test
    fun `without a verify vote a primary-OCR quality split is flagged, not corrected`() {
        val primary = listOf(StitchedRow("LARANITE (RAW)", "510", "296", "142", "ON", "a.png", quotedRead = true))
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 518L, qty = 296L, yield_ = 142L))

        val order = Validation.validate(stitched(primary, toRefine = "99999"), crossCheck = null, ocr = ocr)

        assertEquals(510, order.goods[0].quality, "no majority -> the primary value is kept")
        assertTrue(ExtractWarning.OCR_CONTESTED in order.warnings)
        assertEquals(Validation.CONFIDENCE_OCR_CONTESTED, order.goods[0].confidence)
    }

    @Test
    fun `a verify dissent the OCR vote does not join leaves the primary confirmed`() {
        val primary = listOf(StitchedRow("TUNGSTEN (ORE)", "858", "850", "413", "ON", "a.png", quotedRead = true))
        val secondary = listOf(StitchedRow("TUNGSTEN (ORE)", "850", "850", "413", "ON", "a.png", quotedRead = true))
        val outcome = CrossModelVerify.Outcome(primary, emptySet(), emptySet(), comparable = true, secondaryRows = secondary)
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 858L, qty = 850L, yield_ = 413L))

        val order = Validation.validate(stitched(primary, toRefine = "99999"), outcome, ocr)

        assertEquals(858, order.goods[0].quality)
        assertFalse(ExtractWarning.OCR_CORRECTED in order.warnings)
        assertFalse(ExtractWarning.OCR_CONTESTED in order.warnings)
    }

    @Test
    fun `a row the VLM read with no quality is left untouched by the OCR vote`() {
        val primary = listOf(StitchedRow("INERT MATERIALS", null, "759", null, "OFF", "a.png", quotedRead = true))
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 500L, qty = 759L, yield_ = null))

        val order = Validation.validate(stitched(primary, toRefine = "99999"), crossCheck = null, ocr = ocr)

        assertEquals(null, order.goods[0].quality)
        assertFalse(ExtractWarning.OCR_CORRECTED in order.warnings)
        assertFalse(ExtractWarning.OCR_CONTESTED in order.warnings)
    }

    private val stileronRows = listOf(
        StitchedRow("STILERON (ORE)", "330", "6062", "2720", "ON", "a.png", quotedRead = true),
        StitchedRow("STILERON (ORE)", "517", "3193", "1437", "ON", "a.png", quotedRead = true),
        StitchedRow("STILERON (ORE)", "874", "426", "191", "ON", "a.png", quotedRead = true),
        StitchedRow("STILERON (ORE)", "947", "386", "174", "ON", "a.png", quotedRead = true),
    )

    @Test
    fun `a within-noise yield flip is corrected from the OCR read confirmed by the material rate`() {
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2728L))

        val order = Validation.validate(stitched(stileronRows, toRefine = "10067"), ocr = ocr)

        assertEquals(2728L, order.goods.single { it.inputQuantity == 6062L }.outputQuantity, "2720 -> 2728 via OCR + rate")
        assertTrue(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
        assertEquals(Validation.CONFIDENCE_YIELD_REPAIRED, order.goods[0].confidence)
        assertFalse(ExtractWarning.YIELD_REPAIRED in order.warnings, "the arithmetic path correctly abstained")
    }

    @Test
    fun `an OCR yield farther from the rate than the VLM read is rejected`() {
        val rows = stileronRows.toMutableList().also { it[0] = it[0].copy(yield_ = "2728") }
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2720L))

        val order = Validation.validate(stitched(rows, toRefine = "10067"), ocr = ocr)

        assertEquals(2728L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `a non-confusable OCR yield difference is not adopted`() {
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2725L))

        val order = Validation.validate(stitched(stileronRows, toRefine = "10067"), ocr = ocr)

        assertEquals(2720L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `an OCR yield correction needs two same-material siblings for the rate`() {
        val rows = stileronRows.take(2)
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2728L))

        val order = Validation.validate(stitched(rows, toRefine = "10067"), ocr = ocr)

        assertEquals(2720L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `an OCR yield that does not land on the material rate is rejected`() {
        val rows = listOf(
            StitchedRow("STILERON (ORE)", "330", "6062", "2720", "ON", "a.png", quotedRead = true),
            StitchedRow("STILERON (ORE)", "517", "1000", "400", "ON", "a.png", quotedRead = true),
            StitchedRow("STILERON (ORE)", "874", "500", "200", "ON", "a.png", quotedRead = true),
        )
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2728L))

        val order = Validation.validate(stitched(rows, toRefine = "99999"), ocr = ocr)

        assertEquals(2720L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `a gross yield already fixed arithmetically is not re-touched by the OCR repair`() {
        val rows = listOf(
            StitchedRow("BORASE (ORE)", "359", "751", "385", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "584", "26", "12", "ON", "a.png", quotedRead = true),
            StitchedRow("BORASE (ORE)", "892", "591", "287", "ON", "a.png", quotedRead = true),
        )
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 359L, qty = 751L, yield_ = 365L))

        val order = Validation.validate(stitched(rows, toRefine = "1368"), ocr = ocr)

        assertEquals(365L, order.goods.single { it.inputQuantity == 751L }.outputQuantity)
        assertTrue(ExtractWarning.YIELD_REPAIRED in order.warnings)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `the OCR yield repair corrects the value but never raises a row already capped lower`() {
        val rows = stileronRows.toMutableList().also { it[0] = it[0].copy(contested = true) }
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2728L))

        val order = Validation.validate(stitched(rows, toRefine = "10067"), ocr = ocr)

        assertEquals(2728L, order.goods[0].outputQuantity, "value still corrected")
        assertEquals(Validation.CONFIDENCE_STITCH_CONTESTED, order.goods[0].confidence, "confidence not raised above its cap")
        assertTrue(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `with no OCR reading the within-noise yield flip stays as read`() {
        val order = Validation.validate(stitched(stileronRows, toRefine = "10067"))

        assertEquals(2720L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `a quality outside 0 to 1000 is flagged implausible`() {
        val row = StitchedRow("LINDINIUM (ORE)", "5100", "957", "448", "ON", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_IMPLAUSIBLE, order.goods[0].confidence)
        assertTrue(ExtractWarning.IMPLAUSIBLE_CELL in order.warnings)
    }

    @Test
    fun `a quality at the top of the range stays plausible`() {
        val row = StitchedRow("STILERON (ORE)", "972", "505", "227", "ON", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertFalse(ExtractWarning.IMPLAUSIBLE_CELL in order.warnings)
    }

    @Test
    fun `a contested TO_REFINE anchor makes the checksum repair abstain and flags the order`() {
        val rows = listOf(
            StitchedRow("TUNGSTEN (ORE)", "363", "2171", "1055", "ON", "a.png", quotedRead = true),
            StitchedRow("TUNGSTEN (ORE)", "958", "950", "413", "ON", "a.png", quotedRead = true),
            StitchedRow("TUNGSTEN (ORE)", "902", "312", "151", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "3333"), toRefineContested = true)

        assertEquals(950L, order.goods.single { it.quality == 958 }.inputQuantity, "no repair against a suspect anchor")
        assertFalse(ExtractWarning.CHECKSUM_REPAIRED in order.warnings)
        assertTrue(ExtractWarning.TO_REFINE_CONTESTED in order.warnings)
        assertFalse(ExtractWarning.SUM_MISMATCH in order.warnings, "sum check suppressed against a contested anchor")
    }

    @Test
    fun `a verify-model TO_REFINE disagreement flags the anchor`() {
        val rows = listOf(cleanRow())
        val outcome = CrossModelVerify.Outcome(
            rows, emptySet(), emptySet(), comparable = true, headerToRefineContested = true,
        )

        val order = Validation.validate(stitched(rows, toRefine = "957"), outcome)

        assertTrue(ExtractWarning.TO_REFINE_CONTESTED in order.warnings)
    }

    @Test
    fun `an OCR-contested refine-OFF qty is flagged for review without changing the value`() {
        val rows = listOf(
            cleanRow(),
            StitchedRow("INERT MATERIALS", "0", "2483", "0", "OFF", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "99999"), qtyOcrContested = setOf(1))

        assertEquals(2483L, order.goods[1].inputQuantity, "flag-only — the value is never changed")
        assertEquals(Validation.CONFIDENCE_OCR_CONTESTED, order.goods[1].confidence)
        assertTrue(ExtractWarning.QTY_OCR_CONTESTED in order.warnings)
        assertFalse(order.goods[1].refine)
    }

    @Test
    fun `an OCR-contested refine-ON qty is not flagged here - the checksum owns ON rows`() {
        val order = Validation.validate(stitched(listOf(cleanRow()), toRefine = "957"), qtyOcrContested = setOf(0))

        assertFalse(ExtractWarning.QTY_OCR_CONTESTED in order.warnings)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
    }
}
