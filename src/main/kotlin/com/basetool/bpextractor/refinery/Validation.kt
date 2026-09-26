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
     * A REFINE toggle read contradicted the YIELD column of a quoted order and was corrected; the yield
     * cell is the reliable signal (`--` is OFF, > 0 is ON).
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
     * A QTY digit was corrected by [Validation.checksumRepair]: a unique confusable single-digit edit
     * lands the ON-row QTY sum on the TO REFINE header and matches the row's YIELD via the material's
     * rate.
     */
    CHECKSUM_REPAIRED,

    /**
     * A YIELD digit was corrected by [Validation.yieldRepair]: a unique confusable single-digit edit of a
     * grossly deviating refine-ON yield lands back on the material's rate-implied yield while staying
     * ≤ qty.
     */
    YIELD_REPAIRED,

    /**
     * A YIELD cell was corrected by the OCR cross-reader ([Validation.ocrYieldRepair]): a confusable
     * single-digit alternative that is ≤ qty and strictly closer to the material's rate-implied yield
     * than the VLM read.
     */
    YIELD_OCR_REPAIRED,

    /**
     * A QUALITY cell was corrected by primary/verify/OCR majority ([Validation.resolveQuality]); needs
     * the verify model for a third vote.
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
     * A refine-OFF row's QTY disagrees with the OCR read ([OcrCrossCheck]); flag-only, the export keeps
     * the VLM value.
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
 * Turns a [StitchResult] into contract-ready goods with per-row confidence derived from deterministic
 * validation:
 *
 * 1. QTY, QUALITY and YIELD must parse as numbers (or be `--`), else [CONFIDENCE_IMPLAUSIBLE];
 * 2. the REFINE toggle must read `ON` or `OFF`, else ON at low confidence; in a quoted order the
 *    YIELD column overrides the toggle (`--` is OFF, > 0 is ON) at [CONFIDENCE_REFINE_CORRECTED];
 * 3. the one-sided header checksum flags a visible ON-row QTY sum exceeding TO REFINE;
 * 4. the optional cross-model verify ([CrossModelVerify]) caps rows at [CONFIDENCE_VERIFY_CORRECTED]
 *    or [CONFIDENCE_VERIFY_CONTESTED];
 * 5. the model's self-reported confidence is never used.
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
     * The YIELD column's refine signal for a row whose surviving read saw the quoted state: a positive
     * yield is ON, `--` is OFF. `null` for an un-quoted read or a yield of exactly 0.
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
     * Refine-ON rows whose YIELD/QTY ratio deviates from the leave-one-out median of the same material's
     * other rows, a likely digit misread. Needs ≥ 2 rows of the material with positive QTY and YIELD.
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
     * Deterministic single-digit QTY repair from the TO REFINE checksum and the per-material yield rate,
     * firing only when the ON-row QTY sum exceeds the header. A candidate is one confusable-digit edit of
     * one ON row's QTY that lands the sum inside the ±1-per-row band, keeps YIELD ≤ QTY, and is predicted
     * by the row's own YIELD via the material's leave-one-out rate. Returns rowIndex to corrected QTY
     * when exactly one candidate survives, else an empty map.
     */
    fun checksumRepair(goods: List<RefineryExtractGood>, toRefineTotal: Long?): Map<Int, Long> {
        if (toRefineTotal == null) return emptyMap()
        val on = goods.filter { it.refine && it.inputQuantity != null }
        val sumOn = on.sumOf { it.inputQuantity!! }
        val tol = goods.size.toLong()
        if (sumOn <= toRefineTotal + tol) return emptyMap()
        val candidates = mutableSetOf<Pair<Int, Long>>()
        for (row in on) {
            val qty = row.inputQuantity!!
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
                if (newQty < yieldQ) continue
                val newSum = sumOn - qty + newQty
                if (newSum < toRefineTotal - tol || newSum > toRefineTotal + tol) continue
                if (kotlin.math.abs(newQty - implied) / implied > REPAIR_YIELD_TOLERANCE) continue
                candidates += row.rowIndex to newQty
            }
        }
        return if (candidates.size == 1) mapOf(candidates.first().first to candidates.first().second) else emptyMap()
    }

    /**
     * Deterministic single-digit YIELD repair from the per-material yield rate. A refine-ON row whose
     * yield deviates beyond [YIELD_REPAIR_TRIGGER] from its leave-one-out rate is corrected when a unique
     * confusable single-digit edit lands within [YIELD_REPAIR_LANDING] of the implied yield and stays
     * ≤ qty. Needs ≥ 2 same-material siblings; returns rowIndex to corrected yield.
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
            if (siblingRates.size < 2) continue
            val rate = median(siblingRates)
            if (rate <= 0.0) continue
            val implied = qty * rate
            if (implied <= 0.0) continue
            if (kotlin.math.abs(yieldQ - implied) <= maxOf(2.0, implied * YIELD_REPAIR_TRIGGER)) continue
            val candidates = confusableEdits(yieldQ).filter { cand ->
                cand in 1..qty && kotlin.math.abs(cand - implied) / implied <= YIELD_REPAIR_LANDING
            }.toSet()
            if (candidates.size == 1) result[row.rowIndex] = candidates.first()
        }
        return result
    }

    /**
     * OCR-witnessed single-digit YIELD correction for rows [yieldRepair] abstains on. A refine-ON row's
     * VLM yield is replaced by the OCR yield when that is a single confusable-digit edit of it, ≤ qty,
     * and within [YIELD_REPAIR_LANDING] of and strictly closer to the leave-one-out rate-implied yield.
     * Rows in [alreadyFixed] and rows without an OCR reading are skipped.
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
            if (ocrYield == vlmYield || ocrYield !in 1L..qty) continue
            if (ocrYield !in confusableEdits(vlmYield)) continue
            val siblingRates = on.filter {
                it.rowIndex != good.rowIndex &&
                    foldMaterial(it.rawMaterialName) == foldMaterial(good.rawMaterialName)
            }.map { it.outputQuantity!!.toDouble() / it.inputQuantity!! }
            if (siblingRates.size < 2) continue
            val rate = median(siblingRates)
            if (rate <= 0.0) continue
            val implied = qty * rate
            if (implied <= 0.0) continue
            if (kotlin.math.abs(ocrYield - implied) / implied > YIELD_REPAIR_LANDING) continue
            if (kotlin.math.abs(ocrYield - implied) >= kotlin.math.abs(vlmYield - implied)) continue
            result[i] = ocrYield
        }
        return result
    }

    /** The outcome of the QUALITY majority vote: auto-corrected values + unresolved-disagreement rows. */
    data class QualityResolution(val corrected: Map<Int, Int>, val contested: Set<Int>)

    /**
     * Majority vote of primary VLM, verify model and OCR on the QUALITY column, for rows with an OCR
     * reading ([OcrCrossCheck]). A strict majority differing from the primary corrects it, one equal to
     * the primary confirms it, and no majority marks the row contested.
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
            val primary = good.quality ?: continue
            val ocrQuality = reading.quality?.toInt() ?: continue
            val verify = secondaryRows.getOrNull(i)?.let { PanelValues.toQuality(it.quality) }
            val votes = listOfNotNull(primary, verify, ocrQuality)
            val counts = votes.groupingBy { it }.eachCount()
            if (counts.size == 1) continue
            val maxCount = counts.values.max()
            val top = counts.filterValues { it == maxCount }.keys
            if (maxCount >= 2 && top.size == 1) {
                val winner = top.first()
                if (winner != primary) corrected[i] = winner
            } else {
                contested += i
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

            val qtyImplausible = row.qty != null && qty == null
            val qualityImplausible = row.quality != null && quality == null
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

        val ratioOutliers = yieldRatioOutliers(goods)
        if (ratioOutliers.isNotEmpty()) {
            warnings += ExtractWarning.YIELD_RATIO_OUTLIER
            for (i in goods.indices) {
                if (goods[i].rowIndex in ratioOutliers && goods[i].confidence > CONFIDENCE_YIELD_OUTLIER) {
                    goods[i] = goods[i].copy(confidence = CONFIDENCE_YIELD_OUTLIER)
                }
            }
        }

        val anchorContested = toRefineContested || (crossCheck?.headerToRefineContested ?: false)
        if (anchorContested) {
            warnings += ExtractWarning.TO_REFINE_CONTESTED
        }

        val repair = if (anchorContested) emptyMap() else checksumRepair(goods, PanelValues.toQuantity(stitch.toRefine))
        if (repair.isNotEmpty()) {
            warnings += ExtractWarning.CHECKSUM_REPAIRED
            for (i in goods.indices) {
                repair[goods[i].rowIndex]?.let { fixed ->
                    goods[i] = goods[i].copy(inputQuantity = fixed, confidence = CONFIDENCE_CHECKSUM_REPAIRED)
                }
            }
        }

        val yieldFix = yieldRepair(goods)
        if (yieldFix.isNotEmpty()) {
            warnings += ExtractWarning.YIELD_REPAIRED
            for (i in goods.indices) {
                yieldFix[goods[i].rowIndex]?.let { fixed ->
                    goods[i] = goods[i].copy(outputQuantity = fixed, confidence = CONFIDENCE_YIELD_REPAIRED)
                }
            }
        }

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

        val qtyContestedOff = goods.filter { it.rowIndex in qtyOcrContested && !it.refine }.map { it.rowIndex }.toSet()
        if (qtyContestedOff.isNotEmpty()) {
            warnings += ExtractWarning.QTY_OCR_CONTESTED
            for (i in goods.indices) {
                if (goods[i].rowIndex in qtyContestedOff) {
                    goods[i] = goods[i].copy(confidence = minOf(goods[i].confidence, CONFIDENCE_OCR_CONTESTED))
                }
            }
        }

        if (!stitch.quoted) {
            warnings += ExtractWarning.UNQUOTED_ORDER
        }

        if (crossCheck != null && !crossCheck.comparable) {
            warnings += ExtractWarning.VERIFY_MISMATCH
        }

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

        val toRefineTotal = PanelValues.toQuantity(stitch.toRefine)
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
