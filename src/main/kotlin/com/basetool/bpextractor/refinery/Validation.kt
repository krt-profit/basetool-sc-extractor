package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.refinery.model.RefineryExtractOrder

/** Machine-readable validation findings for the extraction UI (design spec §5.3/§5.4). */
enum class ExtractWarning {
    /** The whole order was captured before GET QUOTE — yields/cost/time are `--`. */
    UNQUOTED_ORDER,

    /** Σ QTY of the visible refine-ON rows exceeds the TO REFINE header — impossible, mis-read. */
    SUM_MISMATCH,

    /** At least one cell that must be numeric did not parse (e.g. HUD-marker bleed-through). */
    IMPLAUSIBLE_CELL,

    /**
     * A REFINE toggle read contradicted the YIELD column of a quoted order and was corrected
     * (the toggle shows an orange knob in BOTH states — left = OFF, right = ON — which VLMs
     * misread as "filled = ON"; the yield cell is the reliable signal: `--` = OFF, > 0 = ON).
     */
    REFINE_CORRECTED,

    /**
     * Cross-model verify: a disagreeing QTY cell was resolved via the TO REFINE checksum — the
     * verify model's value made Σ QTY(ON) land on the header total where the primary's did not.
     */
    VERIFY_CORRECTED,

    /**
     * Cross-model verify: the two models disagree on at least one cell (or their row sets did
     * not align) and no deterministic signal decides it — at least one read is wrong, review
     * must look at the contested rows.
     */
    VERIFY_MISMATCH,

    /**
     * The transcribed bottom button contradicts the quoted state (`CONFIRM` belongs to a quoted
     * panel, `GET QUOTE` to an un-quoted one) — one of the two header reads is wrong.
     */
    CTA_MISMATCH,

    /**
     * A refine-ON row whose YIELD/QTY ratio deviates from the SAME material's other rows: the
     * refinery method's per-material yield rate is constant within an order, so a divergent ratio
     * is a likely digit mis-read in the QTY or YIELD cell (deterministic, needs ≥ 2 rows of the
     * material; flags for review, never guesses which cell).
     */
    YIELD_RATIO_OUTLIER,

    /**
     * A row re-read across OVERLAPPING captures with a disagreeing numeric cell
     * ([StitchedRow.contested]): one capture mis-read it, so the kept value is consensus-unsafe —
     * the only signal that catches the single-digit flips no checksum can (Auftrag 14: the same
     * TUNGSTEN row read 850 in one capture and 858 in the next).
     */
    STITCH_CONTESTED,

    /**
     * A QTY digit was DETERMINISTICALLY corrected ([Validation.checksumRepair]): a unique
     * confusable single-digit edit of one over-reading ON row simultaneously lands the sum of ON-row
     * QTY on the TO REFINE header AND matches that row's own YIELD via the material's rate. The
     * export carries the corrected value (the only read-time fix that recovers truth from arithmetic,
     * not from pixels that don't contain it).
     */
    CHECKSUM_REPAIRED,

    /**
     * A YIELD digit was DETERMINISTICALLY corrected ([Validation.yieldRepair]): a refine-ON row's
     * read yield deviated GROSSLY from the material's per-row yield rate (from ≥ 2 sibling rows of
     * the same material), and a UNIQUE confusable single-digit edit of it lands back on the
     * rate-implied yield while staying ≤ qty (physics). Recovers an OUTPUT mis-read the qty checksum
     * cannot — yield is not in the TO-REFINE sum. Abstains when the read is within the rate's
     * few-percent noise floor: a within-noise last-digit flip (e.g. 2720↔2728) is NOT recoverable
     * from arithmetic, only from a better read.
     */
    YIELD_REPAIRED,

    /**
     * A YIELD cell was corrected by the classical-OCR cross-reader ([Validation.ocrYieldRepair]):
     * the VLM read a yield that [yieldRepair] could NOT fix arithmetically (the deviation is inside
     * the rate's few-percent noise floor), but OCR read a single CONFUSABLE-digit alternative that
     * is ≤ qty (physics) AND lands STRICTLY closer to the material's per-row yield rate than the VLM
     * read. This is exactly the "recoverable only from a better read" case [YIELD_REPAIRED]'s
     * docstring names — OCR is that better read, here corroborated by physics + the rate before it
     * overwrites. A correct yield sits ON the rate, so a confusable OCR misread of it is strictly
     * FARTHER and is rejected: a correct cell can never be flipped (the safety crux).
     */
    YIELD_OCR_REPAIRED,

    /**
     * A QUALITY cell was corrected by 8b/4b/OCR MAJORITY ([Validation.resolveQuality]): the
     * classical-OCR cross-reader ([OcrCrossCheck]) is a third, decorrelated vote on the QUALITY
     * column, which has no arithmetic anchor (the cross-model verify could only FLAG it). When the
     * verify model and OCR agree on a value the primary VLM mis-read, that majority value wins. The
     * lone column where a better READ — not arithmetic — is the only recovery; needs the verify
     * model on for a third vote (without it a 1-1 primary/OCR split only flags, never corrects).
     */
    OCR_CORRECTED,

    /**
     * A QUALITY cell where the votes ([Validation.resolveQuality]) disagree with NO majority — the
     * primary VLM, the verify model and OCR do not 2-of-3 agree (or only two votes exist and they
     * differ). At least one read is wrong and nothing decides it: the export keeps the primary's
     * value and review must look. The OCR analogue of [VERIFY_MISMATCH] for the quality column.
     */
    OCR_CONTESTED,

    /**
     * A refine-OFF row's QTY disagrees with the classical-OCR read ([OcrCrossCheck]): OFF rows are in
     * no checksum and (often) carry no quality cell, so neither the TO-REFINE arithmetic nor the
     * quality majority can witness their qty — this OCR cross-check is the only signal that surfaces
     * such a mis-read (e.g. an INERT qty both VLMs read identically wrong). FLAG-ONLY: the export
     * keeps the VLM value (auto-correcting an OFF qty from a lone OCR read over an agreeing VLM pair is
     * deliberately rejected); review decides.
     */
    QTY_OCR_CONTESTED,

    /**
     * The load-bearing TO_REFINE header total is contested — the verify model and/or the classical-OCR
     * read disagree with the primary VLM's total ([OcrCrossCheck]/[CrossModelVerify]). Every checksum
     * trusts this one number, so when it is suspect the qty checksum-repair ABSTAINS (it will not
     * repair a qty against a possibly-wrong total) and the order is flagged for review.
     */
    TO_REFINE_CONTESTED,
}

/** The validated order: contract-ready goods + order fields + warnings + layout confidence. */
data class ValidatedOrder(
    val goods: List<RefineryExtractGood>,
    val quoted: Boolean,
    val method: String?,
    val inManifestTotal: Long?,
    val toRefineTotal: Long?,
    val expenses: Double?,
    val durationMinutes: Long?,
    val layoutConfidence: Double,
    val warnings: Set<ExtractWarning>,
)

/**
 * Turns a [StitchResult] into contract-ready goods with DERIVED per-row confidence — the Phase 0
 * confidence policy (`docs/refinery-extractor/PHASE0_FINDINGS.md` §6). The planned two-pass
 * agreement was rejected by the golden-set data (it caught 0/5 systematic errors and introduced
 * digit errors of its own); confidence comes from deterministic validation instead:
 *
 * 1. numeric plausibility — QTY / QUALITY / YIELD must parse as numbers (or be the un-quoted
 *    `--`); a non-numeric read (e.g. `2.1KM` HUD bleed-through) drops the row to
 *    [CONFIDENCE_IMPLAUSIBLE];
 * 2. the REFINE toggle must read `ON` or `OFF`; anything else defaults to ON at low confidence
 *    so the backend drafts the row and the review forces a look. In a QUOTED order the YIELD
 *    column overrides the toggle read entirely (`--` ⇒ OFF, > 0 ⇒ ON): the terminal quotes a
 *    yield for exactly the refine-ON rows, while the toggle glyph carries an orange knob in
 *    both states (only its position differs) and is the documented VLM misread class. A
 *    contradicting toggle read is corrected at [CONFIDENCE_REFINE_CORRECTED] + warning;
 * 3. the one-sided header checksum (verified semantics: TO REFINE = Σ QTY of refine-ON rows,
 *    ±1 display rounding per row; the list is a ~6-row scrolling viewport, so only
 *    "visible Σ EXCEEDS the header" is flaggable);
 * 4. the optional cross-model verify ([CrossModelVerify]): rows whose QTY the checksum
 *    arbitration replaced cap at [CONFIDENCE_VERIFY_CORRECTED]; rows the two models read
 *    differently without a deterministic arbiter cap at [CONFIDENCE_VERIFY_CONTESTED];
 * 5. the model's verbalized self-confidence is never used.
 *
 * The order's `layoutConfidence` is the mean row confidence, dampened when the checksum flags.
 */
object Validation {

    /** Clean full-read row confidence (no independent verification exists beyond validation). */
    const val CONFIDENCE_OK = 0.95

    /** A row with an implausible (non-numeric where numeric is required) cell. */
    const val CONFIDENCE_IMPLAUSIBLE = 0.4

    /** A row whose REFINE toggle did not read as ON/OFF. */
    const val CONFIDENCE_REFINE_UNREADABLE = 0.5

    /** A row whose REFINE toggle read contradicted the quoted YIELD column and was corrected. */
    const val CONFIDENCE_REFINE_CORRECTED = 0.85

    /** A row whose QTY the cross-model checksum arbitration replaced (deterministic, reviewed). */
    const val CONFIDENCE_VERIFY_CORRECTED = 0.85

    /** A row the two models read differently with no deterministic arbiter — one of them errs. */
    const val CONFIDENCE_VERIFY_CONTESTED = 0.75

    /** A row re-read with a disagreeing cell across overlapping captures (consensus-unsafe). */
    const val CONFIDENCE_STITCH_CONTESTED = 0.75

    /** A row whose YIELD/QTY ratio deviates from its material's siblings (likely digit mis-read). */
    const val CONFIDENCE_YIELD_OUTLIER = 0.6

    /** A QTY cell deterministically corrected from the checksum + yield rate (reviewed-grade). */
    const val CONFIDENCE_CHECKSUM_REPAIRED = 0.85

    /** A YIELD cell deterministically corrected from the per-material rate witness (reviewed-grade). */
    const val CONFIDENCE_YIELD_REPAIRED = 0.85

    /** A QUALITY cell corrected by 8b/4b/OCR majority (reviewed-grade — two readers outvoted one). */
    const val CONFIDENCE_OCR_CORRECTED = 0.85

    /** A QUALITY cell the three readers disagree on with no majority — one is wrong, review looks. */
    const val CONFIDENCE_OCR_CONTESTED = 0.75

    /** A read yield must deviate beyond this from the rate-implied yield to be a repair candidate. */
    private const val YIELD_REPAIR_TRIGGER = 0.05

    /** A proposed corrected yield must land within this of the rate-implied yield (tighter than the trigger). */
    private const val YIELD_REPAIR_LANDING = 0.04

    /**
     * Digits the SC HUD font renders ambiguously (the round/loopy glyphs) — the observed confusion
     * set across the golden mis-reads (0<->8: 850/858, 403/483, 510/518; 0<->9: 404/494; 6<->8:
     * 365/385, 965/985; 8<->9: 858/958). [checksumRepair] only swaps WITHIN this set.
     */
    private val CONFUSABLE_DIGITS = setOf('0', '6', '8', '9')

    /**
     * Tight YIELD/QTY tolerance for a checksum REPAIR (vs. the looser flagging tolerance): a
     * proposed corrected QTY must match the row's read YIELD within this, so a checksum-only edit
     * that happens to land the sum but contradicts the yield (e.g. 591->501) is rejected.
     */
    private const val REPAIR_YIELD_TOLERANCE = 0.06

    /**
     * Relative tolerance for the per-material YIELD/QTY ratio cross-check. Within-material spread
     * from per-row display rounding is a few percent on small rows; only a GROSS divergence (a
     * mis-read digit, e.g. RICCITE 2877 where the sibling rate implies ~2077) clears this.
     */
    private const val YIELD_RATIO_TOLERANCE = 0.18

    /** Dampening factor on layout confidence when the header checksum flags. */
    private const val SUM_MISMATCH_DAMPENING = 0.9

    /**
     * The YIELD column's deterministic refine signal: in a QUOTED read the terminal quotes a
     * yield for exactly the refine-ON rows and renders `--` for OFF rows, while the toggle glyph
     * shows an orange knob in BOTH states (left = OFF, right = ON) and is the known VLM misread
     * class. Gated on the ROW's surviving read having seen the quoted state — in a mixed capture
     * set a row visible only in a pre-GET-QUOTE capture shows `--` legitimately. A yield of
     * exactly 0 stays ambiguous (INERT MATERIALS shows 0 while OFF) ⇒ null = no signal.
     */
    fun yieldRefineSignal(row: StitchedRow): Boolean? = when {
        !row.quotedRead -> null
        row.yield_ == "--" -> false
        (PanelValues.toQuantity(row.yield_) ?: 0L) > 0L -> true
        else -> null
    }

    /**
     * The one-sided TO-REFINE checksum over contract-ready [goods] (§7 semantics: Σ QTY of
     * refine-ON rows ≤ header + ±1 display rounding per row; shortfall is legal — scrolling
     * viewport). Public so the review screen can re-check it against user-corrected rows.
     */
    fun sumMismatch(goods: List<RefineryExtractGood>, toRefineTotal: Long?): Boolean {
        if (toRefineTotal == null) return false
        val visibleOn = goods.filter { it.refine }.mapNotNull { it.inputQuantity }
        val tolerance = goods.size.toLong()
        return visibleOn.sum() > toRefineTotal + tolerance || visibleOn.any { it > toRefineTotal + 1 }
    }

    /**
     * Refine-ON rows whose YIELD/QTY ratio deviates from the SAME material's other rows — the
     * refinery method's per-material yield rate is constant within an order, so a divergent ratio
     * is a likely digit mis-read in the QTY or YIELD cell. Each row is judged against the
     * leave-one-out median of its material's other rows, so a single gross outlier in a 2-row
     * material flags BOTH rows as inconsistent (a median-of-two would sit halfway and hide it)
     * rather than picking the wrong one. Needs ≥ 2 rows of the material with positive QTY and
     * YIELD. Public so the review screen could re-check it against user-corrected rows.
     */
    fun yieldRatioOutliers(goods: List<RefineryExtractGood>): Set<Int> {
        val groups = goods
            .filter { it.refine && (it.inputQuantity ?: 0L) > 0L && (it.outputQuantity ?: 0L) > 0L }
            .groupBy { foldMaterial(it.rawMaterialName) }
        val outliers = mutableSetOf<Int>()
        for (group in groups.values) {
            if (group.size < 2) continue
            val ratios = group.associate { it.rowIndex to it.outputQuantity!!.toDouble() / it.inputQuantity!! }
            for (g in group) {
                val consensus = median(ratios.filterKeys { it != g.rowIndex }.values.toList())
                val ratio = ratios.getValue(g.rowIndex)
                if (consensus > 0.0 && kotlin.math.abs(ratio - consensus) / consensus > YIELD_RATIO_TOLERANCE) {
                    outliers += g.rowIndex
                }
            }
        }
        return outliers
    }

    /** Fold a material name for grouping: trim, uppercase, collapse spaces, normalise bracket style. */
    private fun foldMaterial(name: String): String =
        name.trim().uppercase().replace(Regex("\\s+"), " ").replace('[', '(').replace(']', ')')

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * Deterministic single-digit QTY repair from the TO-REFINE checksum + per-material yield rate.
     * Returns rowIndex -> corrected QTY, or an empty map when nothing is UNIQUELY determined (it
     * abstains rather than guess). Fires only when Σ QTY(ON) EXCEEDS the header — the one direction
     * the sum can never legally take (a complete or scrolled-out capture is at most header + per-row
     * rounding), so an over-shoot is a guaranteed over-read. A candidate is one CONFUSABLE-digit
     * edit of one ON row's QTY that simultaneously: (a) lands Σ QTY(ON) back inside the ±1/row
     * rounding band of TO REFINE — a tight landing that doubles as a completeness check, since a
     * scrolled-out order's corrected sum stays short of the band and the repair abstains;
     * (b) keeps YIELD <= QTY (physics); and (c) matches the row's own YIELD via the material's
     * REQUIRED row-specific witness — its own YIELD, via the material's rate from OTHER rows
     * (leave-one-out), must predict the corrected qty. Checksum-landing ALONE is never enough: the
     * over-read proves some row is wrong but not which, so a confusable edit on an innocent row could
     * otherwise land the band and corrupt a correct cell. A row with no yield or no same-material
     * sibling has no witness and is never repaired (it stays flagged). Applies only when exactly one
     * candidate survives.
     */
    fun checksumRepair(goods: List<RefineryExtractGood>, toRefineTotal: Long?): Map<Int, Long> {
        if (toRefineTotal == null) return emptyMap()
        val on = goods.filter { it.refine && it.inputQuantity != null }
        val sumOn = on.sumOf { it.inputQuantity!! }
        val tol = goods.size.toLong()
        if (sumOn <= toRefineTotal + tol) return emptyMap() // not an over-read — nothing to repair
        val candidates = mutableSetOf<Pair<Int, Long>>()
        for (row in on) {
            val qty = row.inputQuantity!!
            // MANDATORY row-specific witness: the over-read direction proves SOME row is wrong, but
            // checksum-landing alone does not say WHICH — a confusable edit on an innocent row can
            // uniquely land the band and corrupt a correct cell. So a row is only repairable when its
            // OWN yield, via the material's rate from OTHER rows (leave-one-out, so the over-read
            // can't pollute its own gate), independently predicts the corrected qty. No yield or no
            // same-material sibling ⇒ no witness ⇒ this row is never repaired (it stays flagged).
            val yieldQ = row.outputQuantity ?: continue
            if (yieldQ <= 0L) continue
            val siblingRatios = on.filter {
                it.rowIndex != row.rowIndex &&
                    foldMaterial(it.rawMaterialName) == foldMaterial(row.rawMaterialName) &&
                    (it.inputQuantity ?: 0L) > 0L && (it.outputQuantity ?: 0L) > 0L
            }.map { it.outputQuantity!!.toDouble() / it.inputQuantity!! }
            if (siblingRatios.isEmpty()) continue
            val rate = median(siblingRatios)
            if (rate <= 0.0) continue
            val implied = yieldQ / rate
            if (implied <= 0.0) continue
            for (newQty in confusableEdits(qty)) {
                if (newQty < yieldQ) continue // physics: yield <= qty
                val newSum = sumOn - qty + newQty
                if (newSum < toRefineTotal - tol || newSum > toRefineTotal + tol) continue // checksum band
                if (kotlin.math.abs(newQty - implied) / implied > REPAIR_YIELD_TOLERANCE) continue // yield witness
                candidates += row.rowIndex to newQty
            }
        }
        return if (candidates.size == 1) mapOf(candidates.first().first to candidates.first().second) else emptyMap()
    }

    /**
     * Deterministic single-digit YIELD repair from the per-material yield rate (the OUTPUT-cell
     * analogue of [checksumRepair]). The refinery method's yield rate is constant within an order, so
     * a refine-ON row whose READ yield deviates GROSSLY from the rate — taken from its material's
     * OTHER rows (leave-one-out, so the mis-read can't pollute its own gate) — is a likely output
     * mis-read. Returns rowIndex -> corrected yield when a UNIQUE confusable single-digit edit of the
     * read yield lands back within [YIELD_REPAIR_LANDING] of the rate-implied yield AND stays ≤ qty
     * (physics: output never exceeds input); abstains otherwise. Requires ≥ 2 same-material sibling
     * rows for a trustworthy rate, and fires only when the read is beyond [YIELD_REPAIR_TRIGGER] of
     * the implied yield — a within-noise last-digit flip (2720↔2728) is NOT recoverable from
     * arithmetic (the rate's few-percent noise hides it) and is left to a better read. Yield is not
     * in the TO-REFINE checksum, so this is the only deterministic recovery for an output cell.
     */
    fun yieldRepair(goods: List<RefineryExtractGood>): Map<Int, Long> {
        val on = goods.filter { it.refine && (it.inputQuantity ?: 0L) > 0L && (it.outputQuantity ?: 0L) > 0L }
        val result = mutableMapOf<Int, Long>()
        for (row in on) {
            val qty = row.inputQuantity!!
            val yieldQ = row.outputQuantity!!
            val siblingRates = on.filter {
                it.rowIndex != row.rowIndex &&
                    foldMaterial(it.rawMaterialName) == foldMaterial(row.rawMaterialName)
            }.map { it.outputQuantity!!.toDouble() / it.inputQuantity!! }
            if (siblingRates.size < 2) continue // a single sibling's rounding noise is not a trustworthy rate
            val rate = median(siblingRates)
            if (rate <= 0.0) continue
            val implied = qty * rate
            if (implied <= 0.0) continue
            // Trigger only on a deviation beyond BOTH the relative rate noise AND a small absolute
            // floor — a tiny-qty row's ±0.5 display rounding is a large RELATIVE swing that must not
            // look like a mis-read (e.g. yield 12 on qty 26).
            if (kotlin.math.abs(yieldQ - implied) <= maxOf(2.0, implied * YIELD_REPAIR_TRIGGER)) continue
            val candidates = confusableEdits(yieldQ).filter { cand ->
                cand in 1..qty && kotlin.math.abs(cand - implied) / implied <= YIELD_REPAIR_LANDING
            }.toSet()
            if (candidates.size == 1) result[row.rowIndex] = candidates.first()
        }
        return result
    }

    /**
     * OCR-WITNESSED single-digit YIELD correction — the recovery [yieldRepair] abstains on.
     * [yieldRepair] fires only on GROSS deviations (beyond the rate's few-percent noise floor); a
     * within-noise last-digit flip (e.g. STILERON 2720 vs the rate-implied 2728) is invisible to
     * arithmetic and "recoverable only from a better read". The classical-OCR cross-reader
     * ([OcrCrossCheck]) IS that read. A refine-ON row's VLM yield is replaced by the OCR yield when
     * ALL hold: the OCR value is a single CONFUSABLE-digit edit of the VLM value (same number bar one
     * ambiguous glyph); it is ≤ qty (physics — output never exceeds input); and, using the per-material
     * rate from ≥ 2 same-material ON siblings (leave-one-out), it lands within [YIELD_REPAIR_LANDING]
     * of AND STRICTLY CLOSER to the rate-implied yield than the VLM value. The strict-closer test is
     * the safety crux: a correct yield already sits ON the rate, so any confusable misread of it is
     * strictly FARTHER and is rejected — only an off-the-rate VLM read is ever overwritten, never a
     * correct one. Rows already corrected by the arithmetic [yieldRepair] ([alreadyFixed]) are skipped.
     * Gated on rows that HAVE an OCR reading, so a build/run with no OCR models is byte-for-byte
     * unchanged.
     */
    fun ocrYieldRepair(
        goods: List<RefineryExtractGood>,
        ocr: Map<Int, PanelOcr.RowReading>,
        alreadyFixed: Set<Int> = emptySet(),
    ): Map<Int, Long> {
        val on = goods.filter { it.refine && (it.inputQuantity ?: 0L) > 0L && (it.outputQuantity ?: 0L) > 0L }
        val result = mutableMapOf<Int, Long>()
        for ((i, reading) in ocr) {
            if (i in alreadyFixed) continue
            val good = goods.getOrNull(i) ?: continue
            if (!good.refine) continue
            val qty = good.inputQuantity ?: continue
            val vlmYield = good.outputQuantity ?: continue
            if (qty <= 0L || vlmYield <= 0L) continue
            val ocrYield = reading.yield_ ?: continue
            if (ocrYield == vlmYield || ocrYield !in 1L..qty) continue          // physics: 1 ≤ yield ≤ qty
            if (ocrYield !in confusableEdits(vlmYield)) continue                // one confusable-digit edit only
            val siblingRates = on.filter {
                it.rowIndex != good.rowIndex &&
                    foldMaterial(it.rawMaterialName) == foldMaterial(good.rawMaterialName)
            }.map { it.outputQuantity!!.toDouble() / it.inputQuantity!! }
            if (siblingRates.size < 2) continue                                // need a trustworthy rate witness
            val rate = median(siblingRates)
            if (rate <= 0.0) continue
            val implied = qty * rate
            if (implied <= 0.0) continue
            if (kotlin.math.abs(ocrYield - implied) / implied > YIELD_REPAIR_LANDING) continue   // OCR lands on the rate
            if (kotlin.math.abs(ocrYield - implied) >= kotlin.math.abs(vlmYield - implied)) continue // STRICTLY closer
            result[i] = ocrYield
        }
        return result
    }

    /** The outcome of the QUALITY majority vote: auto-corrected values + unresolved-disagreement rows. */
    data class QualityResolution(val corrected: Map<Int, Int>, val contested: Set<Int>)

    /**
     * 8b/4b/OCR MAJORITY on the QUALITY column — the one numeric column with no arithmetic anchor,
     * so a better READ (not a checksum) is the only recovery. For each row that has an OCR reading
     * ([OcrCrossCheck], gated so rows/builds with no OCR see ZERO behaviour change), the votes are
     * the primary VLM quality, the verify model's quality (when comparable) and OCR's quality:
     * - unanimous ⇒ nothing;
     * - a strict majority (≥ 2 of 3 agree, no top tie) that DIFFERS from the primary ⇒ corrected to
     *   it (the primary was outvoted); a majority that EQUALS the primary ⇒ confirmed, no flag;
     * - otherwise (no majority — three different values, or only two votes that differ) ⇒ contested.
     *
     * Conservative by construction: a lone OCR error (its ~1% rate on the golden set) is outvoted by
     * an agreeing 8b+4b and never reaches the export; it can only ever raise a review flag.
     */
    fun resolveQuality(
        goods: List<RefineryExtractGood>,
        secondaryRows: List<StitchedRow>,
        ocr: Map<Int, PanelOcr.RowReading>,
    ): QualityResolution {
        val corrected = mutableMapOf<Int, Int>()
        val contested = mutableSetOf<Int>()
        for ((i, reading) in ocr) {
            val good = goods.getOrNull(i) ?: continue
            val primary = good.quality ?: continue                   // no primary quality (INERT) ⇒ skip
            val ocrQuality = reading.quality?.toInt() ?: continue     // OCR read no quality here ⇒ skip
            val verify = secondaryRows.getOrNull(i)?.let { PanelValues.toQuality(it.quality) }
            val votes = listOfNotNull(primary, verify, ocrQuality)
            val counts = votes.groupingBy { it }.eachCount()
            if (counts.size == 1) continue                            // unanimous
            val maxCount = counts.values.max()
            val top = counts.filterValues { it == maxCount }.keys
            if (maxCount >= 2 && top.size == 1) {
                val winner = top.first()
                if (winner != primary) corrected[i] = winner          // primary outvoted ⇒ correct
                // winner == primary ⇒ majority confirms the primary, no flag
            } else {
                contested += i                                        // no majority ⇒ flag
            }
        }
        return QualityResolution(corrected, contested)
    }

    /**
     * Single-position CONFUSABLE-digit substitutions of [n] that preserve the digit length (a
     * leading digit may not become 0 — a length-collapsing "edit" like 608->8 is not a digit
     * mis-read and would only pad the candidate set, risking a legitimate repair's uniqueness).
     */
    internal fun confusableEdits(n: Long): List<Long> {
        val s = n.toString()
        val out = mutableListOf<Long>()
        for (i in s.indices) {
            if (s[i] !in CONFUSABLE_DIGITS) continue
            for (d in CONFUSABLE_DIGITS) {
                if (d == s[i]) continue
                if (i == 0 && d == '0') continue
                (s.substring(0, i) + d + s.substring(i + 1)).toLongOrNull()?.let { out += it }
            }
        }
        return out
    }

    fun validate(
        stitch: StitchResult,
        crossCheck: CrossModelVerify.Outcome? = null,
        ocr: Map<Int, PanelOcr.RowReading> = emptyMap(),
        /** [OcrCrossCheck]: the VLM TO_REFINE total is contested by OCR/verify — gate the checksum on it. */
        toRefineContested: Boolean = false,
        /** [OcrCrossCheck]: rows whose qty OCR read confusably differently (flagged on the OFF subset). */
        qtyOcrContested: Set<Int> = emptySet(),
    ): ValidatedOrder {
        val warnings = mutableSetOf<ExtractWarning>()
        val goods = mutableListOf<RefineryExtractGood>()

        stitch.rows.forEachIndexed { index, row ->
            val qty = PanelValues.toQuantity(row.qty)
            val quality = PanelValues.toQuality(row.quality)
            val yieldQty = PanelValues.toQuantity(row.yield_)
            val refineKnown = row.refine == "ON" || row.refine == "OFF"
            val refineFromToggle = if (refineKnown) row.refine == "ON" else true

            val refineFromYield = yieldRefineSignal(row)
            val refine = refineFromYield ?: refineFromToggle
            val refineCorrected = refineKnown && refineFromYield != null && refineFromYield != refineFromToggle

            // Plausibility: QTY and QUALITY must parse whenever the cell carried a value;
            // YIELD must parse when the cell carried a value other than the `--` marker.
            // YIELD > QTY is physically impossible (refining removes impurities, the output is
            // always less than the input) — a guaranteed digit misread in one of the two cells.
            val qtyImplausible = row.qty != null && qty == null
            val qualityImplausible = row.quality != null && quality == null
            // QUALITY is bounded 0..1000 (RefineryExtract contract). A value outside it is a digit
            // mis-read no reader can have produced legitimately (dropped/doubled digit, HUD bleed) —
            // flag it. Deterministic, needs no OCR/verify, so it also guards the rows resolveQuality
            // never reaches. NEVER auto-corrected: a digit-count repair is non-unique (5180 could be
            // 518 or 158) and quality has no arithmetic anchor to pick among candidates.
            val qualityOutOfRange = quality != null && quality !in 0..1000
            val yieldImplausible = row.yield_ != null && row.yield_ != "--" && yieldQty == null
            val yieldExceedsQty = yieldQty != null && qty != null && yieldQty > qty
            val implausible = qtyImplausible || qualityImplausible || qualityOutOfRange ||
                yieldImplausible || yieldExceedsQty

            var confidence = when {
                implausible -> CONFIDENCE_IMPLAUSIBLE
                !refineKnown -> CONFIDENCE_REFINE_UNREADABLE
                refineCorrected -> CONFIDENCE_REFINE_CORRECTED
                else -> CONFIDENCE_OK
            }
            // Cross-model verify (PHASE0 addendum 2026-06-12): the merge marks rows whose QTY the
            // checksum arbitration replaced and rows the two models read differently without a
            // deterministic arbiter — both cap the confidence (min: an implausible row stays 0.4).
            if (crossCheck != null) {
                if (index in crossCheck.corrected) {
                    confidence = minOf(confidence, CONFIDENCE_VERIFY_CORRECTED)
                    warnings += ExtractWarning.VERIFY_CORRECTED
                }
                if (index in crossCheck.contested) {
                    confidence = minOf(confidence, CONFIDENCE_VERIFY_CONTESTED)
                    warnings += ExtractWarning.VERIFY_MISMATCH
                }
            }
            // Consensus across overlapping captures: a cell the captures disagreed on is unsafe.
            if (row.contested) {
                confidence = minOf(confidence, CONFIDENCE_STITCH_CONTESTED)
                warnings += ExtractWarning.STITCH_CONTESTED
            }
            if (implausible) {
                warnings += ExtractWarning.IMPLAUSIBLE_CELL
            }
            if (refineCorrected) {
                warnings += ExtractWarning.REFINE_CORRECTED
            }

            goods += RefineryExtractGood(
                rowIndex = index,
                rawMaterialName = row.name,
                quality = quality,
                inputQuantity = qty,
                outputQuantity = yieldQty,
                refine = refine,
                confidence = confidence,
                sourceImage = row.sourceImage,
            )
        }

        // Yield/QTY ratio cross-check: the refinery method's per-material yield rate is constant
        // within an order, so a refine-ON row whose ratio diverges from its material's siblings is
        // a likely digit mis-read — cap its confidence for review. Catches gross errors a single
        // header checksum can miss (a yield mis-read, or a divergent 2-row material); subtle
        // ±1-digit flips that barely move the ratio are left to the contested-cell signal above.
        val ratioOutliers = yieldRatioOutliers(goods)
        if (ratioOutliers.isNotEmpty()) {
            warnings += ExtractWarning.YIELD_RATIO_OUTLIER
            for (i in goods.indices) {
                if (goods[i].rowIndex in ratioOutliers && goods[i].confidence > CONFIDENCE_YIELD_OUTLIER) {
                    goods[i] = goods[i].copy(confidence = CONFIDENCE_YIELD_OUTLIER)
                }
            }
        }

        // The load-bearing TO_REFINE anchor: contested when OCR ([OcrCrossCheck]) and/or the verify
        // model ([CrossModelVerify]) read a different total than the primary VLM. Every checksum
        // trusts this one number, so when it is suspect the qty checksum-repair ABSTAINS (it must not
        // repair a qty against a possibly-wrong total) and the order is flagged for review.
        val anchorContested = toRefineContested || (crossCheck?.headerToRefineContested ?: false)
        if (anchorContested) {
            warnings += ExtractWarning.TO_REFINE_CONTESTED
        }

        // Deterministic checksum repair: a unique confusable single-digit edit that lands Σ QTY(ON)
        // on TO REFINE and matches the row's yield is APPLIED — the export carries the corrected
        // value. Runs after the flags so it upgrades a contested/outlier cell to corrected, and
        // before the sum check below so a repaired order stops flagging SUM_MISMATCH. Abstains when
        // the TO_REFINE anchor itself is contested (do not repair a qty against a suspect total).
        val repair = if (anchorContested) emptyMap() else checksumRepair(goods, PanelValues.toQuantity(stitch.toRefine))
        if (repair.isNotEmpty()) {
            warnings += ExtractWarning.CHECKSUM_REPAIRED
            for (i in goods.indices) {
                repair[goods[i].rowIndex]?.let { fixed ->
                    goods[i] = goods[i].copy(inputQuantity = fixed, confidence = CONFIDENCE_CHECKSUM_REPAIRED)
                }
            }
        }

        // Deterministic yield repair: a refine-ON row whose READ yield grossly deviates from its
        // material's per-row rate, where a unique confusable digit edit lands back on the implied
        // yield (≤ qty), carries the corrected OUTPUT. Runs after the qty checksum repair so the
        // rate is taken from already-corrected sibling qtys. Recovers gross output mis-reads the
        // checksum cannot (yield is not in the TO-REFINE sum); within-noise flips are left flagged.
        val yieldFix = yieldRepair(goods)
        if (yieldFix.isNotEmpty()) {
            warnings += ExtractWarning.YIELD_REPAIRED
            for (i in goods.indices) {
                yieldFix[goods[i].rowIndex]?.let { fixed ->
                    goods[i] = goods[i].copy(outputQuantity = fixed, confidence = CONFIDENCE_YIELD_REPAIRED)
                }
            }
        }

        // OCR-witnessed yield correction: the WITHIN-NOISE flips yieldRepair abstained on (e.g.
        // STILERON 2720 -> rate-implied 2728), recovered from the classical-OCR read when its
        // confusable single-digit alternative is ≤ qty AND lands STRICTLY closer to the material
        // rate than the VLM value. Runs after yieldRepair (skips rows it already fixed); minOf so a
        // row already capped lower (ratio-outlier 0.6, stitch-contested 0.75) is never RAISED — the
        // warning still forces review. No-op when no OCR reading exists for the row.
        val ocrYieldFix = ocrYieldRepair(goods, ocr, yieldFix.keys)
        if (ocrYieldFix.isNotEmpty()) {
            warnings += ExtractWarning.YIELD_OCR_REPAIRED
            for (i in goods.indices) {
                ocrYieldFix[goods[i].rowIndex]?.let { fixed ->
                    goods[i] = goods[i].copy(
                        outputQuantity = fixed,
                        confidence = minOf(goods[i].confidence, CONFIDENCE_YIELD_REPAIRED),
                    )
                }
            }
        }

        // Classical-OCR third vote on the QUALITY column (the only numeric column with no
        // arithmetic anchor — the cross-model verify can only flag it). 8b/4b/OCR majority
        // auto-corrects a primary mis-read both other readers outvote; a no-majority disagreement
        // is flagged for review. Gated on rows that actually have an OCR reading, so a build/run
        // with no OCR models is byte-for-byte unchanged. Runs last so it overrides on the final
        // (already qty/yield-repaired) goods. Row indices here equal goods[i].rowIndex (== i).
        val qualityFix = resolveQuality(goods, crossCheck?.secondaryRows ?: emptyList(), ocr)
        if (qualityFix.corrected.isNotEmpty()) {
            warnings += ExtractWarning.OCR_CORRECTED
            for (i in goods.indices) {
                qualityFix.corrected[goods[i].rowIndex]?.let { fixed ->
                    goods[i] = goods[i].copy(
                        quality = fixed,
                        confidence = minOf(goods[i].confidence, CONFIDENCE_OCR_CORRECTED),
                    )
                }
            }
        }
        if (qualityFix.contested.isNotEmpty()) {
            warnings += ExtractWarning.OCR_CONTESTED
            for (i in goods.indices) {
                if (goods[i].rowIndex in qualityFix.contested) {
                    goods[i] = goods[i].copy(confidence = minOf(goods[i].confidence, CONFIDENCE_OCR_CONTESTED))
                }
            }
        }

        // OCR-witnessed QTY review flag for refine-OFF rows ([OcrCrossCheck.qtyContested]): an OFF
        // row's qty is in no checksum and (often) has no quality cell, so neither arithmetic nor the
        // quality majority can witness it — an OCR confusable disagreement is the only signal (e.g.
        // an INERT qty both VLMs read identically wrong). FLAG-ONLY: the value is NEVER changed (the
        // export keeps the VLM read); only the confidence is capped and the order flagged for review.
        val qtyContestedOff = goods.filter { it.rowIndex in qtyOcrContested && !it.refine }.map { it.rowIndex }.toSet()
        if (qtyContestedOff.isNotEmpty()) {
            warnings += ExtractWarning.QTY_OCR_CONTESTED
            for (i in goods.indices) {
                if (goods[i].rowIndex in qtyContestedOff) {
                    goods[i] = goods[i].copy(confidence = minOf(goods[i].confidence, CONFIDENCE_OCR_CONTESTED))
                }
            }
        }

        // Un-quoted order: no read saw the quoted state — every yield is `--` by definition.
        if (!stitch.quoted) {
            warnings += ExtractWarning.UNQUOTED_ORDER
        }

        // Cross-model verify ran but the row sets did not align (e.g. a ghost row in one read):
        // no cell comparison was possible — that itself is a disagreement worth a look.
        if (crossCheck != null && !crossCheck.comparable) {
            warnings += ExtractWarning.VERIFY_MISMATCH
        }

        // CTA cross-check: the button label is a redundant read of the quoted state — a
        // contradiction means one of the two header cells was misread.
        val ctaQuoted = stitch.cta?.uppercase()?.let { cta ->
            when {
                "CONFIRM" in cta -> true
                "QUOTE" in cta -> false
                else -> null
            }
        }
        if (ctaQuoted != null && ctaQuoted != stitch.quoted) {
            warnings += ExtractWarning.CTA_MISMATCH
        }

        // One-sided header checksum (PHASE0_FINDINGS §7): the visible refine-ON quantities may
        // legitimately fall SHORT of TO REFINE (scrolled-out rows), but can never exceed it
        // beyond the ±1-per-row display rounding.
        val toRefineTotal = PanelValues.toQuantity(stitch.toRefine)
        // Skip the sum check when the anchor itself is contested — TO_REFINE_CONTESTED already covers
        // the order, and a sum compared against a suspect total would only be a confusing false flag.
        if (!anchorContested && sumMismatch(goods, toRefineTotal)) {
            warnings += ExtractWarning.SUM_MISMATCH
        }

        var layout = if (goods.isEmpty()) 0.0 else goods.sumOf { it.confidence } / goods.size
        if (ExtractWarning.SUM_MISMATCH in warnings) {
            layout *= SUM_MISMATCH_DAMPENING
        }

        return ValidatedOrder(
            goods = goods,
            quoted = stitch.quoted,
            method = stitch.method,
            inManifestTotal = PanelValues.toQuantity(stitch.inManifest),
            toRefineTotal = toRefineTotal,
            expenses = PanelValues.toCost(stitch.totalCost),
            durationMinutes = PanelValues.toDurationMinutes(stitch.processingTime),
            layoutConfidence = layout,
            warnings = warnings,
        )
    }
}

/** Convenience: true when the order should carry the §5.3 re-capture warning. */
fun ValidatedOrder.isUnquoted(): Boolean = ExtractWarning.UNQUOTED_ORDER in warnings

/** Assemble the contract order from a validated order + image provenance (panelType = SETUP). */
fun ValidatedOrder.toContractOrder(
    sourceImages: List<com.basetool.bpextractor.refinery.model.RefineryExtractImage>,
    rawLocationName: String?,
): RefineryExtractOrder = RefineryExtractOrder(
    panelType = "SETUP",
    quoted = quoted,
    layoutConfidence = layoutConfidence,
    rawLocationName = rawLocationName,
    rawMethodName = method,
    rawInManifestTotal = inManifestTotal,
    rawToRefineTotal = toRefineTotal,
    expenses = expenses,
    durationMinutes = durationMinutes,
    totalYieldScu = null,
    sourceImages = sourceImages,
    goods = goods,
)
