package com.basetool.bpextractor.refinery

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A panel candidate box in native pixels. */
data class PanelBox(val x: Int, val y: Int, val width: Int, val height: Int)

/** The prepared inputs for one screenshot: the normalized panel crop + optional location strip. */
data class PreparedImage(
    /** The normalized panel image handed to the VLM read. */
    val readImage: BufferedImage,
    /** The terminal-header strip (location read); null for pre-cropped input. */
    val locationImage: BufferedImage?,
    /** The native-pixel panel box; null when the input was pre-cropped. */
    val panelBox: PanelBox?,
    /** Contract `cropMode`: `vlm` (auto-located) or `precropped`. */
    val cropMode: String,
)

/**
 * The Locate and Normalize stages.
 *
 * - **Locate** finds work-order panels on a 1/4-scale frame via two colour anchors, the maroon SETUP
 *   tab strip and a CTA element below it, for both terminal skins (amber: orange CONFIRM, solid
 *   strip; C47: green CONFIRM ([isCtaGreen]), hatched strip). Candidates are returned left to right;
 *   the leftmost is the newest order.
 * - The capture class is decided by shape ([isPrecropped]): landscape full frames and portrait
 *   terminal-area crops are located, narrow panel-only crops skip Locate.
 * - **Normalize** crops from the native frame and resizes to a long edge of [TARGET_LONG_EDGE] px
 *   (pre-cropped input capped at [PRECROP_MAX_DIM]), dimensions snapped to multiples of 32.
 */
object Locate {

    /** The VLM's sweet spot for the long edge (master plan §9 / Phase 0). */
    const val TARGET_LONG_EDGE = 1536

    /** Upscale cap for pre-cropped panels. */
    const val PRECROP_MAX_DIM = 1200

    /** Verified 4K fallback geometry (x, y, w, h) when no colour-anchor candidate is found. */
    private val PANEL_4K = PanelBox(950, 350, 920, 1500)

    /** Terminal-header strip holding the location, 4K reference coordinates. */
    private val LOCATION_4K = PanelBox(250, 200, 900, 220)

    private const val SCALE = 4

    /**
     * Aspect ratio above which a frame is treated as ultrawide ([isUltrawide]): above 16:9 and 16:10,
     * below 21:9. Drives the header strip ([prepare]) and the pipeline's terminal-extent rescue.
     */
    private const val ULTRAWIDE_ASPECT = 2.0

    /**
     * Fraction of the capture height from the top edge within which a maroon run is the terminal's
     * system banner, not a tab strip ([locatePanels]).
     */
    private const val TOP_BANNER_GUARD = 0.06

    /**
     * How far right of the tab strip's left edge the CTA search may reach, in strip widths — the
     * panel-width proxy. See the bound in [locatePanels]: the C47 UI's strip is a small hatched
     * block (~0.37 of the panel width) where the amber UI's spans the whole panel header.
     */
    private const val CTA_SEARCH_STRIPS = 2.9

    /** [CTA_SEARCH_STRIPS] on ultrawide frames — the verified narrow window, see [locatePanels]. */
    private const val CTA_SEARCH_STRIPS_ULTRAWIDE = 1.9

    /**
     * Mean per-pixel saturation ceiling for a panel candidate ([locatePanels]); rejects orange-lit
     * scenery the colour anchors would otherwise accept.
     */
    private const val MAX_UI_SATURATION = 35.0

    /**
     * How far down the frame a tab strip may sit to still qualify for the CTA-less rescue in
     * [locatePanels]. The panel hangs below its strip and fills the capture, so a real strip is in
     * the top third; the sample set's rescue case has it at 11 % and its sidebar decoys at 35 % and
     * 80 %.
     */
    private const val RESCUE_MAX_STRIP_TOP = 0.35

    /**
     * Whether the image is a pre-cropped panel: small and narrow portrait (w/h below 0.7). Squarer
     * portrait captures of the whole terminal area are not pre-cropped and go through Locate.
     */
    fun isPrecropped(width: Int, height: Int): Boolean =
        width < 1000 && height > width && width * 10 < height * 7

    /** The SETUP tab strip chrome: dark desaturated red, ~RGB(72,49,45) at 4K. */
    internal fun isMaroon(r: Int, g: Int, b: Int): Boolean =
        r in 55..115 && g <= 75 && b <= 70 && r - g >= 14 && r - b >= 16

    /** The CONFIRM / GET QUOTE button fill in the amber refinery UI: bright KRT-style orange. */
    internal fun isCtaOrange(r: Int, g: Int, b: Int): Boolean =
        r >= 170 && g in 110..200 && b <= 110 && r - b >= 90

    /**
     * Whether a pixel matches the yellow-green CONFIRM button fill of the C47 refinery UI, across bright
     * and dim exposures. Green-dominant so it never matches the amber fill, blue-poor so cyan UI text
     * stays out.
     */
    internal fun isCtaGreen(r: Int, g: Int, b: Int): Boolean =
        g in 110..220 && r in 90..200 && b <= 110 && g - b >= 55 && g - r >= 6

    /** Either CTA fill — the amber UI's orange or the C47 UI's green. */
    internal fun isCta(r: Int, g: Int, b: Int): Boolean = isCtaOrange(r, g, b) || isCtaGreen(r, g, b)

    /**
     * Find all work-order panel candidates, left to right, in native pixels. Empty when the
     * colour anchors match nothing (caller falls back to [locatePanel]'s fixed geometry).
     */
    fun locatePanels(img: BufferedImage): List<PanelBox> {
        val small = scaleDown(img, SCALE)
        val w = small.width
        val h = small.height
        val wideCta = !isUltrawide(img)
        val maxGap = max(6, w / 80)
        val maxRunW = (if (h > w) 0.75 else 0.45) * w

        data class RowRun(val y: Int, val x0: Int, val x1: Int)
        val rowRuns = mutableListOf<RowRun>()
        for (y in 0 until h) {
            val matches = BooleanArray(w)
            for (x in 0 until w) {
                val rgb = small.getRGB(x, y)
                matches[x] = isMaroon((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            }
            for ((x0, x1) in runs(matches, maxGap)) {
                val width = x1 - x0
                var density = 0
                for (x in x0..x1) if (matches[x]) density++
                if (width >= 0.08 * w && width <= maxRunW && density.toDouble() / (width + 1) >= 0.35) {
                    rowRuns += RowRun(y, x0, x1)
                }
            }
        }

        val clusters = mutableListOf<MutableList<RowRun>>()
        for (run in rowRuns.sortedWith(compareBy({ it.y }, { it.x0 }))) {
            var placed = false
            for (cluster in clusters) {
                val last = cluster.last()
                val sameWidth = kotlin.math.abs((run.x1 - run.x0) - (last.x1 - last.x0)) <= 0.4 * max(1, last.x1 - last.x0)
                val overlaps = min(run.x1, last.x1) - max(run.x0, last.x0) > 0.5 * (run.x1 - run.x0)
                if (run.y - last.y <= 3 && sameWidth && overlaps) {
                    cluster += run
                    placed = true
                    break
                }
            }
            if (!placed) clusters += mutableListOf(run)
        }

        data class Strip(val x0: Int, val x1: Int, val top: Int, val bottom: Int)
        val allStrips = clusters.mapNotNull { cluster ->
            if (cluster.size < 2) return@mapNotNull null
            val ys = cluster.map { it.y }
            val stripTop = ys.min()
            val stripBottom = ys.max()
            if (stripBottom - stripTop > 12) return@mapNotNull null
            Strip(
                x0 = cluster.map { it.x0 }.sorted()[cluster.size / 2],
                x1 = cluster.map { it.x1 }.sorted()[cluster.size / 2],
                top = stripTop,
                bottom = stripBottom,
            )
        }.sortedBy { it.x0 }
        val strips = allStrips.filterNot { it.top < TOP_BANNER_GUARD * h }.ifEmpty { allStrips }

        fun candidate(strip: Strip, requireCta: Boolean): PanelBox? {
            val (x0, x1, stripTop, stripBottom) = strip
            val stripW = x1 - x0
            val reach = if (wideCta) CTA_SEARCH_STRIPS else CTA_SEARCH_STRIPS_ULTRAWIDE
            val searchX1 = min(w - 1, x0 + (stripW * reach).toInt())
            var ctaRow: Int? = null
            var ctaRight = x1
            for (y in h - 1 downTo stripBottom + 6) {
                val matches = BooleanArray(searchX1 - x0 + 1)
                for (x in x0..searchX1) {
                    val rgb = small.getRGB(x, y)
                    matches[x - x0] = isCta((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                }
                val ctaRuns = runs(matches, 2).filter { it.second - it.first >= max(6, stripW / 12) }
                if (ctaRuns.isNotEmpty()) {
                    if (ctaRow == null) ctaRow = y
                    ctaRight = max(ctaRight, x0 + ctaRuns.maxOf { it.second })
                    if (!wideCta) break
                }
            }
            if (ctaRow == null && requireCta) return null
            val bottom = ctaRow ?: (h - 1)
            val right = if (ctaRow != null) ctaRight else searchX1
            if (meanSaturation(small, x0, stripTop, min(w - 1, right), bottom) > MAX_UI_SATURATION) return null
            val margin = max(3, stripW / 40)
            val top = max(0, stripTop - 4 * margin)
            val bot = min(h - 1, bottom + 3 * margin)
            return PanelBox(
                x = max(0, x0 - margin) * SCALE,
                y = top * SCALE,
                width = (right - x0 + 2 * margin) * SCALE,
                height = (bot - top) * SCALE,
            )
        }

        val boxes = strips.mapNotNull { candidate(it, requireCta = true) }.toMutableList()
        if (boxes.isEmpty()) {
            boxes += strips.filter { it.top < RESCUE_MAX_STRIP_TOP * h }
                .mapNotNull { candidate(it, requireCta = false) }
        }

        val tallest = boxes.maxOfOrNull { it.height } ?: 0
        boxes.removeAll { it.height < 0.6 * tallest }

        boxes.sortBy { it.x }
        val merged = mutableListOf<PanelBox>()
        for (box in boxes) {
            val last = merged.lastOrNull()
            if (last != null && box.x < last.x + last.width * 0.5) {
                val x0n = min(last.x, box.x)
                val y0n = min(last.y, box.y)
                merged[merged.size - 1] = PanelBox(
                    x = x0n,
                    y = y0n,
                    width = max(last.x + last.width, box.x + box.width) - x0n,
                    height = max(last.y + last.height, box.y + box.height) - y0n,
                )
            } else {
                merged += box
            }
        }
        return merged
    }

    /**
     * The extraction target: the leftmost candidate (the newest order), or `null` when the colour anchors
     * matched nothing, leaving the caller to decide before falling back to [fallbackPanel].
     */
    fun locatePanelOrNull(img: BufferedImage): PanelBox? = locatePanels(img).firstOrNull()

    /**
     * The verified 4K geometry scaled to the frame. Position scales with each axis; the panel
     * SIZE scales with the height only — on an ultrawide (e.g. 5120×1440) the game renders the
     * panel at the 16:9 size, so width-proportional scaling would distort the crop.
     */
    fun fallbackPanel(img: BufferedImage): PanelBox {
        val fx = img.width / 3840.0
        val fy = img.height / 2160.0
        return PanelBox(
            (PANEL_4K.x * fx).toInt(),
            (PANEL_4K.y * fy).toInt(),
            (PANEL_4K.width * fy).toInt().coerceAtMost(img.width - (PANEL_4K.x * fx).toInt()),
            (PANEL_4K.height * fy).toInt(),
        )
    }

    /** [locatePanelOrNull] with the silent [fallbackPanel] — kept for callers without a UI. */
    fun locatePanel(img: BufferedImage): PanelBox = locatePanelOrNull(img) ?: fallbackPanel(img)

    /** Snap a dimension to the nearest multiple of 32 (≥ 32). */
    fun snap32(v: Int): Int = max(32, (v / 32.0).roundToInt() * 32)

    /**
     * Resizes so the long edge reaches the model's target, dimensions snapped to /32. The
     * [PRECROP_MAX_DIM] cap applies only to user pre-cropped panels; panels cropped from a larger frame
     * go to [TARGET_LONG_EDGE] via the explicit-target overload.
     */
    fun normalize(img: BufferedImage): BufferedImage {
        val longEdge = max(img.width, img.height)
        val target = if (longEdge < 1000) min(TARGET_LONG_EDGE, PRECROP_MAX_DIM) else TARGET_LONG_EDGE
        return normalize(img, target)
    }

    /** [normalize] to an explicit long-edge [target]. */
    fun normalize(img: BufferedImage, target: Int): BufferedImage {
        val factor = target.toDouble() / max(img.width, img.height)
        val nw = snap32(kotlin.math.ceil(img.width * factor).toInt())
        val nh = snap32(kotlin.math.ceil(img.height * factor).toInt())
        return resize(img, nw, nh)
    }

    /** Locate + Normalize one screenshot into the VLM-ready inputs. */
    fun prepare(img: BufferedImage): PreparedImage {
        val box = if (isPrecropped(img.width, img.height)) null else locatePanel(img)
        return prepare(img, box)
    }

    /**
     * Normalize with a pre-computed panel [box] (null = pre-cropped input) — split out so the
     * pipeline can report Locate and Normalize as separate progress stages (design spec §5.3).
     */
    fun prepare(img: BufferedImage, box: PanelBox?): PreparedImage {
        if (box == null) {
            return PreparedImage(normalize(img), null, null, "precropped")
        }
        val panel = img.getSubimage(
            box.x.coerceIn(0, img.width - 1),
            box.y.coerceIn(0, img.height - 1),
            box.width.coerceAtMost(img.width - box.x),
            box.height.coerceAtMost(img.height - box.y),
        )
        val ultrawideExtent = if (isUltrawide(img)) terminalExtentX(img) else null
        val loc = if (ultrawideExtent != null) {
            val (ex0, ex1) = ultrawideExtent
            val stripW = ((ex1 - ex0) * 2 / 3).coerceAtMost(img.width - ex0)
            val stripH = (img.height * 0.22).toInt().coerceIn(1, img.height)
            img.getSubimage(ex0, 0, stripW, stripH)
        } else if (img.height > img.width) {
            val stripH = max(40, img.height / 10).coerceAtMost(img.height)
            val header = img.getSubimage(0, 0, img.width, stripH)
            header.getSubimage(0, 0, headerNameRight(header, img.width * 2 / 3), stripH)
        } else {
            val fx = img.width / 3840.0
            val fy = img.height / 2160.0
            img.getSubimage(
                (LOCATION_4K.x * fx).toInt(),
                (LOCATION_4K.y * fy).toInt(),
                (LOCATION_4K.width * fx).toInt().coerceAtMost(img.width - (LOCATION_4K.x * fx).toInt()),
                (LOCATION_4K.height * fy).toInt().coerceAtMost(img.height - (LOCATION_4K.y * fy).toInt()),
            )
        }
        val locNorm = resize(loc, snap32(loc.width * 2), snap32(loc.height * 2))
        val readTarget = min(TARGET_LONG_EDGE, (max(panel.width, panel.height) * 1.4).toInt())
        return PreparedImage(normalize(panel, readTarget), locNorm, box, "vlm")
    }

    /** A bright near-white / cyan UI glyph pixel — the terminal's text, never the orange hull. */
    private fun isUiText(r: Int, g: Int, b: Int): Boolean = r > 170 && g > 170 && b > 150

    /**
     * The right edge (strip-local px) to cut a portrait header location strip at: just past the station
     * name, the leftmost run of bright UI-text columns ([isUiText]) with word gaps bridged. Falls back to
     * [fallbackRight] when no name text stands out.
     */
    internal fun headerNameRight(strip: BufferedImage, fallbackRight: Int): Int {
        val w = strip.width
        val h = strip.height
        val col = IntArray(w)
        for (x in 0 until w) {
            var c = 0
            for (y in 0 until h) {
                val rgb = strip.getRGB(x, y)
                if (isUiText((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) c++
            }
            col[x] = c
        }
        val peak = col.maxOrNull() ?: 0
        if (peak < 3) return fallbackRight
        val threshold = max(2, (peak * 0.10).toInt())
        val mask = BooleanArray(w) { col[it] >= threshold }
        val nameRun = runs(mask, max(8, w / 25)).firstOrNull { it.second - it.first >= w / 40 }
            ?: return fallbackRight
        val margin = max(8, w / 40)
        return min(w, nameRun.second + margin + 1)
    }

    /**
     * The terminal's horizontal content extent `(x0, x1)` on an ultrawide frame in native pixels: the
     * widest contiguous run of columns rich in bright UI text ([isUiText]), or `null` when none stands
     * out.
     */
    internal fun terminalExtentX(img: BufferedImage): Pair<Int, Int>? {
        val small = scaleDown(img, SCALE)
        val w = small.width
        val h = small.height
        val col = IntArray(w)
        for (x in 0 until w) {
            var c = 0
            for (y in 0 until h) {
                val rgb = small.getRGB(x, y)
                if (isUiText((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) c++
            }
            col[x] = c
        }
        val peak = col.maxOrNull() ?: 0
        if (peak < 4) return null
        val threshold = max(2, (peak * 0.04).toInt())
        val mask = BooleanArray(w) { col[it] >= threshold }
        val best = runs(mask, max(8, w / 16)).maxByOrNull { it.second - it.first } ?: return null
        val margin = max(8, (best.second - best.first) / 25)
        val x0 = max(0, best.first - margin) * SCALE
        val x1 = (min(w - 1, best.second + margin) + 1) * SCALE
        return x0 to min(img.width, x1)
    }

    /** True for ultrawide / multi-monitor frames (aspect > [ULTRAWIDE_ASPECT]) — see that const. */
    fun isUltrawide(img: BufferedImage): Boolean = img.width > img.height * ULTRAWIDE_ASPECT

    /**
     * The whole terminal as one full-height [PanelBox] of [terminalExtentX] width, or `null` when no UI
     * text stands out. Used by [RefineryPipeline] only as a rescue when the per-panel crop reads no
     * numbers.
     */
    fun terminalExtentBox(img: BufferedImage): PanelBox? =
        terminalExtentX(img)?.let { (x0, x1) -> PanelBox(x0, 0, x1 - x0, img.height) }

    /**
     * Mean per-pixel saturation (max channel − min channel) over an inclusive box of [small] —
     * the "is this UI or is this the world" test, see the guard in [locatePanels]. Subsampled
     * every other pixel; the signal is a bulk property, not a per-pixel one.
     */
    private fun meanSaturation(small: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0L
        var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val rgb = small.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                sum += (max(r, max(g, b)) - min(r, min(g, b))).toLong()
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /** Gap-tolerant contiguous runs over a boolean row (gaps = strip text holes). */
    internal fun runs(matches: BooleanArray, maxGap: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var last = -1
        for (x in matches.indices) {
            if (matches[x]) {
                if (start < 0) start = x
                last = x
            } else if (start >= 0 && x - last > maxGap) {
                result += start to last
                start = -1
                last = -1
            }
        }
        if (start >= 0) result += start to last
        return result
    }

    /**
     * Downscales by an exact integer [factor] with a box filter (mean of each block), so the C47 UI's
     * hatched tab strip survives as maroon where bicubic would alias it away. Alpha is ignored.
     */
    private fun scaleDown(img: BufferedImage, factor: Int): BufferedImage {
        if (img.width < factor || img.height < factor) {
            return resize(img, max(1, img.width / factor), max(1, img.height / factor))
        }
        val w = img.width / factor
        val h = img.height / factor
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val src = IntArray(img.width)
        val r = IntArray(w)
        val g = IntArray(w)
        val b = IntArray(w)
        val n = factor * factor
        for (by in 0 until h) {
            r.fill(0)
            g.fill(0)
            b.fill(0)
            for (dy in 0 until factor) {
                img.getRGB(0, by * factor + dy, img.width, 1, src, 0, img.width)
                for (bx in 0 until w) {
                    val base = bx * factor
                    for (dx in 0 until factor) {
                        val p = src[base + dx]
                        r[bx] += (p shr 16) and 0xFF
                        g[bx] += (p shr 8) and 0xFF
                        b[bx] += p and 0xFF
                    }
                }
            }
            for (bx in 0 until w) {
                out.setRGB(bx, by, ((r[bx] / n) shl 16) or ((g[bx] / n) shl 8) or (b[bx] / n))
            }
        }
        return out
    }

    private fun resize(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(img, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
