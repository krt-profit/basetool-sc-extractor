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
    ) = StitchResult(
        method = "FERRON EXCHANGE",
        quoted = quoted,
        inManifest = toRefine,
        toRefine = toRefine,
        totalCost = "48928",
        processingTime = "20H 58M",
        rows = rows,
    )

    private fun cleanRow(name: String = "LINDINIUM (ORE)", qty: String = "957") =
        StitchedRow(name, "618", qty, "448", "ON", "a.png")

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
        val row = StitchedRow("LINDINIUM (ORE)", "505", "2837", "2.1KM", "ON", "a.png")

        val order = Validation.validate(stitched(listOf(row)))

        assertEquals(Validation.CONFIDENCE_IMPLAUSIBLE, order.goods[0].confidence)
        assertTrue(ExtractWarning.IMPLAUSIBLE_CELL in order.warnings)
    }

    @Test
    fun `an unreadable refine toggle defaults to ON at low confidence`() {
        val row = StitchedRow("LINDINIUM (ORE)", "618", "957", "448", "0N?", "a.png")

        val order = Validation.validate(stitched(listOf(row)))

        assertTrue(order.goods[0].refine, "defaults ON so the backend drafts the row for review")
        assertEquals(Validation.CONFIDENCE_REFINE_UNREADABLE, order.goods[0].confidence)
    }

    @Test
    fun `an order with no quoted read carries the unquoted warning`() {
        val row = StitchedRow("LINDINIUM (ORE)", "505", "2837", null, "ON", "a.png")

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
            StitchedRow("INERT MATERIALS", "0", "5449", "0", "OFF", "a.png"),
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
            StitchedRow("TUNGSTEN (ORE)", "530", "1000", "2.1KM", "ON", "a.png"),
        )

        val order = Validation.validate(stitched(rows, toRefine = "1500"))

        val mean = (Validation.CONFIDENCE_OK + Validation.CONFIDENCE_IMPLAUSIBLE) / 2
        assertEquals(mean * 0.9, order.layoutConfidence, 1e-9)
    }

    @Test
    fun `row indices follow the stitched order`() {
        val rows = listOf(cleanRow(), cleanRow(name = "TUNGSTEN (ORE)"))

        val order = Validation.validate(stitched(rows))

        assertEquals(listOf(0, 1), order.goods.map { it.rowIndex })
    }
}
