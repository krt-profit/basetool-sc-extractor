package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the stitch/dedupe rules (master plan §9 Phase 3, corrected): full-triple row identity,
 * never merging co-visible rows, quoted-over-unquoted preference, scroll-overlap ordering and
 * partial-row dropping — modelled on the golden-set realities (Auftrag 1/2).
 */
class StitcherTest {

    private fun row(
        name: String,
        quality: String?,
        qty: String?,
        yield_: String? = null,
        refine: String = "ON",
        partial: Boolean = false,
    ) = PanelRow(name, quality, qty, yield_, refine, partial)

    private fun panel(
        rows: List<PanelRow>,
        quoted: Boolean = true,
        method: String? = "FERRON EXCHANGE",
        inManifest: String? = "32295",
        toRefine: String? = "32295",
        totalCost: String? = if (quoted) "48928" else null,
        processingTime: String? = if (quoted) "20H 58M" else null,
    ) = PanelRead(method, quoted, inManifest, toRefine, totalCost, processingTime, if (quoted) "CONFIRM" else "GET QUOTE", rows)

    @Test
    fun `chains two scrolled captures by their overlap and keeps on-screen order`() {
        // Capture B (taken FIRST alphabetically) shows rows 3..6, capture A shows rows 1..4 —
        // input order must not matter, only the overlap does.
        val top = ImageRead(
            "b_lower.png",
            panel(
                listOf(
                    row("C (ORE)", "300", "30", "3"),
                    row("D (ORE)", "400", "40", "4"),
                    row("E (ORE)", "500", "50", "5"),
                    row("F (ORE)", "600", "60", "6"),
                ),
            ),
        )
        val bottom = ImageRead(
            "a_upper.png",
            panel(
                listOf(
                    row("A (ORE)", "100", "10", "1"),
                    row("B (ORE)", "200", "20", "2"),
                    row("C (ORE)", "300", "30", "3"),
                    row("D (ORE)", "400", "40", "4"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(top, bottom))

        assertEquals(listOf("A (ORE)", "B (ORE)", "C (ORE)", "D (ORE)", "E (ORE)", "F (ORE)"), result.rows.map { it.name })
    }

    @Test
    fun `duplicate materials at different qualities never collapse`() {
        // Auftrag 1 reality: LINDINIUM at four different qualities is one order's normal state.
        val capture = ImageRead(
            "a.png",
            panel(
                listOf(
                    row("LINDINIUM (ORE)", "385", "100", "40"),
                    row("LINDINIUM (ORE)", "585", "200", "90"),
                    row("LINDINIUM (ORE)", "618", "957", "448"),
                    row("LINDINIUM (ORE)", "729", "300", "150"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(capture))

        assertEquals(4, result.rows.size)
    }

    @Test
    fun `identical co-visible rows in ONE capture stay separate`() {
        val capture = ImageRead(
            "a.png",
            panel(
                listOf(
                    row("ALUMINUM (ORE)", "500", "100", "40"),
                    row("ALUMINUM (ORE)", "500", "100", "40"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(capture))

        assertEquals(2, result.rows.size)
    }

    @Test
    fun `the quoted variant of a row beats the un-quoted duplicate`() {
        // Auftrag 2 reality: the same order captured before AND after GET QUOTE.
        val unquoted = ImageRead(
            "before_quote.png",
            panel(
                listOf(
                    row("LINDINIUM (ORE)", "505", "2837", yield_ = null),
                    row("TUNGSTEN (ORE)", "530", "1104", yield_ = null),
                ),
                quoted = false,
            ),
        )
        val quoted = ImageRead(
            "after_quote.png",
            panel(
                listOf(
                    row("LINDINIUM (ORE)", "505", "2837", yield_ = "1300"),
                    row("TUNGSTEN (ORE)", "530", "1104", yield_ = "560"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(unquoted, quoted))

        assertEquals(2, result.rows.size)
        assertEquals("1300", result.rows[0].yield_)
        assertEquals("after_quote.png", result.rows[0].sourceImage)
        assertTrue(result.quoted, "any quoted read makes the stitched order quoted")
        assertEquals("48928", result.totalCost, "cost comes from the quoted read")
    }

    @Test
    fun `partial rows are dropped before stitching`() {
        val capture = ImageRead(
            "a.png",
            panel(
                listOf(
                    row("A (ORE)", "100", "10", "1"),
                    row("B (ORE)", "200", "20", "2", partial = true),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(capture))

        assertEquals(listOf("A (ORE)"), result.rows.map { it.name })
    }

    @Test
    fun `captures without any overlap are appended in input order`() {
        val first = ImageRead("one.png", panel(listOf(row("A (ORE)", "100", "10", "1"))))
        val second = ImageRead("two.png", panel(listOf(row("Z (ORE)", "900", "90", "9"))))

        val result = Stitcher.stitch(listOf(first, second))

        assertEquals(listOf("A (ORE)", "Z (ORE)"), result.rows.map { it.name })
    }

    @Test
    fun `an identical re-capture folds away instead of duplicating rows`() {
        val rows = listOf(
            row("A (ORE)", "100", "10", "1"),
            row("B (ORE)", "200", "20", "2"),
            row("C (ORE)", "300", "30", "3"),
        )
        val first = ImageRead("one.png", panel(rows))
        val again = ImageRead("two.png", panel(rows))

        val result = Stitcher.stitch(listOf(first, again))

        assertEquals(3, result.rows.size)
    }

    @Test
    fun `a capture contained in the middle of a longer one folds in place`() {
        val long = ImageRead(
            "long.png",
            panel(
                listOf(
                    row("A (ORE)", "100", "10", "1"),
                    row("B (ORE)", "200", "20", "2"),
                    row("C (ORE)", "300", "30", "3"),
                    row("D (ORE)", "400", "40", "4"),
                ),
            ),
        )
        val middle = ImageRead(
            "middle.png",
            panel(
                listOf(
                    row("B (ORE)", "200", "20", "2"),
                    row("C (ORE)", "300", "30", "3"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(long, middle))

        assertEquals(listOf("A (ORE)", "B (ORE)", "C (ORE)", "D (ORE)"), result.rows.map { it.name })
    }

    @Test
    fun `header fields fall back to the un-quoted read when no quoted one exists`() {
        val capture = ImageRead(
            "a.png",
            panel(listOf(row("A (ORE)", "100", "10", yield_ = null)), quoted = false),
        )

        val result = Stitcher.stitch(listOf(capture))

        assertEquals("FERRON EXCHANGE", result.method)
        assertEquals("32295", result.inManifest)
        assertEquals(false, result.quoted)
    }
}
