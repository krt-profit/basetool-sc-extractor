package com.basetool.bpextractor.refinery

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Validates the PRODUCTION OCR loading path end to end: [OcrModels] loads the BUNDLED classpath
 * resources (the `/ocr/` .onnx files) via `getResourceAsStream` + ONNX `createSession(bytes)` — no temp file,
 * no env override — and reads a real normalized panel. Gated on `PANEL_DIR` (a folder of panels,
 * e.g. the [PanelDumpTest] output); run it WITHOUT `OCR_MODELS_DIR` so the bundled path (not the
 * dev file override) is exercised. Reads a local folder only — no private data committed.
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
