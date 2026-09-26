package com.basetool.bpextractor.refinery

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Manual smoke harness for the [DigitOcr] digit reader on single-number crops. Trivially green unless
 * both `OCR_MODEL` (a recognition model path) and `OCR_CELLS` (a folder of `<truth>__<n>.png` crops)
 * are set; `OCR_DICT` names the dictionary of a model without ONNX `character` metadata. The private
 * crops are never committed.
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

        val dictionary = System.getenv("OCR_DICT")?.takeUnless { it.isBlank() }?.let { OcrModels.readDictionary(File(it).readText()) }
        DigitOcr(modelPath.toPath(), dictionary).use { ocr ->
            var hits = 0
            crops.forEach { f ->
                val truth = f.name.substringBefore("__").substringBefore('.')
                val read = ocr.readNumber(ImageIO.read(f))
                val ok = read == truth
                if (ok) hits++
                println("  ${f.name.padEnd(14)} truth=${truth.padStart(5)}  ocr=${read.padStart(6)}  ${if (ok) "OK" else "MISS"}")
            }
            println("DigitOcr exact: $hits/${crops.size}")
            check(hits == crops.size) { "DigitOcr matched only $hits/${crops.size} — port/preprocessing regression" }
        }
    }
}
