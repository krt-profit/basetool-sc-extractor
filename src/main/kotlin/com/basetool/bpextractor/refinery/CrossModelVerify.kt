package com.basetool.bpextractor.refinery

/**
 * Cross-model verification (PHASE0_FINDINGS 2026-06-12 addendum): merge the stitched read of the
 * primary model with the stitched read of an architecturally DIFFERENT verify model.
 *
 * Same-model two-pass was rejected in Phase 0 (§6): at temperature 0 the errors are systematic,
 * so a second pass reads the same wrong value. Two different vision encoders, however, make
 * DECORRELATED errors — on the golden set there is no cell where both models read the same wrong
 * value. That gives two deterministic levers:
 *
 * 1. **Disagreement flagging** — any cell the models read differently contains at least one
 *    misread; the row is marked contested and the review is forced to look (the union of both
 *    models' error sets becomes visible, including errors no checksum can catch).
 * 2. **Checksum arbitration** — for a disagreeing QTY cell of a refine-ON row, the TO REFINE
 *    header (= Σ QTY of refine-ON rows, ±1 per row, §7) decides: if exactly one candidate value
 *    lands the sum inside the tolerance band, that value wins. This auto-corrects digit misreads
 *    that previously could only be flagged as an order-level SUM_MISMATCH.
 * 3. **Physics arbitration** — refining removes impurities, so YIELD can never exceed QTY. When
 *    the models disagree on ONE of the two cells while agreeing on the other, and exactly one
 *    candidate satisfies the constraint, that candidate wins. Decides cells the header checksum
 *    cannot reach (refine-OFF rows, missing header).
 *
 * The merge is conservative: when the two stitched row sets do not align 1:1 (different row
 * count — e.g. a ghost row in one read), no cell comparison runs and the order is only flagged.
 */
object CrossModelVerify {

    /** The merged outcome: primary rows with arbitrated substitutions + per-row index flags. */
    data class Outcome(
        /** The primary rows, with arbitrated QTY/YIELD substitutions applied. */
        val rows: List<StitchedRow>,
        /** Row indices with an unresolved disagreement — one model is wrong, review must look. */
        val contested: Set<Int>,
        /** Row indices where an arbiter picked the verify model's cell over the primary's. */
        val corrected: Set<Int>,
        /** False when the row sets did not align — no cell comparison was possible. */
        val comparable: Boolean,
        /**
         * The verify model's aligned rows (index-parallel to [rows]) — the second vote for the
         * QUALITY majority in [Validation]; empty when the row sets did not align ([comparable]).
         */
        val secondaryRows: List<StitchedRow> = emptyList(),
    )

    /** Merge [primary] (authoritative) with the [secondary] verify read. */
    fun merge(primary: StitchResult, secondary: StitchResult): Outcome {
        if (primary.rows.size != secondary.rows.size) {
            return Outcome(primary.rows, emptySet(), emptySet(), comparable = false)
        }

        val toRefine = PanelValues.toQuantity(primary.toRefine)
        val tolerance = primary.rows.size.toLong()
        val rows = primary.rows.toMutableList()
        val contested = mutableSetOf<Int>()
        val corrected = mutableSetOf<Int>()

        // Σ QTY of refine-ON rows from the primary read — the arbitration baseline. Refine uses
        // the same yield-first semantics as Validation, so a misread toggle cannot skew the sum
        // for quoted reads.
        val baseSum = primary.rows
            .filter { finalRefine(it) }
            .sumOf { PanelValues.toQuantity(it.qty) ?: 0L }

        primary.rows.forEachIndexed { i, p ->
            val s = secondary.rows[i]

            if (!namesAgree(p, s)) contested += i
            if (p.quality != s.quality) contested += i

            // A refine disagreement only matters where the yield signal does not already decide
            // it deterministically (Validation overrides the toggle for quoted reads anyway).
            if (p.refine != s.refine && Validation.yieldRefineSignal(p) == null) contested += i

            var row = p
            if (normalizedYield(p.yield_) != normalizedYield(s.yield_)) {
                when (arbitrateYield(p, s)) {
                    Arbitration.SECONDARY -> {
                        row = row.copy(yield_ = s.yield_)
                        corrected += i
                    }
                    Arbitration.PRIMARY -> Unit // physics confirms the primary — no flag
                    Arbitration.UNDECIDED -> contested += i
                }
            }
            if (p.qty != s.qty) {
                when (arbitrateQty(p, s, baseSum, toRefine, tolerance)) {
                    Arbitration.SECONDARY -> {
                        row = row.copy(qty = s.qty)
                        corrected += i
                    }
                    Arbitration.PRIMARY -> Unit // an arbiter confirms the primary — no flag
                    Arbitration.UNDECIDED -> contested += i
                }
            }
            rows[i] = row
        }
        return Outcome(rows, contested, corrected, comparable = true, secondaryRows = secondary.rows)
    }

    private enum class Arbitration { PRIMARY, SECONDARY, UNDECIDED }

    /**
     * Decide a QTY disagreement. First arbiter: the TO REFINE checksum (refine-ON rows only;
     * "wins" requires the substituted Σ to land INSIDE the ±tolerance band — if rows are
     * scrolled out of the viewport, neither sum lands). Second arbiter where the checksum cannot
     * reach (OFF rows, missing header, both sums fitting): the physical constraint QTY ≥ YIELD
     * over an AGREED numeric yield. Undecided stays contested (safe).
     */
    private fun arbitrateQty(
        p: StitchedRow,
        s: StitchedRow,
        baseSum: Long,
        toRefine: Long?,
        tolerance: Long,
    ): Arbitration {
        val qtyP = PanelValues.toQuantity(p.qty) ?: return Arbitration.UNDECIDED
        val qtyS = PanelValues.toQuantity(s.qty) ?: return Arbitration.UNDECIDED
        if (toRefine != null && finalRefine(p)) {
            val fitsP = kotlin.math.abs(baseSum - toRefine) <= tolerance
            val fitsS = kotlin.math.abs(baseSum - qtyP + qtyS - toRefine) <= tolerance
            if (fitsP && !fitsS) return Arbitration.PRIMARY
            if (fitsS && !fitsP) return Arbitration.SECONDARY
        }
        if (p.yield_ != null && p.yield_ == s.yield_) {
            val yieldQty = PanelValues.toQuantity(p.yield_)
            if (yieldQty != null) {
                val fitsP = qtyP >= yieldQty
                val fitsS = qtyS >= yieldQty
                if (fitsP && !fitsS) return Arbitration.PRIMARY
                if (fitsS && !fitsP) return Arbitration.SECONDARY
            }
        }
        return Arbitration.UNDECIDED
    }

    /**
     * Decide a YIELD disagreement via the physical constraint YIELD ≤ QTY over an AGREED QTY:
     * exactly one numeric candidate inside the bound wins. `--`/unreadable candidates cannot win
     * numerically — the cell stays contested.
     */
    private fun arbitrateYield(p: StitchedRow, s: StitchedRow): Arbitration {
        if (p.qty == null || p.qty != s.qty) return Arbitration.UNDECIDED
        val qty = PanelValues.toQuantity(p.qty) ?: return Arbitration.UNDECIDED
        val yieldP = PanelValues.toQuantity(p.yield_) ?: return Arbitration.UNDECIDED
        val yieldS = PanelValues.toQuantity(s.yield_) ?: return Arbitration.UNDECIDED
        val fitsP = yieldP <= qty
        val fitsS = yieldS <= qty
        return when {
            fitsP && !fitsS -> Arbitration.PRIMARY
            fitsS && !fitsP -> Arbitration.SECONDARY
            else -> Arbitration.UNDECIDED
        }
    }

    /** The row's effective refine state, yield signal first (mirrors [Validation.validate]). */
    private fun finalRefine(row: StitchedRow): Boolean =
        Validation.yieldRefineSignal(row) ?: (row.refine != "OFF")

    /**
     * Name folding for comparison only (exported names stay verbatim): case, ALL whitespace and
     * the bracket style fold — `SA VRIL IUM (ORE)` vs `SAVRILIUM [ORE]` is the same physical row
     * transcribed by two models, not a disagreement worth a review flag.
     */
    private fun foldName(name: String): String =
        name.uppercase().replace(Regex("\\s+"), "").replace('[', '(').replace(']', ')')

    /**
     * Names agree when the folded spellings match, or differ by a SINGLE character while both
     * numeric cells agree (same tolerance as the stitcher's row identity): a one-edit garble
     * (`LINDINIMUM`) is a transcription artefact basetool's fuzzy name matching absorbs anyway —
     * the primary's spelling stays, no review flag.
     */
    private fun namesAgree(p: StitchedRow, s: StitchedRow): Boolean {
        val a = foldName(p.name)
        val b = foldName(s.name)
        if (a == b) return true
        return p.quality != null && p.quality == s.quality &&
            p.qty != null && p.qty == s.qty && Stitcher.withinOneEdit(a, b)
    }

    /** Yield comparison: `--` and null (absent) both mean "no quoted yield". */
    private fun normalizedYield(value: String?): String? = value?.takeUnless { it == "--" }
}
