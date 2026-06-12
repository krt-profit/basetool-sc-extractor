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
 * The Locate → Normalize stages, ported from the Phase 0 spike harness (`normalize.py` — the
 * verified classical-CV approach; `PHASE0_FINDINGS.md` §3):
 *
 * - **Locate** finds work-order panels on a 1/4-scale frame via two colour anchors: the maroon
 *   SETUP tab strip (~RGB(72,49,45); gap-tolerant runs, width-similarity clustering) plus a
 *   bright-orange CTA element below it. Pure luminance profiling does not work — the terminal
 *   interior is uniformly dark (measured mean 35–64 across the golden set).
 * - **Owner-confirmed domain rule (2026-06-10):** with several panels side by side, the NEWEST
 *   order is always the LEFTMOST — candidates are returned left to right and callers take the
 *   first; the VLM read's panel type is a validation, never a selection mechanism.
 * - **Three capture classes** (decided by shape, see [isPrecropped]): landscape full frames
 *   (locate + the verified full-frame header-strip geometry for the location read), portrait
 *   terminal-area crops — header + sidebar + panel, the Auftrag 10/12 class — (locate with a
 *   relaxed strip-width ceiling + location from the crop's top-left strip), and narrow portrait
 *   panel-only crops (skip Locate, no location anywhere — `cropMode = precropped`).
 * - **Normalize** always runs client-side (Ollama silently downscales > ~3.2 MP): crop from the
 *   native frame, resize to a long edge of [TARGET_LONG_EDGE] px (pre-cropped input capped at
 *   [PRECROP_MAX_DIM] — upscaling a ~500 px panel beyond ~2.4× only blurs), dimensions snapped
 *   to multiples of 32. AWT bicubic interpolation; a hand-rolled Lanczos-3 kernel was not needed
 *   on the golden set (plan §9 Phase 3 note).
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
     * A pre-cropped panel image is small and NARROW portrait (golden-set panel-only crops are
     * ~480–520×915–940, w/h ≈ 0.52–0.55). Squarer portrait captures of the whole terminal area
     * — header bar + sidebar + panel, the Auftrag 10/12 class at ~910–970×1050–1090
     * (w/h ≈ 0.84–0.89) — are NOT pre-cropped: they carry a locatable panel AND the location
     * header, so they must go through Locate like a full frame.
     */
    fun isPrecropped(width: Int, height: Int): Boolean =
        width < 1000 && height > width && width * 10 < height * 7

    /** The SETUP tab strip chrome: dark desaturated red, ~RGB(72,49,45) at 4K. */
    internal fun isMaroon(r: Int, g: Int, b: Int): Boolean =
        r in 55..115 && g <= 75 && b <= 70 && r - g >= 14 && r - b >= 16

    /** The CONFIRM / GET QUOTE button fill: bright KRT-style orange. */
    internal fun isCtaOrange(r: Int, g: Int, b: Int): Boolean =
        r >= 170 && g in 110..200 && b <= 110 && r - b >= 90

    /**
     * Find all work-order panel candidates, left to right, in native pixels. Empty when the
     * colour anchors match nothing (caller falls back to [locatePanel]'s fixed geometry).
     */
    fun locatePanels(img: BufferedImage): List<PanelBox> {
        val small = scaleDown(img, SCALE)
        val w = small.width
        val h = small.height
        val maxGap = max(6, w / 80)
        // Strip-width ceiling, frame-relative: on a (landscape) full frame the tab strip never
        // nears half the screen, but on a portrait terminal-area crop (Auftrag 10/12 class) the
        // panel — and its strip — legitimately spans well past 45% of the image width.
        val maxRunW = (if (h > w) 0.75 else 0.45) * w

        // 1. Per row: gap-tolerant maroon runs of plausible strip width.
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

        // 2. Cluster runs that overlap horizontally, sit on consecutive rows AND share a similar
        //    width (environment noise above the strip must not chain in).
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

        val boxes = mutableListOf<PanelBox>()
        for (cluster in clusters) {
            if (cluster.size < 2) continue // a real strip is several rows tall even at 1/4 scale
            val ys = cluster.map { it.y }
            val x0 = cluster.map { it.x0 }.sorted()[cluster.size / 2]
            val x1 = cluster.map { it.x1 }.sorted()[cluster.size / 2]
            val stripTop = ys.min()
            val stripBottom = ys.max()
            if (stripBottom - stripTop > 12) continue // too tall to be a tab strip
            val stripW = x1 - x0

            // 3. Confirm with an orange CTA below the strip. The maroon strip covers only the
            //    panel's LEFT part; the CTA is right-aligned — search to the right of the strip
            //    too, with an absolute run threshold (small/distant panels have small buttons).
            //    Do NOT scan the whole body width: with two panels side by side that bleeds
            //    into the neighbour's progress bar (verified spike regression).
            val searchX1 = min(w - 1, x0 + (stripW * 1.9).toInt())
            var bottom: Int? = null
            var orangeRight = x1
            for (y in h - 1 downTo stripBottom + 6) {
                val matches = BooleanArray(searchX1 - x0 + 1)
                for (x in x0..searchX1) {
                    val rgb = small.getRGB(x, y)
                    matches[x - x0] = isCtaOrange((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                }
                val orangeRuns = runs(matches, 2).filter { it.second - it.first >= max(6, stripW / 12) }
                if (orangeRuns.isNotEmpty()) {
                    bottom = y
                    orangeRight = max(orangeRight, x0 + orangeRuns.maxOf { it.second })
                    break
                }
            }
            if (bottom == null) continue
            val margin = max(3, stripW / 40)
            val top = max(0, stripTop - 4 * margin) // include the WORK ORDER bar above
            val bot = min(h - 1, bottom + 3 * margin)
            boxes += PanelBox(
                x = max(0, x0 - margin) * SCALE,
                y = top * SCALE,
                width = (orangeRight - x0 + 2 * margin) * SCALE,
                height = (bot - top) * SCALE,
            )
        }

        // Drop sidebar look-alikes: the MATERIAL SELECTION box mimics BOTH anchors (dark-red
        // USER DETAILS strip above an orange SETUP WORK ORDER button — the Auftrag 10 false
        // positive that, as the leftmost candidate, would win the newest-order rule) but is
        // far SHORTER than any work-order panel. Relative to the tallest candidate so the
        // rule holds at any capture distance; a lone candidate is never dropped.
        val tallest = boxes.maxOfOrNull { it.height } ?: 0
        boxes.removeAll { it.height < 0.6 * tallest }

        // Merge near-duplicate candidates (overlapping clusters from split strips), keep
        // left-to-right order — the leftmost candidate is the extraction target.
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
     * The extraction target: the LEFTMOST candidate (= newest order, owner rule 2026-06-10), or
     * null when the colour anchors matched nothing — the caller decides whether to surface the
     * miss before falling back to [fallbackPanel] (the fixed geometry is a guess, not a find).
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
     * Resize so the long edge hits the model sweet spot, dimensions snapped to /32. The
     * [PRECROP_MAX_DIM] cap applies only to small images of unknown provenance (= user
     * pre-cropped panels); panels WE crop out of a larger frame go to the full
     * [TARGET_LONG_EDGE] via the explicit-target overload — capping those starves them of
     * resolution the source frame actually has (the Auftrag 10 digit regression).
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
        val loc = if (img.height > img.width) {
            // Portrait = a terminal-area crop (the game only renders landscape frames): the
            // fixed full-frame header geometry does not apply — the header bar with the
            // location name sits at the very top of the crop, the name on its left. The strip
            // stops at 2/3 width to exclude the big REFINEMENT title on the right.
            img.getSubimage(0, 0, img.width * 2 / 3, max(40, img.height / 10).coerceAtMost(img.height))
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
        // The location strip is small text — a 2× upscale puts it in the model's sweet spot.
        val locNorm = resize(loc, snap32(loc.width * 2), snap32(loc.height * 2))
        // Our own crop from a known frame: aim at the sweet spot but never upscale beyond
        // ~1.4× — blowing a small panel up further degrades the digit reads (measured on the
        // Auftrag 12 terminal-area crop: 1.65× turned a clean 510 into 518), while capping at
        // PRECROP_MAX_DIM starves it below what the source frame carries (Auftrag 10, 105→185).
        val readTarget = min(TARGET_LONG_EDGE, (max(panel.width, panel.height) * 1.4).toInt())
        return PreparedImage(normalize(panel, readTarget), locNorm, box, "vlm")
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

    private fun scaleDown(img: BufferedImage, factor: Int): BufferedImage =
        resize(img, max(1, img.width / factor), max(1, img.height / factor))

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
