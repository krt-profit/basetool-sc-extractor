package com.basetool.bpextractor.refinery

import java.nio.file.Path

/**
 * Lazy, process-lifetime provider of the bundled classical-OCR cross-reader ([PanelOcr]). The ONNX
 * sessions are heavyweight, so they are created at most once — on the FIRST refinery extraction
 * that asks for them (a blueprint-only session never loads them) — and reused across runs; they are
 * deliberately never closed (process lifetime). The pipeline must not close the returned instance.
 *
 * Resolution order:
 * 1. `OCR_MODELS_DIR` env override — a folder holding the two `ch_PP-OCRv3_{det,rec}_infer.onnx`
 *    files. Lets the golden-sweep harness validate the integration against local models BEFORE the
 *    ~12.5 MB models are bundled into the app.
 * 2. The bundled classpath resources under `/ocr/`. Absent in a dev/test build with no models
 *    bundled — [get] then returns null and the pipeline runs without the OCR cross-check (no
 *    regression: the VLM result stands).
 */
object OcrModels {

    private const val DET_RES = "/ocr/ch_PP-OCRv3_det_infer.onnx"
    private const val REC_RES = "/ocr/ch_PP-OCRv3_rec_infer.onnx"
    private const val DET_FILE = "ch_PP-OCRv3_det_infer.onnx"
    private const val REC_FILE = "ch_PP-OCRv3_rec_infer.onnx"

    @Volatile
    private var loaded = false

    @Volatile
    private var instance: PanelOcr? = null

    /** The shared [PanelOcr], or null when no models are available (OCR cross-check disabled). */
    fun get(): PanelOcr? {
        if (loaded) return instance
        synchronized(this) {
            if (loaded) return instance
            instance = runCatching { load() }.getOrNull()
            loaded = true
            return instance
        }
    }

    private fun load(): PanelOcr? {
        System.getenv("OCR_MODELS_DIR")?.takeUnless { it.isBlank() }?.let { dir ->
            PanelOcr.fromFiles(Path.of(dir, DET_FILE), Path.of(dir, REC_FILE))?.let { return it }
        }
        val det = OcrModels::class.java.getResourceAsStream(DET_RES)?.use { it.readBytes() } ?: return null
        val rec = OcrModels::class.java.getResourceAsStream(REC_RES)?.use { it.readBytes() } ?: return null
        return PanelOcr(det, rec)
    }
}
