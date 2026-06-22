package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `a QTY disagreement in the overlap zone neither duplicates rows nor keeps the edge read`() {
        // Auftrag 10 reality: the same physical LARANITE row is the EDGE row of capture 1
        // (mis-read 185) and a mid-table row of capture 2 (clean 105). The exact overlap fails
        // on the digit, so without the loose pass every overlap row would export twice; the
        // surviving QTY must come from the capture that saw the row away from its edges.
        val first = ImageRead(
            "1_upper.png",
            panel(
                listOf(
                    row("BEXALITE (RAW)", "597", "127", "--"),
                    row("BORASE (ORE)", "892", "192", "93"),
                    row("BORASE (ORE)", "903", "727", "353"),
                    row("LARANITE (RAW)", "510", "185", "--"), // edge row, mis-read
                ),
            ),
        )
        val second = ImageRead(
            "2_lower.png",
            panel(
                listOf(
                    row("BORASE (ORE)", "892", "192", "93"),
                    row("BORASE (ORE)", "903", "727", "353"),
                    row("LARANITE (RAW)", "510", "105", "--"), // mid-table, clean
                    row("TUNGSTEN (ORE)", "858", "276", "134"),
                    row("INERT MATERIALS", "0", "852", "0", refine = "OFF"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(first, second))

        assertEquals(
            listOf("BEXALITE (RAW)", "BORASE (ORE)", "BORASE (ORE)", "LARANITE (RAW)", "TUNGSTEN (ORE)", "INERT MATERIALS"),
            result.rows.map { it.name },
        )
        assertEquals("105", result.rows.single { it.name == "LARANITE (RAW)" }.qty)
        assertTrue(result.rows.single { it.name == "LARANITE (RAW)" }.contested, "the cross-capture QTY disagreement is contested")
    }

    @Test
    fun `a quoted read of an OFF row beats the un-quoted read even though its yield is the marker`() {
        // The yield-based refine correction may only fire on rows whose surviving read saw the
        // quoted state — for an OFF row the quoted capture also shows "--", so preferring by
        // "has a numeric yield" alone would keep the un-quoted variant (and its provenance).
        val early = ImageRead(
            "early.png",
            panel(listOf(row("BEXALITE (RAW)", "597", "127", "--", refine = "ON")), quoted = false),
        )
        val quoted = ImageRead(
            "quoted.png",
            panel(listOf(row("BEXALITE (RAW)", "597", "127", "--", refine = "ON"))),
        )

        val result = Stitcher.stitch(listOf(early, quoted))

        assertEquals(1, result.rows.size)
        assertTrue(result.rows[0].quotedRead, "the quoted capture's read must survive")
        assertEquals("quoted.png", result.rows[0].sourceImage)
    }

    @Test
    fun `a one-character name garble with matching numbers does not break the overlap`() {
        // Auftrag 1 run-to-run reality: one read transcribes LINDINIUM as LINDINIMUM. The
        // numeric cells (quality+qty) disambiguate, so the overlap must still chain — otherwise
        // the shared rows duplicate (observed: 23 instead of 18 stitched rows).
        val top = ImageRead(
            "a.png",
            panel(
                listOf(
                    row("LINDINIUM (ORE)", "618", "957", "448"),
                    row("LINDINIUM (ORE)", "729", "391", "183"),
                    row("TUNGSTEN (ORE)", "530", "1431", "695"),
                ),
            ),
        )
        val bottom = ImageRead(
            "b.png",
            panel(
                listOf(
                    row("LINDINIMUM (ORE)", "729", "391", "183"),
                    row("TUNGSTEN (ORE)", "530", "1431", "695"),
                    row("UCTION SALVAGE", "0", "400", "64"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(top, bottom))

        assertEquals(4, result.rows.size)
        assertEquals(
            listOf("LINDINIUM (ORE)", "LINDINIUM (ORE)", "TUNGSTEN (ORE)", "UCTION SALVAGE"),
            result.rows.map { it.name },
        )
    }

    @Test
    fun `a quality-and-qty misread of the same row across captures merges on the stable yield`() {
        // Auftrag 14: the high-quality TUNGSTEN row is read 958|950 in one scroll capture and
        // 858|858 in the next — only the YIELD (413) is stable. Identity-on-the-triple kept BOTH
        // (the order stitched to 18 rows instead of 13); the positive-yield anchor must align them
        // into one row and mark it contested so the review looks.
        val upper = ImageRead(
            "2_mid.png",
            panel(
                listOf(
                    row("TUNGSTEN (ORE)", "363", "2171", "1055"),
                    row("TUNGSTEN (ORE)", "958", "950", "413"),
                    row("TUNGSTEN (ORE)", "902", "312", "151"),
                ),
            ),
        )
        val lower = ImageRead(
            "3_low.png",
            panel(
                listOf(
                    row("TUNGSTEN (ORE)", "858", "858", "413"),
                    row("TUNGSTEN (ORE)", "902", "312", "151"),
                    row("RICCITE (ORE)", "965", "261", "117"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(upper, lower))

        // 4 unique rows, not 6: the 958/858 row and the 902 row chain across the overlap.
        assertEquals(
            listOf("TUNGSTEN (ORE)", "TUNGSTEN (ORE)", "TUNGSTEN (ORE)", "RICCITE (ORE)"),
            result.rows.map { it.name },
        )
        assertTrue(result.rows[1].contested, "the row the captures read differently is contested")
        assertFalse(result.rows[2].contested, "a row both captures agree on is NOT contested")
    }

    @Test
    fun `an OFF seam row mis-read in both cells reconciles instead of duplicating`() {
        // Auftrag 15: the TARANITE overlap row (last of capture 1 = first of capture 2) is a
        // refine-OFF row (yield "--", so no positive-yield anchor). Capture 1 reads it 310/290,
        // capture 2 mis-reads BOTH cells as 318/298 (0/8 confusions), so overlap() finds nothing
        // and the row would export twice. Seam reconciliation aligns the boundary pair into one
        // contested row, keeping the upper capture's read.
        val upper = ImageRead(
            "1_upper.png",
            panel(
                listOf(
                    row("STILERON (ORE)", "947", "386", "174"),
                    row("BEXALITE (RAW)", "597", "89", "--", refine = "OFF"),
                    row("TARANITE (RAW)", "310", "290", "--", refine = "OFF"),
                ),
            ),
        )
        val lower = ImageRead(
            "2_lower.png",
            panel(
                listOf(
                    row("TARANITE [RAW]", "318", "298", "--", refine = "OFF"),
                    row("TARANITE [RAW]", "525", "619", "--", refine = "OFF"),
                    row("INERT MATERIALS", "0", "2403", "0", refine = "OFF"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(upper, lower))

        assertEquals(
            listOf("STILERON (ORE)", "BEXALITE (RAW)", "TARANITE (RAW)", "TARANITE [RAW]", "INERT MATERIALS"),
            result.rows.map { it.name },
            "the dual-misread seam row merges — 5 rows, not 6",
        )
        val seam = result.rows.single { it.name == "TARANITE (RAW)" }
        assertEquals("310", seam.quality, "the upper capture's seam read survives")
        assertEquals("290", seam.qty)
        assertTrue(seam.contested, "the cross-capture seam disagreement is contested")
    }

    @Test
    fun `a non-confusable OFF seam difference stays a scroll gap, not reconciled`() {
        // Guard: seam reconciliation only fires on a CONFUSABLE single-digit edit of the SAME
        // material. A boundary pair differing by a non-confusable digit (0 vs 7) is a genuine
        // scroll gap — keep both rows and let the checksum flag the hole rather than drop one.
        val upper = ImageRead("1.png", panel(listOf(row("TARANITE (RAW)", "310", "290", "--", refine = "OFF"))))
        val lower = ImageRead("2.png", panel(listOf(row("TARANITE (RAW)", "317", "290", "--", refine = "OFF"))))

        val result = Stitcher.stitch(listOf(upper, lower))

        assertEquals(2, result.rows.size, "a non-confusable (0 vs 7) quality difference is not a seam re-read")
    }

    @Test
    fun `equal qty and yield differing only in quality is not merged across captures`() {
        // Safety guard for the yield anchor: two genuinely distinct TUNGSTEN tiers that happen to
        // hold an equal amount (same QTY, same YIELD, different QUALITY) must NOT collapse — only a
        // QTY *disagreement* unlocks the yield anchor, so these stay two rows for the review.
        val first = ImageRead("one.png", panel(listOf(row("TUNGSTEN (ORE)", "858", "850", "413"))))
        val second = ImageRead("two.png", panel(listOf(row("TUNGSTEN (ORE)", "902", "850", "413"))))

        val result = Stitcher.stitch(listOf(first, second))

        assertEquals(2, result.rows.size, "equal qty+yield with only a quality difference must not merge")
    }

    @Test
    fun `the garble tolerance needs both numeric cells - unreadable numbers stay strict`() {
        // Without quality+qty as disambiguators a one-edit name match is NOT enough to merge.
        val a = ImageRead("a.png", panel(listOf(row("TORITE (ORE)", null, null, "30"))))
        val b = ImageRead("b.png", panel(listOf(row("TORIDE (ORE)", null, null, "30"))))

        val result = Stitcher.stitch(listOf(a, b))

        assertEquals(2, result.rows.size)
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
        assertTrue(result.rows[0].quotedRead, "the surviving read saw the quoted state")
        assertTrue(result.quoted, "any quoted read makes the stitched order quoted")
        assertEquals("48928", result.totalCost, "cost comes from the quoted read")
    }

    @Test
    fun `bracket-style transcription variants of the same row align across captures`() {
        // The VLM sometimes transcribes the suffix as "[ORE]" instead of "(ORE)" — same panel,
        // same row. The identity key folds the bracket style so the overlap still matches.
        val first = ImageRead(
            "one.png",
            panel(
                listOf(
                    row("GOLD (ORE)", "553", "46", "21"),
                    row("BORASE (ORE)", "359", "483", "195"),
                ),
            ),
        )
        val second = ImageRead(
            "two.png",
            panel(
                listOf(
                    row("BORASE [ORE]", "359", "483", "195"),
                    row("TUNGSTEN [ORE]", "858", "276", "134"),
                ),
            ),
        )

        val result = Stitcher.stitch(listOf(first, second))

        assertEquals(3, result.rows.size)
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
        assertEquals(false, result.rows[0].quotedRead, "row provenance: the read was un-quoted")
    }
}
