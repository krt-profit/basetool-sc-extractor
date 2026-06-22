package com.basetool.bpextractor.refinery

import java.awt.image.BufferedImage

/**
 * Pairs each stitched VLM row with the classical-OCR ([PanelOcr]) reading of the SAME physical row,
 * so [Validation] can run a third (decorrelated) vote on the numeric cells — above all QUALITY,
 * which the VLM cross-check can only flag, never resolve (it has no arithmetic anchor).
 *
 * Alignment is VALUE-anchored, not positional: a stitched row is matched to the OCR row of its
 * source capture whose QTY equals the stitched QTY (OCR's most reliable column). That sidesteps the
 * stitcher's cross-image row mapping entirely and is self-correcting — if the QTY itself is mis-read
 * or the match is ambiguous, the row simply gets no OCR reading and the VLM result stands (no
 * regression). Each capture is OCR'd at most once and the reading cached.
 */
object OcrCrossCheck {

    /** rowIndex (into [rows]) → the OCR reading of that row, for rows that matched uniquely by QTY. */
    fun read(
        rows: List<StitchedRow>,
        panels: Map<String, BufferedImage>,
        ocr: PanelOcr,
    ): Map<Int, PanelOcr.RowReading> {
        val cache = HashMap<String, List<PanelOcr.RowReading>>()
        val result = HashMap<Int, PanelOcr.RowReading>()
        rows.forEachIndexed { i, row ->
            val panel = panels[row.sourceImage] ?: return@forEachIndexed
            val qty = PanelValues.toQuantity(row.qty) ?: return@forEachIndexed
            val ocrRows = cache.getOrPut(row.sourceImage) { ocr.readRows(panel) }
            val matches = ocrRows.filter { it.qty == qty }
            if (matches.size == 1) result[i] = matches.first()
        }
        return result
    }
}
