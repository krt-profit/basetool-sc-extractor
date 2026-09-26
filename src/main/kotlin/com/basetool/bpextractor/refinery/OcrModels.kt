package com.basetool.bpextractor.refinery

import java.nio.file.Files
import java.nio.file.Path

/**
 * Lazy, process-lifetime provider of the bundled classical-OCR reader ([PanelOcr]), created on the
 * first refinery extraction that asks for it and never closed; callers must not close it.
 *
 * Resolution order:
 * 1. the `OCR_MODELS_DIR` environment override, a folder holding [DET_FILE], [REC_FILE] and
 *    [DICT_FILE];
 * 2. the bundled classpath resources under `/ocr/`. When absent, [get] returns `null` and the
 *    pipeline runs without the OCR cross-check.
 */
object OcrModels {

    /** The bundled PP-OCRv6 small detection model. */
    const val DET_FILE = "PP-OCRv6_small_det.onnx"

    /** The bundled PP-OCRv6 small recognition model. */
    const val REC_FILE = "PP-OCRv6_small_rec.onnx"

    /** The recognition dictionary, one entry per line; the model carries none in its metadata. */
    const val DICT_FILE = "PP-OCRv6_small_rec_dict.txt"

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
            val dict = Path.of(dir, DICT_FILE)
            if (Files.isRegularFile(dict)) {
                PanelOcr.fromFiles(Path.of(dir, DET_FILE), Path.of(dir, REC_FILE), readDictionary(Files.readString(dict)))
                    ?.let { return it }
            }
        }
        val det = resource(DET_FILE) ?: return null
        val rec = resource(REC_FILE) ?: return null
        val dict = resource(DICT_FILE) ?: return null
        return PanelOcr(det, rec, TextDetector.Params.PP_OCR_V6_SMALL, readDictionary(dict.toString(Charsets.UTF_8)))
    }

    private fun resource(name: String): ByteArray? =
        OcrModels::class.java.getResourceAsStream("/ocr/$name")?.use { it.readBytes() }

    /** Splits a dictionary file into its entries, keeping entries that are themselves whitespace. */
    internal fun readDictionary(text: String): List<String> =
        text.removePrefix("﻿").split('\n').map { it.removeSuffix("\r") }.let { lines ->
            if (lines.lastOrNull() == "") lines.dropLast(1) else lines
        }
}
