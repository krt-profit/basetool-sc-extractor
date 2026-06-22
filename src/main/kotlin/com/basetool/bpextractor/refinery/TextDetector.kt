package com.basetool.bpextractor.refinery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PP-OCRv3 text DETECTION (Differentiable Binarization / DBNet) run via ONNX Runtime — the
 * cell-finder that pairs with [DigitOcr] (recognition). On the refinery SETUP panel the table is a
 * clean axis-aligned grid of bright text on a dark interior, so the heavy opencv-only steps of the
 * reference post-process (findContours + minAreaRect + pyclipper unclip) are replaced with an
 * equivalent axis-aligned pipeline: binarize the probability map, dilate, label connected
 * components, take each component's bounding box, score it by mean probability, and expand it by
 * the DB "unclip" distance. That is faithful for axis-aligned boxes and needs no native CV.
 *
 * Pre/post parameters are the rapidocr `ch_PP-OCRv3_det` config verbatim (min-side resize to 736
 * snapped to /32; ImageNet mean/std on RGB; thresh 0.3, box_thresh 0.5, unclip 1.6, 2×2 dilation)
 * so the boxes match the reference detector that was ground-truthed on the golden panels.
 *
 * The ONNX session is heavyweight — construct once and reuse; [close] releases the native session.
 */
class TextDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    /** Load from a model file on disk (smoke harnesses / dev). */
    constructor(modelPath: Path) : this(
        OrtEnvironment.getEnvironment(),
        OrtEnvironment.getEnvironment().createSession(modelPath.toString(), OrtSession.SessionOptions()),
    )

    /** Load from in-memory model bytes (the bundled classpath resource — no temp file needed). */
    constructor(modelBytes: ByteArray) : this(
        OrtEnvironment.getEnvironment(),
        OrtEnvironment.getEnvironment().createSession(modelBytes, OrtSession.SessionOptions()),
    )

    private val inputName: String = session.inputNames.first()

    /** An axis-aligned detected text box in the SOURCE image's pixels. */
    data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
        val cx get() = (x0 + x1) / 2
        val cy get() = (y0 + y1) / 2
    }

    /** Detect all text boxes in [img], in source pixels (unordered). */
    fun detect(img: BufferedImage): List<Box> {
        val srcW = img.width
        val srcH = img.height
        val ratio = if (min(srcH, srcW) < LIMIT_SIDE) LIMIT_SIDE.toDouble() / min(srcH, srcW) else 1.0
        val rw = snap32((srcW * ratio).roundToInt())
        val rh = snap32((srcH * ratio).roundToInt())
        val resized = resize(img, rw, rh)

        val data = normalize(resized)
        val shape = longArrayOf(1, 3, rh.toLong(), rw.toLong())
        val prob: Array<FloatArray> = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<Array<FloatArray>>>)[0][0] // [H][W]
            }
        }
        return boxesFromProb(prob, rw, rh, srcW, srcH)
    }

    /** DB post-process: binarize → dilate → connected components → bbox → score → unclip → scale. */
    private fun boxesFromProb(prob: Array<FloatArray>, mapW: Int, mapH: Int, srcW: Int, srcH: Int): List<Box> {
        val bin = Array(mapH) { y -> BooleanArray(mapW) { x -> prob[y][x] > THRESH } }
        val mask = dilate2x2(bin, mapW, mapH)

        val boxes = mutableListOf<Box>()
        val labels = IntArray(mapW * mapH) { -1 }
        val stack = ArrayDeque<Int>()
        for (sy in 0 until mapH) {
            for (sx in 0 until mapW) {
                val start = sy * mapW + sx
                if (!mask[sy][sx] || labels[start] != -1) continue
                // Flood-fill one component (8-connectivity), tracking its bounding box + prob sum.
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                stack.addLast(start)
                labels[start] = start
                while (stack.isNotEmpty()) {
                    val p = stack.removeLast()
                    val px = p % mapW; val py = p / mapW
                    if (px < minX) minX = px; if (px > maxX) maxX = px
                    if (py < minY) minY = py; if (py > maxY) maxY = py
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx; val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= mapW || ny >= mapH) continue
                        val np = ny * mapW + nx
                        if (mask[ny][nx] && labels[np] == -1) {
                            labels[np] = start
                            stack.addLast(np)
                        }
                    }
                }
                val w = maxX - minX + 1
                val h = maxY - minY + 1
                if (min(w, h) < MIN_SIZE) continue
                // box_score_fast: mean probability over the component's bounding box.
                var sum = 0.0
                for (y in minY..maxY) for (x in minX..maxX) sum += prob[y][x]
                if (sum / (w * h) < BOX_THRESH) continue
                // DB unclip: expand the box outward by area*ratio/perimeter (axis-aligned form).
                val dist = (w.toDouble() * h * UNCLIP_RATIO / (2.0 * (w + h))).roundToInt()
                val ex0 = (minX - dist).coerceAtLeast(0)
                val ey0 = (minY - dist).coerceAtLeast(0)
                val ex1 = (maxX + dist).coerceAtMost(mapW - 1)
                val ey1 = (maxY + dist).coerceAtMost(mapH - 1)
                if (min(ex1 - ex0, ey1 - ey0) < MIN_SIZE + 2) continue
                // Scale the box from the resized-map space back to source pixels.
                boxes += Box(
                    x0 = (ex0.toDouble() * srcW / mapW).roundToInt().coerceIn(0, srcW),
                    y0 = (ey0.toDouble() * srcH / mapH).roundToInt().coerceIn(0, srcH),
                    x1 = (ex1.toDouble() * srcW / mapW).roundToInt().coerceIn(0, srcW),
                    y1 = (ey1.toDouble() * srcH / mapH).roundToInt().coerceIn(0, srcH),
                )
            }
        }
        return boxes
    }

    /** 2×2 dilation (bridges 1px gaps so a number's digits merge into one component). */
    private fun dilate2x2(bin: Array<BooleanArray>, w: Int, h: Int): Array<BooleanArray> {
        val out = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!bin[y][x]) continue
                out[y][x] = true
                if (x + 1 < w) out[y][x + 1] = true
                if (y + 1 < h) out[y + 1][x] = true
                if (x + 1 < w && y + 1 < h) out[y + 1][x + 1] = true
            }
        }
        return out
    }

    /** ImageNet normalize → CHW float (RGB order): (v/255 - mean)/std. */
    private fun normalize(img: BufferedImage): FloatArray {
        val w = img.width
        val h = img.height
        val plane = w * h
        val data = FloatArray(3 * plane)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = img.getRGB(x, y)
                val r = ((rgb shr 16) and 0xFF) / 255f
                val g = ((rgb shr 8) and 0xFF) / 255f
                val b = (rgb and 0xFF) / 255f
                val i = y * w + x
                data[i] = (r - MEAN_R) / STD_R               // channel 0 = R
                data[plane + i] = (g - MEAN_G) / STD_G        // channel 1 = G
                data[2 * plane + i] = (b - MEAN_B) / STD_B    // channel 2 = B
            }
        }
        return data
    }

    private fun snap32(v: Int): Int = max(32, (v / 32.0).roundToInt() * 32)

    private fun resize(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        return out
    }

    override fun close() = session.close()

    companion object {
        private const val LIMIT_SIDE = 736
        private const val THRESH = 0.3f
        private const val BOX_THRESH = 0.5
        private const val UNCLIP_RATIO = 1.6
        private const val MIN_SIZE = 3
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        @Suppress("unused")
        private fun l2(dx: Double, dy: Double) = sqrt(dx * dx + dy * dy)
    }
}
