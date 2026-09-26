package com.basetool.bpextractor.refinery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Measures [GlyphTopology] on the private sample corpus against its golden-expected values: every OCR
 * cell whose number is a golden value (or one confusable edit away from exactly one) supplies the true
 * digits, and each true 0/6/8/9 glyph is classified.
 *
 * ```powershell
 * $env:GLYPH_EVAL_DIR = "<the sample corpus>"
 * $env:GLYPH_EVAL_EXPECTED = "<corpus>\golden-expected.json"
 * .\gradlew.bat test --tests '*GlyphTopologyEval*' --rerun-tasks -i
 * ```
 *
 * Trivially green when `GLYPH_EVAL_DIR` is unset; prints counts only.
 */
class GlyphTopologyEval {

    @Test
    fun `classifies the confusable digits of the corpus`() {
        val corpus = System.getenv("GLYPH_EVAL_DIR")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        val expected = File(requireNotNull(System.getenv("GLYPH_EVAL_EXPECTED")) { "GLYPH_EVAL_EXPECTED unset" })
        val golden = Json.parseToJsonElement(expected.readText()).jsonObject
        val ocr = OcrModels.get() ?: error("bundled OCR models did not load")

        val confusion = sortedMapOf<String, Int>()
        var cellsTrue = 0
        var cellsOcrWrong = 0
        var segmented = 0
        var arbitrated = 0
        var arbitratedRight = 0
        var arbitratedWrong = 0
        var abstained = 0
        val features = mutableListOf<String>()
        val ocrWrongOutcomes = sortedMapOf<String, Int>()

        for ((orderName, entry) in golden) {
            val order = File(corpus, orderName).takeIf { it.isDirectory } ?: continue
            val obj = entry.jsonObject
            val numbers = mutableSetOf<Long>()
            obj["toRefine"]?.jsonPrimitive?.int?.let { numbers += it.toLong() }
            obj["rows"]?.jsonArray?.forEach { row ->
                row.jsonPrimitive.content.split('|').drop(1).take(3).forEach { it.toLongOrNull()?.let(numbers::add) }
            }
            val images = order.listFiles { f: File -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }.orEmpty()
            for (image in images.sortedBy { it.name }) {
                val panel = Locate.prepare(ImageIO.read(image)).readImage
                for (cell in ocr.readNumericGrid(panel).flatten()) {
                    val read = cell.digits.toLongOrNull() ?: continue
                    val truth = when {
                        read in numbers -> read
                        else -> Validation.confusableEdits(read).filter { it in numbers }.singleOrNull() ?: continue
                    }
                    val truthDigits = truth.toString()
                    if (truthDigits.length != cell.digits.length) continue
                    cellsTrue++
                    val crop = panel.getSubimage(
                        cell.box.x0.coerceIn(0, panel.width - 1),
                        cell.box.y0.coerceIn(0, panel.height - 1),
                        cell.box.width.coerceIn(1, panel.width - cell.box.x0.coerceIn(0, panel.width - 1)),
                        cell.box.height.coerceIn(1, panel.height - cell.box.y0.coerceIn(0, panel.height - 1)),
                    )
                    val topo = GlyphTopology.read(crop, truthDigits.length)
                    if (topo != null) {
                        segmented++
                        val glyphs = GlyphTopology.glyphs(crop)!!
                        truthDigits.forEachIndexed { i, d ->
                            if (d in "0689") {
                                confusion.merge("$d->${topo[i]}", 1, Int::plus)
                                val g = glyphs[i]
                                val f = g.holes.joinToString(";") {
                                    "a=%.3f,cx=%.2f,cy=%.2f,h=%.2f".format(it.areaShare, it.cx, it.cy, it.heightShare)
                                }
                                features += "$d ${topo[i]} gh=${g.height} [$f]"
                            }
                        }
                    }
                    if (read != truth) {
                        cellsOcrWrong++
                        val verdict = GlyphTopology.arbitrate(crop, cell.digits, truthDigits)
                        ocrWrongOutcomes.merge(
                            when (verdict) {
                                truthDigits -> "rescued"
                                null -> "abstained"
                                else -> "wrong"
                            },
                            1,
                            Int::plus,
                        )
                        when (verdict) {
                            truthDigits -> arbitratedRight++
                            null -> abstained++
                            else -> arbitratedWrong++
                        }
                        arbitrated++
                    } else {
                        for (edit in Validation.confusableEdits(read)) {
                            val e = edit.toString()
                            if (e.length != truthDigits.length) continue
                            when (GlyphTopology.arbitrate(crop, e, truthDigits)) {
                                truthDigits -> arbitratedRight++
                                null -> abstained++
                                else -> arbitratedWrong++
                            }
                            arbitrated++
                        }
                    }
                }
            }
        }
        println("GLYPH cells=$cellsTrue segmented=$segmented ocrWrong=$cellsOcrWrong $ocrWrongOutcomes")
        println("GLYPH arbitrations=$arbitrated right=$arbitratedRight wrong=$arbitratedWrong abstained=$abstained")
        println("GLYPH confusion ${confusion.entries.joinToString(" ") { "${it.key}:${it.value}" }}")
        System.getenv("GLYPH_EVAL_FEATURES")?.takeUnless { it.isBlank() }?.let { File(it).writeText(features.joinToString("\n")) }
    }
}
