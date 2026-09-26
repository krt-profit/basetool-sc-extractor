package com.basetool.bpextractor.refinery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.file.Path
import kotlin.math.ceil

/**
 * Classical-OCR digit reader (PP-OCRv3 recognition, CRNN + CTC, via ONNX Runtime), a decorrelated
 * second opinion on numeric cells the VLM misreads.
 *
 * Reads a single cropped cell only and is never used standalone; the result is filtered to `0-9`.
 * The ONNX session is heavyweight: construct once and reuse; [close] releases it.
 */
class DigitOcr private constructor(
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

    /** CTC labels: index 0 = blank, then the model's dictionary, then a trailing space (PP-OCR order). */
    private val labels: List<String> = buildList {
        add("blank")
        val dict = session.metadata.customMetadata["character"]
            ?: error("rec model carries no 'character' metadata")
        dict.split("\n").forEach { add(it.trimEnd('\r')) }
        add(" ")
    }

    /** The model's single input name (PP-OCRv3 rec uses `x`); read it rather than hard-coding. */
    private val inputName: String = session.inputNames.first()

    /** Recognize the digits in a single-line cell crop; "" when no digit is read. */
    fun readNumber(cell: BufferedImage): String {
        val (data, width) = preprocess(cell)
        val shape = longArrayOf(1, 3, IMG_HEIGHT.toLong(), width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val preds = (result[0].value as Array<Array<FloatArray>>)[0]
                return ctcDigits(preds)
            }
        }
    }

    /**
     * PP-OCR rec preprocessing: resize to a fixed height keeping aspect, BGR channel order, normalise
     * to [-1, 1] (`v / 127.5 - 1`), CHW layout. Width is dynamic (the model accepts any), so no
     * right-padding is needed for a single crop.
     */
    private fun preprocess(img: BufferedImage): Pair<FloatArray, Int> {
        val width = maxOf(1, ceil(IMG_HEIGHT.toDouble() * img.width / img.height).toInt())
        val scaled = BufferedImage(width, IMG_HEIGHT, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().run {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(img, 0, 0, width, IMG_HEIGHT, null)
            dispose()
        }
        val plane = IMG_HEIGHT * width
        val data = FloatArray(3 * plane)
        for (y in 0 until IMG_HEIGHT) {
            for (x in 0 until width) {
                val rgb = scaled.getRGB(x, y)
                val i = y * width + x
                data[i] = (rgb and 0xFF) / 127.5f - 1f
                data[plane + i] = ((rgb shr 8) and 0xFF) / 127.5f - 1f
                data[2 * plane + i] = ((rgb shr 16) and 0xFF) / 127.5f - 1f
            }
        }
        return data to width
    }

    /** CTC greedy decode → digits only: per timestep argmax, drop blanks (index 0), collapse runs. */
    private fun ctcDigits(preds: Array<FloatArray>): String {
        val out = StringBuilder()
        var prev = -1
        for (step in preds) {
            var best = 0
            var bestScore = step[0]
            for (i in 1 until step.size) {
                if (step[i] > bestScore) {
                    bestScore = step[i]
                    best = i
                }
            }
            if (best != 0 && best != prev) {
                labels.getOrNull(best)?.let { ch ->
                    if (ch.length == 1 && ch[0] in '0'..'9') out.append(ch)
                }
            }
            prev = best
        }
        return out.toString()
    }

    override fun close() = session.close()

    companion object {
        /** PP-OCRv3 rec fixed input height (`rec_img_shape = [3, 48, 320]`). */
        private const val IMG_HEIGHT = 48
    }
}
