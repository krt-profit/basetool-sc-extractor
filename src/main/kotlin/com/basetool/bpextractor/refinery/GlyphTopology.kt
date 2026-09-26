package com.basetool.bpextractor.refinery

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max

/**
 * Tells the HUD digits 0, 6, 8 and 9 apart by the holes their strokes enclose — a reader that shares
 * no model with the VLM or the CRNN.
 *
 * The HUD font draws a slashed zero, so a 0 encloses two diagonally offset holes, an 8 two stacked
 * ones, a 6 one low and a 9 one high. The reader only ever arbitrates between those four digits and
 * abstains whenever the shape is not clear.
 */
object GlyphTopology {

    /** One segmented glyph: its box in the scaled cell and the holes it encloses. */
    data class Glyph(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val holes: List<Hole>) {
        val width: Int get() = x1 - x0 + 1
        val height: Int get() = y1 - y0 + 1
    }

    /** One enclosed background region, with its area share and centroid relative to the glyph box (0..1). */
    data class Hole(val areaShare: Double, val cx: Double, val cy: Double, val heightShare: Double)

    /** Upscaling applied before thresholding, so one-pixel counters survive as regions. */
    private const val SCALE = 4

    /** A foreground component smaller than this share of the cell's largest one is noise. */
    private const val MIN_COMPONENT_SHARE = 0.12

    /** A hole smaller than this share of its glyph's box area is noise. */
    private const val MIN_HOLE_SHARE = 0.012

    /** Two holes at least this far apart horizontally, as a share of the glyph width, are a slashed 0's. */
    private const val DIAGONAL_DX = 0.12

    /** Two holes at most this far apart horizontally are stacked, as in 8 or a blurred 6 or 9. */
    private const val STACKED_DX = 0.08

    /** Stacked holes whose smaller one has at least this share of the larger one's area are an 8's. */
    private const val EIGHT_AREA_RATIO = 0.65

    /** Stacked holes below this area ratio are a 6 or 9 whose open hook blurred shut. */
    private const val HOOK_AREA_RATIO = 0.50

    /** A lone hole smaller than this share of the glyph box is too small to place. */
    private const val MIN_LONE_HOLE_SHARE = 0.045

    /** A lone hole centred above this height share is a 9's, below the other a 6's. */
    private const val HIGH_HOLE = 0.44
    private const val LOW_HOLE = 0.56

    /**
     * Segments a single-line numeric [cell] into its glyphs, left to right, or `null` when it cannot
     * be segmented cleanly.
     */
    fun glyphs(cell: BufferedImage): List<Glyph>? {
        if (cell.width < 2 || cell.height < 2) return null
        val w = cell.width * SCALE
        val h = cell.height * SCALE
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(cell, 0, 0, w, h, null)
            dispose()
        }
        val lum = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = scaled.getRGB(x, y)
                lum[y * w + x] = max((rgb shr 16) and 0xFF, max((rgb shr 8) and 0xFF, rgb and 0xFF))
            }
        }
        val threshold = otsu(lum)
        val fg = BooleanArray(w * h) { lum[it] > threshold }
        val components = components(fg, w, h)
        if (components.isEmpty()) return null
        val largest = components.maxOf { it.size }
        val kept = components.filter { it.size >= MIN_COMPONENT_SHARE * largest }
        val columns = mergeByColumn(kept.map { bounds(it, w) }.sortedBy { it[0] })
        return columns.map { box -> glyph(fg, w, box) }
    }

    /**
     * The digit among 0, 6, 8 and 9 that [glyph]'s holes describe, or `null` when they fit none of them
     * clearly.
     */
    fun classify(glyph: Glyph): Char? {
        val holes = glyph.holes
        return when (holes.size) {
            2 -> {
                val (upper, lower) = holes.sortedBy { it.cy }
                val dx = abs(upper.cx - lower.cx)
                val ratio = minOf(upper.areaShare, lower.areaShare) / maxOf(upper.areaShare, lower.areaShare)
                when {
                    dx >= DIAGONAL_DX -> '0'
                    dx > STACKED_DX -> null
                    ratio >= EIGHT_AREA_RATIO -> '8'
                    ratio <= HOOK_AREA_RATIO -> if (lower.areaShare > upper.areaShare) '6' else '9'
                    else -> null
                }
            }
            1 -> {
                val hole = holes.single()
                when {
                    hole.areaShare < MIN_LONE_HOLE_SHARE -> null
                    hole.cy < HIGH_HOLE -> '9'
                    hole.cy > LOW_HOLE -> '6'
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Reads [cell] as the digits of [expectedLength], one classified character per glyph (`?` for a
     * glyph outside 0/6/8/9 or unclear), or `null` when the cell does not split into that many glyphs.
     */
    fun read(cell: BufferedImage, expectedLength: Int): String? {
        val glyphs = glyphs(cell) ?: return null
        if (glyphs.size != expectedLength) return null
        return glyphs.joinToString("") { (classify(it) ?: '?').toString() }
    }

    /**
     * Arbitrates between two readings of the same cell that differ in exactly one position, both
     * digits in 0/6/8/9: returns the one the glyph there matches, or `null` to abstain.
     */
    fun arbitrate(cell: BufferedImage, a: String, b: String): String? {
        if (a.length != b.length) return null
        val diff = a.indices.filter { a[it] != b[it] }
        if (diff.size != 1) return null
        val i = diff.single()
        if (a[i] !in CONFUSABLE || b[i] !in CONFUSABLE) return null
        val read = read(cell, a.length) ?: return null
        return when (read[i]) {
            a[i] -> a
            b[i] -> b
            else -> null
        }
    }

    private val CONFUSABLE = setOf('0', '6', '8', '9')

    private fun glyph(fg: BooleanArray, w: Int, box: IntArray): Glyph {
        val (x0, y0, x1, y1) = box.toList()
        val gw = x1 - x0 + 1
        val gh = y1 - y0 + 1
        val bg = BooleanArray(gw * gh) { i -> !fg[(y0 + i / gw) * w + x0 + i % gw] }
        val holes = components(bg, gw, gh, eightConnected = false)
            .filter { region -> region.none { p -> p % gw == 0 || p % gw == gw - 1 || p / gw == 0 || p / gw == gh - 1 } }
            .filter { it.size >= MIN_HOLE_SHARE * gw * gh }
            .map { region ->
                val ys = region.map { it / gw }
                Hole(
                    areaShare = region.size.toDouble() / (gw * gh),
                    cx = region.sumOf { it % gw }.toDouble() / region.size / gw,
                    cy = ys.sum().toDouble() / region.size / gh,
                    heightShare = (ys.max() - ys.min() + 1).toDouble() / gh,
                )
            }
        return Glyph(x0, y0, x1, y1, holes)
    }

    /** Merges component boxes that overlap horizontally by more than half the narrower one. */
    private fun mergeByColumn(boxes: List<IntArray>): List<IntArray> {
        val merged = mutableListOf<IntArray>()
        for (b in boxes) {
            val last = merged.lastOrNull()
            if (last != null) {
                val overlap = minOf(last[2], b[2]) - maxOf(last[0], b[0]) + 1
                if (overlap > 0.5 * minOf(last[2] - last[0] + 1, b[2] - b[0] + 1)) {
                    merged[merged.lastIndex] = intArrayOf(
                        minOf(last[0], b[0]), minOf(last[1], b[1]), maxOf(last[2], b[2]), maxOf(last[3], b[3]),
                    )
                    continue
                }
            }
            merged += b
        }
        return merged
    }

    private fun bounds(region: IntArray, w: Int): IntArray {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        for (p in region) {
            val x = p % w
            val y = p / w
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    /** Connected regions of `true` pixels, each as its pixel indices. */
    private fun components(mask: BooleanArray, w: Int, h: Int, eightConnected: Boolean = true): List<IntArray> {
        val seen = BooleanArray(mask.size)
        val out = mutableListOf<IntArray>()
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            val region = ArrayList<Int>()
            while (top > 0) {
                val p = stack[--top]
                region += p
                val x = p % w
                val y = p / w
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (!eightConnected && dx != 0 && dy != 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val q = ny * w + nx
                        if (mask[q] && !seen[q]) {
                            seen[q] = true
                            stack[top++] = q
                        }
                    }
                }
            }
            out += region.toIntArray()
        }
        return out
    }

    /** Otsu's threshold over 0..255 values. */
    private fun otsu(values: IntArray): Int {
        val hist = IntArray(256)
        values.forEach { hist[it]++ }
        val total = values.size.toDouble()
        val sumAll = (0..255).sumOf { it.toDouble() * hist[it] }
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }
}
