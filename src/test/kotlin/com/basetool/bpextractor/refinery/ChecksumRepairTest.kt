package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [Validation.checksumRepair]: it recovers a known over-read and abstains on a clean order, an
 * ambiguous two-solution decoy, a fix that violates YIELD ≤ QTY, and a sum that does not over-read.
 */
class ChecksumRepairTest {

    private fun good(rowIndex: Int, name: String, quality: Int?, inp: Long?, out: Long?, refine: Boolean = true) =
        RefineryExtractGood(rowIndex, name, quality, inp, out, refine, Validation.CONFIDENCE_OK, "a.png")

    @Test
    fun `recovers the over-read TUNGSTEN qty from the checksum and yield rate`() {
        val goods = listOf(
            good(0, "GOLD (ORE)", 553, 24, 11),
            good(1, "BORASE (ORE)", 359, 751, 365),
            good(2, "BORASE (ORE)", 584, 26, 12),
            good(3, "BORASE (ORE)", 892, 591, 287),
            good(4, "LARANITE (RAW)", 510, 569, 274),
            good(5, "LARANITE (RAW)", 698, 54, 26),
            good(6, "TUNGSTEN (ORE)", 363, 2171, 1055),
            good(7, "TUNGSTEN (ORE)", 958, 950, 413),
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
        val goods = listOf(good(0, "A (ORE)", 300, 800, 400), good(1, "B (ORE)", 400, 850, 420))
        assertTrue(Validation.checksumRepair(goods, 1650).isEmpty())
        assertTrue(Validation.checksumRepair(goods, 9999).isEmpty(), "a short-fall is never repaired")
    }

    @Test
    fun `abstains rather than corrupt a correctly-read row when the true error is unreachable`() {
        val goods = listOf(
            good(0, "GOLD (ORE)", 553, 850, 410),
            good(1, "BORASE (ORE)", 359, 144, 70),
            good(2, "LARANITE (RAW)", 510, 968, 470),
            good(3, "TUNGSTEN (ORE)", 363, 707, 343),
            good(4, "RICCITE (ORE)", 325, 821, 390),
        )
        assertTrue(Validation.checksumRepair(goods, 3431).isEmpty())
    }

    @Test
    fun `abstains without a same-material yield-rate witness`() {
        val goods = listOf(good(0, "A (ORE)", 300, 190, 90), good(1, "B (ORE)", 300, 290, 90))
        assertTrue(Validation.checksumRepair(goods, 390).isEmpty())
    }

    @Test
    fun `abstains when the over-shoot is not reachable by a single confusable digit`() {
        val goods = listOf(good(0, "A (ORE)", 300, 1271, 600), good(1, "B (ORE)", 400, 127, 60))
        assertTrue(Validation.checksumRepair(goods, 700).isEmpty())
    }
}
