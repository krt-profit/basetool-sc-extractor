package com.basetool.bpextractor.refinery

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Validates the production OCR loading path: [OcrModels] loads the bundled `/ocr/` resources and reads
 * a real normalized panel. Gated on `PANEL_DIR`; run without `OCR_MODELS_DIR`.
 */
class BundledOcrTest {

    @Test
    fun `OcrModels loads the bundled models and reads a panel`() {
        val panelDir = System.getenv("PANEL_DIR")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        require(System.getenv("OCR_MODELS_DIR").isNullOrBlank()) {
            "unset OCR_MODELS_DIR — this test must exercise the BUNDLED resource path"
        }
        require(panelDir.isDirectory) { "PANEL_DIR is not a directory: $panelDir" }

        val ocr = OcrModels.get() ?: error("bundled OCR models did not load from the /ocr/ resources")
        val panel = panelDir.listFiles { f -> f.extension.lowercase() == "png" }!!
            .sortedBy { it.name }.firstOrNull() ?: error("no .png panel in $panelDir")
        val rows = ocr.readRows(ImageIO.read(panel))
        println("bundled OCR read ${rows.size} data row(s) from ${panel.name}")
        check(rows.isNotEmpty()) { "bundled OCR produced no rows from ${panel.name}" }
    }
}
