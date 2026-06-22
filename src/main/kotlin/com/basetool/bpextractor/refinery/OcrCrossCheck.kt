package com.basetool.bpextractor.refinery

import java.awt.image.BufferedImage

/**
 * Fuses the classical-OCR read ([PanelOcr]) with the stitched VLM rows into the signals [Validation]
 * consumes. Three products, all computed from a single detect+recognize pass per source panel:
 *
 * 1. [Result.readings] — per stitched row, the OCR reading of the SAME physical row, aligned by an
 *    exact QTY anchor (OCR's most reliable column). Feeds the QUALITY majority and the OCR-witnessed
 *    yield repair. Self-correcting: a mis-read qty simply yields no reading and the VLM result stands.
 * 2. [Result.qtyContested] — rows whose VLM qty is ABSENT from the OCR qty column but a single
 *    CONFUSABLE-digit edit of it IS present (and is not another VLM row's qty). This is the only
 *    signal that can surface an OFF-row qty mis-read: OFF rows have no checksum and (often) no quality
 *    cell, so the qty anchor in (1) cannot bind them — but their qty is still a real qty-column value.
 *    FLAG-ONLY (the owner rejected auto-correcting an OFF-row qty from OCR over an agreeing VLM pair).
 * 3. [Result.toRefineContested] — the load-bearing TO_REFINE anchor is a single VLM read every
 *    checksum trusts; when the VLM total is absent from EVERY panel's OCR numbers but a confusable
 *    edit of it is present, the anchor is suspect. [Validation] then flags it and makes the qty
 *    checksum-repair abstain rather than repair a qty against a possibly-wrong total.
 *
 * All checks are gated on a CONFUSABLE single-digit edit ([Validation.confusableEdits]) so OCR's own
 * noise/garble can only ever raise a review flag, never silently change a value.
 */
object OcrCrossCheck {

    /** The fused signals; the latter two are flag-only. */
    data class Result(
        val readings: Map<Int, PanelOcr.RowReading>,
        val qtyContested: Set<Int>,
        val toRefineContested: Boolean,
    )

    fun read(
        rows: List<StitchedRow>,
        panels: Map<String, BufferedImage>,
        ocr: PanelOcr,
        toRefine: Long?,
    ): Result {
        val cache = HashMap<String, PanelOcr.PanelNumbers>()
        val readings = HashMap<Int, PanelOcr.RowReading>()
        val qtyContested = mutableSetOf<Int>()
        val allVlmQtys = rows.mapNotNull { PanelValues.toQuantity(it.qty) }

        rows.forEachIndexed { i, row ->
            val panel = panels[row.sourceImage] ?: return@forEachIndexed
            val pn = cache.getOrPut(row.sourceImage) { ocr.readPanel(panel) }
            val qty = PanelValues.toQuantity(row.qty) ?: return@forEachIndexed

            // (1) qty-anchored row match for the quality/yield resolvers (unchanged behaviour).
            val matches = pn.rows.filter { it.qty == qty }
            if (matches.size == 1) readings[i] = matches.first()

            // (2) the VLM qty is absent from the OCR qty column, but a confusable single-digit edit
            // of it is present and is NOT some other VLM row's qty (so it is not just a sibling row
            // OCR happened to read) — OCR saw a genuinely different value for this cell.
            if (qty !in pn.qtyColumn) {
                val others = allVlmQtys.filterIndexed { j, _ -> j != i }.toSet()
                if (Validation.confusableEdits(qty).any { it in pn.qtyColumn && it !in others }) {
                    qtyContested += i
                }
            }
        }

        // (3) TO_REFINE anchor cross-check: contested when the VLM total is in NO panel's numbers but
        // a confusable edit of it appears in some panel — strong evidence the header digit was misread.
        val toRefineContested = toRefine != null &&
            cache.values.none { toRefine in it.allNumbers } &&
            Validation.confusableEdits(toRefine).any { edit -> cache.values.any { edit in it.allNumbers } }

        return Result(readings, qtyContested, toRefineContested)
    }
}
