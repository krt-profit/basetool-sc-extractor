package com.basetool.bpextractor.refinery

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Manual smoke harness for the [DigitOcr] PP-OCR digit reader — validates that the in-JVM ONNX
 * recognition reproduces the digits the VLM mis-reads (the cells in `refinery-digit-misread-recovery`).
 * Trivially green unless BOTH `OCR_MODEL` (path to `ch_PP-OCRv3_rec_infer.onnx`) and `OCR_CELLS`
 * (a folder of `<truth>__<n>.png` single-number crops) are set.
 *
 * The crops are produced offline from the PRIVATE captures (guardrail 1a) — they are NEVER committed
 * or bundled; the harness only READS a local folder, exactly like [PromptSmokeTest].
 */
class OcrSmokeTest {

    @Test
    fun `DigitOcr reads single-number cell crops`() {
        val modelPath = System.getenv("OCR_MODEL")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        val cellsDir = System.getenv("OCR_CELLS")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        require(modelPath.isFile) { "OCR_MODEL is not a file: $modelPath" }
        require(cellsDir.isDirectory) { "OCR_CELLS is not a directory: $cellsDir" }

        val crops = cellsDir.listFiles { f -> f.extension.lowercase() == "png" }!!.sortedBy { it.name }
        check(crops.isNotEmpty()) { "no .png cell crops in $cellsDir" }

        DigitOcr(modelPath.toPath()).use { ocr ->
            var hits = 0
            crops.forEach { f ->
                val truth = f.name.substringBefore("__").substringBefore('.')
                val read = ocr.readNumber(ImageIO.read(f))
                val ok = read == truth
                if (ok) hits++
                println("  ${f.name.padEnd(14)} truth=${truth.padStart(5)}  ocr=${read.padStart(6)}  ${if (ok) "OK" else "MISS"}")
            }
            println("DigitOcr exact: $hits/${crops.size}")
            // The port must reproduce the Python reference (which read every one of these correctly).
            check(hits == crops.size) { "DigitOcr matched only $hits/${crops.size} — port/preprocessing regression" }
        }
    }
}
