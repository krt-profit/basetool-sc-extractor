package com.basetool.bpextractor.refinery

import java.awt.image.BufferedImage

/**
 * Fuses the classical-OCR read ([PanelOcr]) with the stitched VLM rows into the signals [Validation]
 * consumes, from one detect-and-recognize pass per source panel:
 *
 * 1. [Result.readings]: per stitched row, the OCR reading of the same row, aligned by an exact QTY
 *    anchor.
 * 2. [Result.qtyContested]: rows whose VLM qty is absent from the OCR qty column while a single
 *    confusable-digit edit of it is present; flag-only.
 * 3. [Result.toRefineContested]: set when the VLM TO REFINE total is absent from every panel's OCR
 *    numbers but a confusable edit of it is present.
 *
 * 4. [Result.glyphVeto]: whether a stitched row's cell visibly shows a value a repair would replace
 *    ([GlyphTopology]).
 *
 * Every check requires a confusable single-digit edit ([Validation.confusableEdits]), so OCR noise
 * can only raise a review flag or hold a repair back, never change a value.
 */
object OcrCrossCheck {

    /** Holds back a single-digit repair of a row's cell whose glyphs clearly show the unrepaired value. */
    fun interface GlyphVeto {
        /** Whether row [rowIndex]'s cell reading [from] visibly shows [from] rather than the repair [to]. */
        fun contradicts(rowIndex: Int, from: Long, to: Long): Boolean

        companion object {
            /** Never vetoes. */
            val NONE = GlyphVeto { _, _, _ -> false }
        }
    }

    /** The fused signals; all but [readings] are flag-only or hold a repair back. */
    data class Result(
        val readings: Map<Int, PanelOcr.RowReading>,
        val qtyContested: Set<Int>,
        val toRefineContested: Boolean,
        val glyphVeto: GlyphVeto = GlyphVeto.NONE,
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

            val matches = pn.rows.filter { it.qty == qty }
            if (matches.size == 1) readings[i] = matches.first()

            if (qty !in pn.qtyColumn) {
                val others = allVlmQtys.filterIndexed { j, _ -> j != i }.toSet()
                if (Validation.confusableEdits(qty).any { it in pn.qtyColumn && it !in others }) {
                    qtyContested += i
                }
            }
        }

        val toRefineContested = toRefine != null &&
            cache.values.none { toRefine in it.allNumbers } &&
            Validation.confusableEdits(toRefine).any { edit -> cache.values.any { edit in it.allNumbers } }

        val glyphVeto = GlyphVeto { rowIndex, from, to ->
            val row = rows.getOrNull(rowIndex) ?: return@GlyphVeto false
            val panel = panels[row.sourceImage] ?: return@GlyphVeto false
            val numbers = cache[row.sourceImage] ?: return@GlyphVeto false
            val cell = numbers.cells.filter { it.digits.toLongOrNull() in setOf(from, to) }.singleOrNull()
                ?: return@GlyphVeto false
            GlyphTopology.arbitrate(ocr.cellImage(panel, cell), from.toString(), to.toString()) == from.toString()
        }

        return Result(readings, qtyContested, toRefineContested, glyphVeto)
    }
}
