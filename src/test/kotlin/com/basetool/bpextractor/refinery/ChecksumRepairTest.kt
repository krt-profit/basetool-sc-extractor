package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [Validation.checksumRepair] — the deterministic single-digit QTY repair. The tests assert
 * both POWER (it recovers the known Auftrag 14 over-read 950 -> 850) and SAFETY (it abstains on a
 * clean order, on an ambiguous two-solution decoy, when the only fix violates YIELD <= QTY, and when
 * the sum does not over-read at all). A wrong auto-correction is worse than an honest flag, so the
 * safety cases matter as much as the power case.
 */
class ChecksumRepairTest {

    private fun good(rowIndex: Int, name: String, quality: Int?, inp: Long?, out: Long?, refine: Boolean = true) =
        RefineryExtractGood(rowIndex, name, quality, inp, out, refine, Validation.CONFIDENCE_OK, "a.png")

    @Test
    fun `recovers the over-read TUNGSTEN qty from the checksum and yield rate`() {
        // Auftrag 14 shape: the high-quality TUNGSTEN row over-reads QTY 850 -> 950. Sum(ON)=7786
        // exceeds TO REFINE 7691; 950 -> 850 is the UNIQUE confusable edit that lands the sum AND
        // matches the row's yield (413/0.485 ~= 851). 591 -> 501 also lands the sum but is rejected
        // by the yield rate, so the repair stays unique.
        val goods = listOf(
            good(0, "GOLD (ORE)", 553, 24, 11),
            good(1, "BORASE (ORE)", 359, 751, 365),
            good(2, "BORASE (ORE)", 584, 26, 12),
            good(3, "BORASE (ORE)", 892, 591, 287),
            good(4, "LARANITE (RAW)", 510, 569, 274),
            good(5, "LARANITE (RAW)", 698, 54, 26),
            good(6, "TUNGSTEN (ORE)", 363, 2171, 1055),
            good(7, "TUNGSTEN (ORE)", 958, 950, 413), // over-read: truth 850
            good(8, "TUNGSTEN (ORE)", 902, 312, 151),
            good(9, "RICCITE (ORE)", 325, 2077, 935),
            good(10, "RICCITE (ORE)", 965, 261, 117),
            good(11, "BEXALITE (RAW)", 597, 89, null, refine = false),
            good(12, "INERT MATERIALS", 0, 759, 0, refine = false),
        )

        assertEquals(mapOf(7 to 850L), Validation.checksumRepair(goods, 7691))
    }

    @Test
    fun `abstains when the sum does not over-read`() {
        // Sum(ON) within the rounding band of TO REFINE (and a short-fall is legal anyway).
        val goods = listOf(good(0, "A (ORE)", 300, 800, 400), good(1, "B (ORE)", 400, 850, 420))
        assertTrue(Validation.checksumRepair(goods, 1650).isEmpty())
        assertTrue(Validation.checksumRepair(goods, 9999).isEmpty(), "a short-fall is never repaired")
    }

    @Test
    fun `abstains when two different edits both land the sum (ambiguous)`() {
        // Two different-material single rows (no yield-rate tie-break): 190 -> 100 and 290 -> 200
        // each reduce the sum by 90 and land TO REFINE 390. Ambiguous -> must abstain.
        val goods = listOf(good(0, "A (ORE)", 300, 190, 90), good(1, "B (ORE)", 300, 290, 90))
        assertTrue(Validation.checksumRepair(goods, 390).isEmpty())
    }

    @Test
    fun `abstains when the only checksum fix would push qty below yield`() {
        // 980 -> 900 lands TO REFINE 1100 but 900 < yield 950 (refining cannot output more than
        // input), so the only candidate is rejected on physics and the repair abstains.
        val goods = listOf(good(0, "A (ORE)", 300, 980, 950), good(1, "B (ORE)", 400, 200, 90))
        assertTrue(Validation.checksumRepair(goods, 1100).isEmpty())
    }

    @Test
    fun `abstains when the over-shoot is not reachable by a single confusable digit`() {
        // Sum over-reads but no single confusable-digit edit of any ON qty lands the band
        // (the digits in play are 1/2/7, which the SC font does not confuse) -> abstain, don't guess.
        val goods = listOf(good(0, "A (ORE)", 300, 1271, 600), good(1, "B (ORE)", 400, 127, 60))
        assertTrue(Validation.checksumRepair(goods, 700).isEmpty())
    }
}
