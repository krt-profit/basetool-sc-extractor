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
 *    so the backend drafts the row and the review forces a look;
 * 3. the one-sided header checksum (verified semantics: TO REFINE = Σ QTY of refine-ON rows,
 *    ±1 display rounding per row; the list is a ~6-row scrolling viewport, so only
 *    "visible Σ EXCEEDS the header" is flaggable);
 * 4. the model's verbalized self-confidence is never used.
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

    /** Dampening factor on layout confidence when the header checksum flags. */
    private const val SUM_MISMATCH_DAMPENING = 0.9

    fun validate(stitch: StitchResult): ValidatedOrder {
        val warnings = mutableSetOf<ExtractWarning>()
        val goods = mutableListOf<RefineryExtractGood>()

        stitch.rows.forEachIndexed { index, row ->
            val qty = PanelValues.toQuantity(row.qty)
            val quality = PanelValues.toQuality(row.quality)
            val yieldQty = PanelValues.toQuantity(row.yield_)
            val refineKnown = row.refine == "ON" || row.refine == "OFF"
            val refine = if (refineKnown) row.refine == "ON" else true

            // Plausibility: QTY and QUALITY must parse whenever the cell carried a value;
            // YIELD must parse when the order is quoted and the cell wasn't `--`.
            val qtyImplausible = row.qty != null && qty == null
            val qualityImplausible = row.quality != null && quality == null
            val yieldImplausible = row.yield_ != null && yieldQty == null
            val implausible = qtyImplausible || qualityImplausible || yieldImplausible

            val confidence = when {
                implausible -> CONFIDENCE_IMPLAUSIBLE
                !refineKnown -> CONFIDENCE_REFINE_UNREADABLE
                else -> CONFIDENCE_OK
            }
            if (implausible) {
                warnings += ExtractWarning.IMPLAUSIBLE_CELL
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

        // Un-quoted order: no read saw the quoted state — every yield is `--` by definition.
        if (!stitch.quoted) {
            warnings += ExtractWarning.UNQUOTED_ORDER
        }

        // One-sided header checksum (PHASE0_FINDINGS §7): the visible refine-ON quantities may
        // legitimately fall SHORT of TO REFINE (scrolled-out rows), but can never exceed it
        // beyond the ±1-per-row display rounding.
        val toRefineTotal = PanelValues.toQuantity(stitch.toRefine)
        if (toRefineTotal != null) {
            val visibleOn = goods.filter { it.refine }.mapNotNull { it.inputQuantity }
            val sum = visibleOn.sum()
            val tolerance = goods.size.toLong()
            val anyRowExceeds = visibleOn.any { it > toRefineTotal + 1 }
            if (sum > toRefineTotal + tolerance || anyRowExceeds) {
                warnings += ExtractWarning.SUM_MISMATCH
            }
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
