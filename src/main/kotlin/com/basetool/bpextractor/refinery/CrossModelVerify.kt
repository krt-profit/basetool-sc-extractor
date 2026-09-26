package com.basetool.bpextractor.refinery

/**
 * Merges the stitched read of the primary model with that of an architecturally different verify
 * model, whose errors are decorrelated from the primary's.
 *
 * - Cells the models read differently mark the row contested.
 * - A disagreeing QTY of a refine-ON row is decided by the TO REFINE checksum when exactly one
 *   candidate lands the sum inside the tolerance band.
 * - Otherwise YIELD ≤ QTY decides when exactly one candidate satisfies it.
 *
 * When the row sets do not align 1:1, no cell comparison runs and the order is only flagged.
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
        /**
         * The two models read DIFFERENT TO_REFINE header totals — one of them mis-read the
         * load-bearing checksum anchor. [Validation] folds this into the OCR header cross-check and
         * makes the qty checksum-repair abstain rather than repair against a suspect total.
         */
        val headerToRefineContested: Boolean = false,
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

        val baseSum = primary.rows
            .filter { finalRefine(it) }
            .sumOf { PanelValues.toQuantity(it.qty) ?: 0L }

        primary.rows.forEachIndexed { i, p ->
            val s = secondary.rows[i]

            if (!namesAgree(p, s)) contested += i
            if (p.quality != s.quality) contested += i

            if (p.refine != s.refine && Validation.yieldRefineSignal(p) == null) contested += i

            var row = p
            if (normalizedYield(p.yield_) != normalizedYield(s.yield_)) {
                when (arbitrateYield(p, s)) {
                    Arbitration.SECONDARY -> {
                        row = row.copy(yield_ = s.yield_)
                        corrected += i
                    }
                    Arbitration.PRIMARY -> Unit
                    Arbitration.UNDECIDED -> contested += i
                }
            }
            if (p.qty != s.qty) {
                when (arbitrateQty(p, s, baseSum, toRefine, tolerance)) {
                    Arbitration.SECONDARY -> {
                        row = row.copy(qty = s.qty)
                        corrected += i
                    }
                    Arbitration.PRIMARY -> Unit
                    Arbitration.UNDECIDED -> contested += i
                }
            }
            rows[i] = row
        }
        val pT = PanelValues.toQuantity(primary.toRefine)
        val sT = PanelValues.toQuantity(secondary.toRefine)
        val headerContested = pT != null && sT != null && pT != sT
        return Outcome(
            rows, contested, corrected, comparable = true,
            secondaryRows = secondary.rows, headerToRefineContested = headerContested,
        )
    }

    private enum class Arbitration { PRIMARY, SECONDARY, UNDECIDED }

    /**
     * Decides a QTY disagreement: first by the TO REFINE checksum (refine-ON rows, substituted sum inside
     * the tolerance band), then by QTY ≥ YIELD over an agreed yield. Undecided stays contested.
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
     * Folds a name for comparison only: case, all whitespace and bracket style are ignored, so
     * `SA VRIL IUM (ORE)` equals `SAVRILIUM [ORE]`.
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
