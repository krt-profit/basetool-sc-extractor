package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // The golden set's real artefact: an AR marker "2.1KM" read into the YIELD column.
        val row = StitchedRow("LINDINIUM (ORE)", "505", "2837", "2.1KM", "ON", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_IMPLAUSIBLE, order.goods[0].confidence)
        assertTrue(ExtractWarning.IMPLAUSIBLE_CELL in order.warnings)
    }

    @Test
    fun `a yield exceeding the quantity is a guaranteed digit misread and flags the row`() {
        // Refining removes impurities — the output can never exceed the input. One of the two
        // cells must be a digit misread; the row drops to implausible for the review.
        val row = StitchedRow("BORASE (ORE)", "359", "103", "195", "ON", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_IMPLAUSIBLE, order.goods[0].confidence)
        assertTrue(ExtractWarning.IMPLAUSIBLE_CELL in order.warnings)
    }

    @Test
    fun `a grossly mis-read yield is deterministically repaired from the material rate`() {
        // A14 BORASE: three ON rows of one material set the per-row rate (~0.486). The 751-qty row's
        // yield is mis-read 385 (truth 365). The two siblings give the rate, 385->365 is the unique
        // confusable single-digit edit landing on the implied yield (≤ qty), so the OUTPUT is fixed.
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
    fun `a within-noise yield flip is left as read, never repaired`() {
        // A15 STILERON: the read yield 2720 is ~0.3% off the rate-implied 2728 — inside the rate's
        // noise floor. Arithmetic cannot tell 2720 from 2728, so yieldRepair MUST abstain (no false
        // fix): the recovery for such last-digit flips is a better read, not the rate.
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
        // Auftrag 10 regression: the REFINE toggle shows an orange knob in BOTH states, so the
        // VLM read the OFF rows (BEXALITE 597, LARANITE 510 — yield "--") as ON. In a quoted
        // order the yield column is authoritative: "--" means the row is not refined.
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
        // INERT MATERIALS shows yield 0 with the (disabled) toggle OFF — 0 is ambiguous, the
        // toggle read stands and the row keeps full confidence.
        val row = StitchedRow("INERT MATERIALS", "0", "852", "0", "OFF", "a.png", quotedRead = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertFalse(order.goods[0].refine)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertFalse(ExtractWarning.REFINE_CORRECTED in order.warnings)
    }

    @Test
    fun `dash yields in an un-quoted order do not override the toggle`() {
        // Before GET QUOTE every yield is "--" — the column carries no refine signal there.
        val row = StitchedRow("LINDINIUM (ORE)", "505", "2837", "--", "ON", "a.png", quotedRead = false)

        val order = Validation.validate(stitched(listOf(row), quoted = false))

        assertTrue(order.goods[0].refine)
        assertEquals(Validation.CONFIDENCE_OK, order.goods[0].confidence)
        assertFalse(ExtractWarning.REFINE_CORRECTED in order.warnings)
    }

    @Test
    fun `rows surviving from an un-quoted capture keep their toggle even in a quoted order`() {
        // Auftrag 2 regression: three pre-GET-QUOTE captures + one quoted capture of the same
        // order. Rows visible ONLY in the early captures show "--" legitimately — the yield
        // override must gate on the ROW's read, not on the order-level quoted flag.
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
        // Auftrag 10 shape: with the two mis-read OFF rows excluded, Σ ON matches TO REFINE.
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
        // The button label is a redundant read of the quote state: CONFIRM belongs to a quoted
        // panel, GET QUOTE to an un-quoted one — a contradiction means a header misread.
        val order = Validation.validate(stitched(listOf(cleanRow()), quoted = true, cta = "GET QUOTE"))

        assertTrue(ExtractWarning.CTA_MISMATCH in order.warnings)
    }

    @Test
    fun `a consistent CTA stays silent`() {
        val order = Validation.validate(stitched(listOf(cleanRow()), quoted = true, cta = "CONFIRM"))

        assertFalse(ExtractWarning.CTA_MISMATCH in order.warnings)
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
        // The visible viewport rarely shows the whole list; only EXCEEDING the header is wrong.
        val order = Validation.validate(stitched(listOf(cleanRow(qty = "100")), toRefine = "32295"))

        assertFalse(ExtractWarning.SUM_MISMATCH in order.warnings)
    }

    @Test
    fun `the rounding tolerance of one per row is not a mismatch`() {
        // Verified semantics: displayed integers are rounded, Σ may exceed by up to 1 per row.
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
        // The stitcher saw this row in two captures with disagreeing cells — consensus-unsafe.
        val row = StitchedRow("TUNGSTEN (ORE)", "858", "858", "413", "ON", "a.png", quotedRead = true, contested = true)

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_STITCH_CONTESTED, order.goods[0].confidence)
        assertTrue(ExtractWarning.STITCH_CONTESTED in order.warnings)
    }

    @Test
    fun `a divergent yield-qty ratio within a material flags both rows`() {
        // Auftrag 14 RICCITE: one row read 2877 (ratio ~0.33), its sibling 261 (ratio ~0.45) —
        // the method's per-material rate cannot be both, so the inconsistency is flagged for review.
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
        // One row of a material has no sibling to compare against — never flagged.
        val rows = listOf(
            cleanRow(name = "GOLD (ORE)", qty = "100").let { it.copy(yield_ = "48") },
            StitchedRow("BORASE (ORE)", "359", "751", "365", "ON", "a.png", quotedRead = true),
        )

        val order = Validation.validate(stitched(rows, toRefine = "99999"))

        assertFalse(ExtractWarning.YIELD_RATIO_OUTLIER in order.warnings)
    }

    @Test
    fun `a row that is both contested and a ratio outlier ends at the lower confidence`() {
        // The contested cap is 0.75, the ratio-outlier cap 0.6 — the lower must win (both fire).
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
        // Sum(ON) 2171+950+312 = 3433 exceeds TO REFINE 3333 by 100; 950 -> 850 lands it AND matches
        // the row's yield, so the repair fires, the export carries 850, and the mismatch clears.
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
        // A14 RICCITE: the primary VLM read quality 985; the verify model and OCR both read 965 —
        // a 2-of-3 majority on the column with no arithmetic anchor, so the export is corrected.
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
        // LARANITE: the primary and verify model both read 510 (correct); OCR mis-reads 518 (its
        // documented confusion). 2-of-3 back 510, so OCR is outvoted — no change, no flag.
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
        // Verify model off (no secondary): only the primary (510) and OCR (518) vote and they
        // differ — no majority, so the cell is flagged for review and the primary value stands.
        val primary = listOf(StitchedRow("LARANITE (RAW)", "510", "296", "142", "ON", "a.png", quotedRead = true))
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 518L, qty = 296L, yield_ = 142L))

        val order = Validation.validate(stitched(primary, toRefine = "99999"), crossCheck = null, ocr = ocr)

        assertEquals(510, order.goods[0].quality, "no majority -> the primary value is kept")
        assertTrue(ExtractWarning.OCR_CONTESTED in order.warnings)
        assertEquals(Validation.CONFIDENCE_OCR_CONTESTED, order.goods[0].confidence)
    }

    @Test
    fun `a verify dissent the OCR vote does not join leaves the primary confirmed`() {
        // Primary 858 and OCR 858 agree; the verify model alone read 850. 2-of-3 back 858 (the
        // primary) — confirmed, no correction and no flag.
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
        // INERT MATERIALS carries no quality cell (primary quality null); even with an OCR quality
        // reading the majority vote skips it — the tool never invents a quality the VLM didn't read.
        val primary = listOf(StitchedRow("INERT MATERIALS", null, "759", null, "OFF", "a.png", quotedRead = true))
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 500L, qty = 759L, yield_ = null))

        val order = Validation.validate(stitched(primary, toRefine = "99999"), crossCheck = null, ocr = ocr)

        assertEquals(null, order.goods[0].quality)
        assertFalse(ExtractWarning.OCR_CORRECTED in order.warnings)
        assertFalse(ExtractWarning.OCR_CONTESTED in order.warnings)
    }

    // --- OCR-witnessed yield repair (ocrYieldRepair): the within-noise flips yieldRepair abstains on ---

    private val stileronRows = listOf(
        StitchedRow("STILERON (ORE)", "330", "6062", "2720", "ON", "a.png", quotedRead = true),
        StitchedRow("STILERON (ORE)", "517", "3193", "1437", "ON", "a.png", quotedRead = true),
        StitchedRow("STILERON (ORE)", "874", "426", "191", "ON", "a.png", quotedRead = true),
        StitchedRow("STILERON (ORE)", "947", "386", "174", "ON", "a.png", quotedRead = true),
    )

    @Test
    fun `a within-noise yield flip is corrected from the OCR read confirmed by the material rate`() {
        // A15 STILERON: the 6062-qty row reads yield 2720; the siblings' rate (~0.450) implies ~2728,
        // a within-noise gap yieldRepair abstains on. OCR read 2728 — a confusable 0->8 edit, <= qty,
        // and STRICTLY closer to the rate — so it is adopted.
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2728L))

        val order = Validation.validate(stitched(stileronRows, toRefine = "10067"), ocr = ocr)

        assertEquals(2728L, order.goods.single { it.inputQuantity == 6062L }.outputQuantity, "2720 -> 2728 via OCR + rate")
        assertTrue(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
        assertEquals(Validation.CONFIDENCE_YIELD_REPAIRED, order.goods[0].confidence)
        assertFalse(ExtractWarning.YIELD_REPAIRED in order.warnings, "the arithmetic path correctly abstained")
    }

    @Test
    fun `an OCR yield farther from the rate than the VLM read is rejected`() {
        // Mirror image: the VLM reads the correct 2728 (on the rate); OCR mis-reads 2720 (a confusable
        // edit, but FARTHER from the rate). The strict-closer gate rejects it — a correct cell is never flipped.
        val rows = stileronRows.toMutableList().also { it[0] = it[0].copy(yield_ = "2728") }
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2720L))

        val order = Validation.validate(stitched(rows, toRefine = "10067"), ocr = ocr)

        assertEquals(2728L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `a non-confusable OCR yield difference is not adopted`() {
        // OCR reads 2725 — not a single CONFUSABLE-digit edit of 2720 (5 is outside the 0/6/8/9 family),
        // so it is not treated as the same number mis-read and the VLM value stands.
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2725L))

        val order = Validation.validate(stitched(stileronRows, toRefine = "10067"), ocr = ocr)

        assertEquals(2720L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `an OCR yield correction needs two same-material siblings for the rate`() {
        // Only one sibling -> no trustworthy rate witness -> abstain even though OCR read a confusable edit.
        val rows = stileronRows.take(2)
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2728L))

        val order = Validation.validate(stitched(rows, toRefine = "10067"), ocr = ocr)

        assertEquals(2720L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `an OCR yield that does not land on the material rate is rejected`() {
        // Siblings imply a ~0.40 rate (6062*0.40 ~= 2425); OCR's 2728 is a confusable edit of 2720 but
        // lands far from the rate-implied yield (> the landing band), so it is not trusted.
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
        // BORASE 385 is a GROSS mis-read yieldRepair fixes to 365; an OCR reading for the same row must
        // not double-fire (alreadyFixed) — YIELD_REPAIRED owns it, not YIELD_OCR_REPAIRED.
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
        // A fixable row that is ALSO stitch-contested (capped 0.75): the yield IS corrected, but minOf
        // keeps the confidence at its lower cap — the warning still forces review.
        val rows = stileronRows.toMutableList().also { it[0] = it[0].copy(contested = true) }
        val ocr = mapOf(0 to PanelOcr.RowReading(quality = 330L, qty = 6062L, yield_ = 2728L))

        val order = Validation.validate(stitched(rows, toRefine = "10067"), ocr = ocr)

        assertEquals(2728L, order.goods[0].outputQuantity, "value still corrected")
        assertEquals(Validation.CONFIDENCE_STITCH_CONTESTED, order.goods[0].confidence, "confidence not raised above its cap")
        assertTrue(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }

    @Test
    fun `with no OCR reading the within-noise yield flip stays as read`() {
        // The no-OCR no-op: same STILERON rows, no ocr arg -> ocrYieldRepair is a no-op, 2720 stands.
        val order = Validation.validate(stitched(stileronRows, toRefine = "10067"))

        assertEquals(2720L, order.goods[0].outputQuantity)
        assertFalse(ExtractWarning.YIELD_OCR_REPAIRED in order.warnings)
    }
}
