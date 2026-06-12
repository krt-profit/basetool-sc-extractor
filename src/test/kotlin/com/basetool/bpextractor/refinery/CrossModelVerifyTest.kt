package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the cross-model verify merge (PHASE0 addendum 2026-06-12): disagreement flagging, the
 * TO-REFINE checksum arbitration of QTY disagreements, cosmetic name folding and the conservative
 * row-count gate — modelled on the real golden-set disagreements between the 8b and the 4b.
 */
class CrossModelVerifyTest {

    private fun row(
        name: String,
        quality: String? = "618",
        qty: String? = "957",
        yield_: String? = "448",
        refine: String = "ON",
        quotedRead: Boolean = true,
    ) = StitchedRow(name, quality, qty, yield_, refine, "a.png", quotedRead)

    private fun stitch(rows: List<StitchedRow>, toRefine: String? = null, quoted: Boolean = true) =
        StitchResult("DINYX SOLVENTATION", quoted, toRefine, toRefine, "831", "3H 8M", rows)

    @Test
    fun `identical reads produce no flags`() {
        val rows = listOf(row("GOLD (ORE)"), row("INERT MATERIALS", refine = "OFF", yield_ = "0"))

        val outcome = CrossModelVerify.merge(stitch(rows), stitch(rows))

        assertTrue(outcome.comparable)
        assertTrue(outcome.contested.isEmpty())
        assertTrue(outcome.corrected.isEmpty())
        assertEquals(rows, outcome.rows)
    }

    @Test
    fun `a QTY disagreement is corrected when only the verify value lands on TO REFINE`() {
        // The Auftrag 10 reality: primary reads 483, verify reads 403; Σ QTY(ON) with 403 lands
        // exactly on the header (46+403+192+727+276 = 1644 ≈ 1645), with 483 it exceeds it.
        val primary = listOf(
            row("GOLD (ORE)", qty = "46", yield_ = "21"),
            row("BORASE (ORE)", quality = "359", qty = "483", yield_ = "195"),
            row("BORASE (ORE)", quality = "892", qty = "192", yield_ = "93"),
            row("BORASE (ORE)", quality = "903", qty = "727", yield_ = "353"),
            row("TUNGSTEN (ORE)", quality = "858", qty = "276", yield_ = "134"),
        )
        val secondary = primary.map { if (it.qty == "483") it.copy(qty = "403") else it }

        val outcome = CrossModelVerify.merge(stitch(primary, toRefine = "1645"), stitch(secondary, toRefine = "1645"))

        assertEquals("403", outcome.rows[1].qty)
        assertEquals(setOf(1), outcome.corrected)
        assertTrue(outcome.contested.isEmpty())
    }

    @Test
    fun `a QTY disagreement keeps the primary silently when the checksum confirms it`() {
        val primary = listOf(row("GOLD (ORE)", qty = "46", yield_ = "21"))
        val secondary = listOf(row("GOLD (ORE)", qty = "48", yield_ = "21"))

        val outcome = CrossModelVerify.merge(stitch(primary, toRefine = "46"), stitch(secondary, toRefine = "46"))

        assertEquals("46", outcome.rows[0].qty)
        assertTrue(outcome.corrected.isEmpty())
        assertTrue(outcome.contested.isEmpty())
    }

    @Test
    fun `a QTY disagreement on an OFF row stays contested - not checksummable`() {
        val primary = listOf(
            row("GOLD (ORE)", qty = "46", yield_ = "21"),
            row("RICCITE (ORE)", qty = "1850", yield_ = "--", refine = "OFF"),
        )
        val secondary = listOf(primary[0], primary[1].copy(qty = "1859"))

        val outcome = CrossModelVerify.merge(stitch(primary, toRefine = "46"), stitch(secondary, toRefine = "46"))

        assertEquals("1850", outcome.rows[1].qty)
        assertEquals(setOf(1), outcome.contested)
    }

    @Test
    fun `an ambiguous QTY disagreement stays contested`() {
        // Both candidates land inside the tolerance band (difference 1 on a 1-row order).
        val primary = listOf(row("GOLD (ORE)", qty = "46", yield_ = "21"))
        val secondary = listOf(row("GOLD (ORE)", qty = "47", yield_ = "21"))

        val outcome = CrossModelVerify.merge(stitch(primary, toRefine = "46"), stitch(secondary, toRefine = "46"))

        assertEquals("46", outcome.rows[0].qty, "primary stays authoritative")
        assertEquals(setOf(0), outcome.contested)
    }

    @Test
    fun `a QTY disagreement is decided by the yield bound where the checksum cannot reach`() {
        // No usable header arbitration (both sums short of TO REFINE — scrolled viewport), but
        // the models AGREE on the yield: a QTY below its own yield is physically impossible
        // (refining removes impurities), so the candidate satisfying QTY ≥ YIELD wins.
        val primary = listOf(row("GOLD (ORE)", qty = "46", yield_ = "50"))
        val secondary = listOf(row("GOLD (ORE)", qty = "96", yield_ = "50"))

        val outcome = CrossModelVerify.merge(stitch(primary, toRefine = "9999"), stitch(secondary, toRefine = "9999"))

        assertEquals("96", outcome.rows[0].qty)
        assertEquals(setOf(0), outcome.corrected)
        assertTrue(outcome.contested.isEmpty())
    }

    @Test
    fun `a YIELD disagreement is decided by the agreed QTY bound`() {
        // The models agree on QTY 100; a yield of 195 would exceed it — the 95 candidate wins.
        val primary = listOf(row("BORASE (ORE)", qty = "100", yield_ = "195"))
        val secondary = listOf(row("BORASE (ORE)", qty = "100", yield_ = "95"))

        val outcome = CrossModelVerify.merge(stitch(primary), stitch(secondary))

        assertEquals("95", outcome.rows[0].yield_)
        assertEquals(setOf(0), outcome.corrected)
        assertTrue(outcome.contested.isEmpty())
    }

    @Test
    fun `a YIELD disagreement with both candidates inside the bound stays contested`() {
        val primary = listOf(row("BORASE (ORE)", qty = "100", yield_ = "45"))
        val secondary = listOf(row("BORASE (ORE)", qty = "100", yield_ = "48"))

        val outcome = CrossModelVerify.merge(stitch(primary), stitch(secondary))

        assertEquals("45", outcome.rows[0].yield_, "primary stays authoritative")
        assertEquals(setOf(0), outcome.contested)
    }

    @Test
    fun `quality and yield disagreements are contested`() {
        val primary = listOf(row("BEXALITE (RAW)", quality = "302"))
        val secondary = listOf(row("BEXALITE (RAW)", quality = "392"))

        val outcome = CrossModelVerify.merge(stitch(primary), stitch(secondary))

        assertEquals(setOf(0), outcome.contested)
    }

    @Test
    fun `cosmetic name variants do not flag - whitespace and bracket style fold`() {
        val primary = listOf(row("SA VRIL IUM (ORE)"))
        val secondary = listOf(row("SAVRILIUM [ORE]"))

        val outcome = CrossModelVerify.merge(stitch(primary), stitch(secondary))

        assertTrue(outcome.contested.isEmpty())
    }

    @Test
    fun `a one-edit name garble with agreeing numbers is not contested`() {
        // The verify partner's run-to-run artefact (LINDINIMUM): basetool fuzzy-matches names —
        // with quality+qty agreeing this is transcription noise, not a review-worthy mismatch.
        val primary = listOf(row("LINDINIUM (ORE)", quality = "729", qty = "391", yield_ = "183"))
        val secondary = listOf(primary[0].copy(name = "LINDINIMUM (ORE)"))

        val outcome = CrossModelVerify.merge(stitch(primary), stitch(secondary))

        assertTrue(outcome.contested.isEmpty())
        assertEquals("LINDINIUM (ORE)", outcome.rows[0].name, "the primary's spelling stays")
    }

    @Test
    fun `a refine disagreement is contested only where the yield signal does not decide`() {
        // Row 0: quoted with a numeric yield — the yield rule decides, no flag needed.
        // Row 1: un-quoted read — no signal, the toggle disagreement must surface.
        val primary = listOf(
            row("GOLD (ORE)", yield_ = "21", refine = "ON"),
            row("BEXALITE (RAW)", yield_ = "--", refine = "ON", quotedRead = false),
        )
        val secondary = listOf(
            primary[0].copy(refine = "OFF"),
            primary[1].copy(refine = "OFF"),
        )

        val outcome = CrossModelVerify.merge(stitch(primary), stitch(secondary))

        assertEquals(setOf(1), outcome.contested)
    }

    @Test
    fun `mismatching row counts disable the comparison`() {
        val primary = listOf(row("GOLD (ORE)"), row("BORASE (ORE)"))
        val secondary = listOf(row("GOLD (ORE)"))

        val outcome = CrossModelVerify.merge(stitch(primary), stitch(secondary))

        assertFalse(outcome.comparable)
        assertEquals(primary, outcome.rows)
        assertTrue(outcome.contested.isEmpty())
    }
}
