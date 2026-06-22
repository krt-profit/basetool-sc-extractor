package com.basetool.bpextractor.refinery

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * Classical-OCR cross-reader for the refinery SETUP panel: [TextDetector] finds the text boxes,
 * [DigitOcr] reads the numeric ones, and the boxes are clustered back into the table's rows
 * (top→bottom) and columns (left→right). It is a DECORRELATED second opinion on the numeric cells
 * the VLM mis-reads — never a standalone reader (it has no understanding of names, toggles or the
 * quoted state). The pipeline pairs each cell with the VLM's structured read and resolves
 * disagreements deterministically (qty checksum, yield rate, or model/OCR majority on quality).
 *
 * Both ONNX sessions are heavyweight — construct once and reuse; [close] releases them.
 */
class PanelOcr private constructor(
    private val detector: TextDetector,
    private val reader: DigitOcr,
) : AutoCloseable {

    /** Load both models from files on disk (smoke harnesses / dev). */
    constructor(detModel: Path, recModel: Path) : this(TextDetector(detModel), DigitOcr(recModel))

    /** Load both models from in-memory bytes (the bundled classpath resources). */
    constructor(detBytes: ByteArray, recBytes: ByteArray) : this(TextDetector(detBytes), DigitOcr(recBytes))

    /** One recognized numeric cell: its detected box and the digits read from it. */
    data class NumCell(val box: TextDetector.Box, val digits: String) {
        val value: Long get() = digits.toLong()
        val cx: Int get() = box.cx
    }

    /** One table row's numeric columns as read by OCR; any column may be absent. */
    data class RowReading(val quality: Long?, val qty: Long?, val yield_: Long?)

    /** Detect + recognize every numeric cell in the panel (unordered). */
    private fun numericCells(panel: BufferedImage): List<NumCell> =
        detector.detect(panel).mapNotNull { box ->
            val digits = reader.readNumber(crop(panel, box))
            if (digits.isEmpty()) null else NumCell(box, digits)
        }

    /**
     * Read the panel into rows of numeric cells (top→bottom rows, left→right cells) — the raw grid
     * used by the smoke harness. Boxes whose recognition yields no digits are dropped.
     */
    fun readNumericGrid(panel: BufferedImage): List<List<NumCell>> =
        clusterRows(numericCells(panel)).map { row -> row.sortedBy { it.box.x0 } }

    /**
     * Read the panel into per-row QUALITY/QTY/YIELD readings. The three data columns are found from
     * the cells themselves: among rows with ≥ 2 cells, cell x-centres cluster into columns by large
     * gaps, and the three most-populated clusters (left→right = QUALITY, QTY, YIELD) are the data
     * columns — this isolates them from the header totals (IN MANIFEST / TO REFINE, different x) and
     * the single-cell title/total/balance rows. A row is a data row when it carries QUALITY and QTY.
     * Returns one [RowReading] per data row, top→bottom; the caller matches them to the VLM rows by
     * the QTY anchor (OCR's most reliable column), so a missing/extra row never shifts the others.
     */
    fun readRows(panel: BufferedImage): List<RowReading> {
        val rows = clusterRows(numericCells(panel))
        val cols = dataColumns(rows) ?: return emptyList()
        val out = mutableListOf<RowReading>()
        for (row in rows) {
            val assign = arrayOfNulls<Long>(3)
            for (cell in row) {
                val ci = (0..2).minByOrNull { abs(cell.cx - cols[it]) }!!
                if (abs(cell.cx - cols[ci]) < COL_TOLERANCE && cell.digits.toLongOrNull() != null) {
                    assign[ci] = cell.value
                }
            }
            if (assign[0] != null && assign[1] != null) {
                out += RowReading(quality = assign[0], qty = assign[1], yield_ = assign[2])
            }
        }
        return out
    }

    /**
     * The three data-column x-centres (sorted L→R) or null when the panel has too few cells. Cell
     * centres from multi-cell rows are clustered by gaps > [COL_GAP]; the three largest clusters by
     * membership are the data columns (the header's two stray cells form low-membership clusters).
     */
    private fun dataColumns(rows: List<List<NumCell>>): List<Int>? {
        val xs = rows.filter { it.size >= 2 }.flatten().map { it.cx }.sorted()
        if (xs.size < 3) return null
        val clusters = mutableListOf(mutableListOf(xs.first()))
        for (x in xs.drop(1)) {
            if (x - clusters.last().last() > COL_GAP) clusters += mutableListOf(x)
            else clusters.last() += x
        }
        if (clusters.size < 3) return null
        return clusters.sortedByDescending { it.size }.take(3)
            .map { it.average().toInt() }
            .sorted()
    }

    /**
     * Cluster cells into table rows by vertical overlap: sorted top→bottom, a cell joins the
     * current row while its box overlaps that row's y-span by more than [ROW_OVERLAP] of its own
     * height, otherwise it starts a new row.
     */
    private fun clusterRows(cells: List<NumCell>): List<List<NumCell>> {
        if (cells.isEmpty()) return emptyList()
        val sorted = cells.sortedBy { it.box.cy }
        val rows = mutableListOf<MutableList<NumCell>>()
        var top = sorted.first().box.y0
        var bot = sorted.first().box.y1
        var current = mutableListOf(sorted.first())
        for (cell in sorted.drop(1)) {
            val overlap = minOf(bot, cell.box.y1) - maxOf(top, cell.box.y0)
            if (overlap > ROW_OVERLAP * cell.box.height) {
                current += cell
                top = minOf(top, cell.box.y0)
                bot = maxOf(bot, cell.box.y1)
            } else {
                rows += current
                current = mutableListOf(cell)
                top = cell.box.y0
                bot = cell.box.y1
            }
        }
        rows += current
        return rows.map { row -> row.sortedBy { it.box.x0 } }
    }

    private fun crop(img: BufferedImage, box: TextDetector.Box): BufferedImage {
        val x = box.x0.coerceIn(0, img.width - 1)
        val y = box.y0.coerceIn(0, img.height - 1)
        val w = box.width.coerceIn(1, img.width - x)
        val h = box.height.coerceIn(1, img.height - y)
        return img.getSubimage(x, y, w, h)
    }

    override fun close() {
        detector.close()
        reader.close()
    }

    companion object {
        /** Min fraction of a cell's height that must overlap a row's y-span to join it. */
        private const val ROW_OVERLAP = 0.4

        /** Max x-gap (px) within one column cluster before a new column starts. */
        private const val COL_GAP = 40

        /** Max distance (px) from a column centre for a cell to be assigned to that column. */
        private const val COL_TOLERANCE = 45

        /** Load from two model files on disk; null when either is missing. */
        fun fromFiles(detModel: Path, recModel: Path): PanelOcr? =
            if (Files.isRegularFile(detModel) && Files.isRegularFile(recModel)) {
                PanelOcr(detModel, recModel)
            } else {
                null
            }
    }
}
