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
     * Single-position CONFUSABLE-digit substitutions of [n] that preserve the digit length (a
     * leading digit may not become 0 — a length-collapsing "edit" like 608->8 is not a digit
     * mis-read and would only pad the candidate set, risking a legitimate repair's uniqueness).
     */
    private fun confusableEdits(n: Long): List<Long> {
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

    fun validate(stitch: StitchResult, crossCheck: CrossModelVerify.Outcome? = null): ValidatedOrder {
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
            val yieldImplausible = row.yield_ != null && row.yield_ != "--" && yieldQty == null
            val yieldExceedsQty = yieldQty != null && qty != null && yieldQty > qty
            val implausible = qtyImplausible || qualityImplausible || yieldImplausible || yieldExceedsQty

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

        // Deterministic checksum repair: a unique confusable single-digit edit that lands Σ QTY(ON)
        // on TO REFINE and matches the row's yield is APPLIED — the export carries the corrected
        // value. Runs after the flags so it upgrades a contested/outlier cell to corrected, and
        // before the sum check below so a repaired order stops flagging SUM_MISMATCH.
        val repair = checksumRepair(goods, PanelValues.toQuantity(stitch.toRefine))
        if (repair.isNotEmpty()) {
            warnings += ExtractWarning.CHECKSUM_REPAIRED
            for (i in goods.indices) {
                repair[goods[i].rowIndex]?.let { fixed ->
                    goods[i] = goods[i].copy(inputQuantity = fixed, confidence = CONFIDENCE_CHECKSUM_REPAIRED)
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
        if (sumMismatch(goods, toRefineTotal)) {
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
