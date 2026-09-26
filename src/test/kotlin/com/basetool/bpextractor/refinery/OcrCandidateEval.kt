package com.basetool.bpextractor.refinery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties

/**
 * Compares classical-OCR model candidates on the same VLM reads, so a model swap is judged by what the
 * cross-reader does to the validated orders and not by a model card.
 *
 * Each candidate is a folder holding `det.onnx` and `rec.onnx`, optionally `dict.txt` (one entry per
 * line, for a model without ONNX `character` metadata) and `det.properties` (`limitSide`, `thresh`,
 * `boxThresh`, `unclipRatio`, defaulting to the bundled [TextDetector.Params.PP_OCR_V6_SMALL]). The
 * report holds private values and belongs outside the repo.
 */
object OcrCandidateEval {

    /** One order's VLM-side result, fixed across every candidate. */
    class OrderReads(
        val name: String,
        val stitched: StitchResult,
        val crossCheck: CrossModelVerify.Outcome?,
        val panels: Map<String, BufferedImage>,
    )

    private val OCR_WARNINGS = setOf(
        ExtractWarning.OCR_CORRECTED,
        ExtractWarning.YIELD_OCR_REPAIRED,
        ExtractWarning.OCR_CONTESTED,
        ExtractWarning.QTY_OCR_CONTESTED,
        ExtractWarning.TO_REFINE_CONTESTED,
    )

    /** Runs every candidate under [candidatesDir] over [orders] and writes the comparison to [out]. */
    fun run(orders: List<OrderReads>, candidatesDir: File, expectedFile: File?, out: File) {
        val expected = expectedFile?.takeIf { it.isFile }?.let { parseExpected(it.readText()) } ?: emptyMap()
        val report = StringBuilder()
        val baseline = orders.associate { it.name to validate(it, null) }
        val summary = mutableListOf<String>()

        val candidates = candidatesDir.listFiles { f: File -> f.isDirectory }.orEmpty().sortedBy { it.name }
        for (dir in candidates) {
            val det = File(dir, "det.onnx")
            val rec = File(dir, "rec.onnx")
            if (!det.isFile || !rec.isFile) {
                report.appendLine("### ${dir.name}: skipped, det.onnx or rec.onnx missing")
                continue
            }
            val dictionary = File(dir, "dict.txt").takeIf { it.isFile }?.readLines(Charsets.UTF_8)
            val params = detParams(File(dir, "det.properties"))
            report.appendLine("### ${dir.name}  params=$params dict=${dictionary?.size ?: "metadata"}")

            val stats = Stats()
            PanelOcr(det.toPath(), rec.toPath(), params, dictionary).use { ocr ->
                for (order in orders) {
                    val started = System.nanoTime()
                    val validated = validate(order, ocr)
                    stats.nanos += System.nanoTime() - started
                    stats.panels += order.panels.size
                    val rows = rowsOf(validated.first)
                    val base = rowsOf(baseline.getValue(order.name).first)
                    val golden = expected[order.name]

                    if (golden != null) {
                        stats.goldenRows += golden.size
                        stats.goldenMiss += golden.filterNot { it in rows }.size
                        if (golden == rows) stats.ordersMatching++
                    }
                    val changed = rows.indices.filter { it < base.size && rows[it] != base[it] }
                    stats.ocrChangedRows += changed.size
                    changed.forEach { i ->
                        val verdict = golden?.getOrNull(i)?.let { g ->
                            when (g) {
                                rows[i] -> "RESCUE"
                                base[i] -> "BREAK"
                                else -> "OTHER"
                            }
                        } ?: "UNKNOWN"
                        stats.verdicts.merge(verdict, 1, Int::plus)
                        report.appendLine("  ${order.name} [$i] $verdict ${base[i]} -> ${rows[i]}")
                    }
                    val baseGoods = baseline.getValue(order.name).first.goods
                    validated.first.goods.forEachIndexed { i, good ->
                        val before = baseGoods.getOrNull(i)?.confidence ?: return@forEachIndexed
                        if (good.confidence < before) {
                            stats.rowsDowngraded++
                            report.appendLine("  ${order.name} [$i] confidence $before -> ${good.confidence}")
                        }
                    }
                    val flags = validated.first.warnings.intersect(OCR_WARNINGS)
                    flags.forEach { stats.flags.merge(it.name, 1, Int::plus) }
                    val baseFlags = baseline.getValue(order.name).first.warnings.intersect(OCR_WARNINGS)
                    if (flags != baseFlags) report.appendLine("  ${order.name} flags ${flags.sorted()}")

                    witness(order, ocr, validated.second, golden, stats, report)
                }
            }
            report.appendLine("  $stats")
            summary += "${dir.name.padEnd(22)} $stats"
        }

        out.absoluteFile.parentFile?.mkdirs()
        out.writeText("=== SUMMARY ===\n" + summary.joinToString("\n") + "\n\n" + report)
        println("OCR candidate report -> ${out.absolutePath}")
    }

    private class Stats {
        var panels = 0
        var nanos = 0L
        var ordersMatching = 0
        var goldenRows = 0
        var goldenMiss = 0
        var ocrChangedRows = 0
        var rowsDowngraded = 0
        val verdicts = sortedMapOf<String, Int>()
        val flags = sortedMapOf<String, Int>()
        var anchored = 0
        var qualityAgree = 0
        var qualityDisagree = 0
        var yieldAgree = 0
        var yieldDisagree = 0
        var qtyWitnessed = 0
        var qtyRows = 0

        override fun toString() =
            "orders=$ordersMatching rowsOff=$goldenMiss/$goldenRows changed=$ocrChangedRows $verdicts downgraded=$rowsDowngraded " +
                "flags=$flags anchored=$anchored/$qtyRows qtyInColumn=$qtyWitnessed " +
                "quality=$qualityAgree/-$qualityDisagree yield=$yieldAgree/-$yieldDisagree " +
                "ms/panel=${if (panels == 0) 0 else nanos / 1_000_000 / panels}"
    }

    /** Validates [order] with [ocr], or without the cross-reader when null; also returns its readings. */
    private fun validate(order: OrderReads, ocr: PanelOcr?): Pair<ValidatedOrder, OcrCrossCheck.Result?> {
        val result = ocr?.let {
            OcrCrossCheck.read(order.stitched.rows, order.panels, it, PanelValues.toQuantity(order.stitched.toRefine))
        }
        val validated = Validation.validate(
            order.stitched,
            order.crossCheck,
            ocr = result?.readings ?: emptyMap(),
            toRefineContested = result?.toRefineContested ?: false,
            qtyOcrContested = result?.qtyContested ?: emptySet(),
        )
        return validated to result
    }

    /** Counts how often the reader's own cells agree with the golden values, independent of fusion. */
    private fun witness(
        order: OrderReads,
        ocr: PanelOcr,
        result: OcrCrossCheck.Result?,
        golden: List<String>?,
        stats: Stats,
        report: StringBuilder,
    ) {
        if (golden == null || golden.size != order.stitched.rows.size) return
        val panelNumbers = HashMap<String, PanelOcr.PanelNumbers>()
        golden.forEachIndexed { i, line ->
            val cells = line.split('|')
            val quality = cells[1].toLongOrNull()
            val qty = cells[2].toLongOrNull()
            val yield_ = cells[3].toLongOrNull()
            val source = order.stitched.rows[i].sourceImage
            val panel = order.panels[source] ?: return@forEachIndexed
            if (qty != null) {
                stats.qtyRows++
                val numbers = panelNumbers.getOrPut(source) { ocr.readPanel(panel) }
                if (qty in numbers.qtyColumn) stats.qtyWitnessed++
            }
            val reading = result?.readings?.get(i) ?: return@forEachIndexed
            stats.anchored++
            if (quality != null && reading.quality != null) {
                if (reading.quality == quality) {
                    stats.qualityAgree++
                } else {
                    stats.qualityDisagree++
                    report.appendLine("  ${order.name} [$i] witness quality ${reading.quality} vs $quality in $source")
                }
            }
            if (yield_ != null && reading.yield_ != null) {
                if (reading.yield_ == yield_) {
                    stats.yieldAgree++
                } else {
                    stats.yieldDisagree++
                    report.appendLine("  ${order.name} [$i] witness yield ${reading.yield_} vs $yield_ in $source")
                }
            }
        }
    }

    private fun rowsOf(order: ValidatedOrder): List<String> =
        order.goods.map { "${it.rawMaterialName}|${it.quality}|${it.inputQuantity}|${it.outputQuantity}|${it.refine}" }

    private fun parseExpected(text: String): Map<String, List<String>> =
        Json.parseToJsonElement(text).jsonObject.mapValues { (_, order) ->
            (order as JsonObject).getValue("rows").jsonArray.map { it.jsonPrimitive.content }
        }

    private fun detParams(file: File): TextDetector.Params {
        val base = TextDetector.Params.PP_OCR_V6_SMALL
        if (!file.isFile) return base
        val props = Properties().apply { file.reader().use { load(it) } }
        return TextDetector.Params(
            limitSide = props.getProperty("limitSide")?.toInt() ?: base.limitSide,
            thresh = props.getProperty("thresh")?.toFloat() ?: base.thresh,
            boxThresh = props.getProperty("boxThresh")?.toDouble() ?: base.boxThresh,
            unclipRatio = props.getProperty("unclipRatio")?.toDouble() ?: base.unclipRatio,
        )
    }
}
