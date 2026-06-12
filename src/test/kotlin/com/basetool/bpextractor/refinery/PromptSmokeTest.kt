package com.basetool.bpextractor.refinery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Manual smoke harness for the live read path (Locate → Normalize → PanelReader → Stitch →
 * Validation) against a local folder of REAL sample orders — used to spot-check prompt changes
 * against a running Ollama. Skipped (trivially green) unless `PROMPT_SMOKE_DIR` points at a
 * folder of order folders (one folder = one order, e.g. `…\Beispiele Raffinerieaufträge`).
 *
 * The per-image reads + validated orders are written to `build/prompt-smoke.txt` (or
 * `PROMPT_SMOKE_OUT`). With `PROMPT_SMOKE_EXPECTED=<file>` the validated values are diffed
 * against a golden-expected JSON and the test FAILS on any regression — set
 * `PROMPT_SMOKE_WRITE_EXPECTED=1` to (re)write the file from the current state. The input
 * captures, the report AND the expected file contain PRIVATE data (player handle, balance) —
 * keep all of them outside the repo (guardrail 1a; the expected file lives best next to the
 * golden set itself).
 */
class PromptSmokeTest {

    /** One order's golden-expected shape: header total + rows as `name|quality|in|out|refine`. */
    @Serializable
    private data class ExpectedOrder(val toRefine: Long?, val rows: List<String>)

    @Test
    fun `read every sample order through the live pipeline stages`() {
        val root = System.getenv("PROMPT_SMOKE_DIR")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        require(root.isDirectory) { "PROMPT_SMOKE_DIR is not a directory: $root" }
        val model = System.getenv("PROMPT_SMOKE_MODEL") ?: "qwen3-vl:8b-instruct"
        val reader = PanelReader(HttpOllamaClient(), model)
        // Optional cross-model verify pass, mirroring RefineryPipeline's merge semantics.
        val verifyName = System.getenv("PROMPT_SMOKE_VERIFY_MODEL")?.takeUnless { it.isBlank() }
        val verifier = verifyName?.let { PanelReader(HttpOllamaClient(), it) }
        val report = StringBuilder()
        val actualOrders = linkedMapOf<String, ExpectedOrder>()

        val orders = root.listFiles { f: File -> f.isDirectory }!!
            .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
        orders.forEach { folder ->
            report.appendLine("=== ${folder.name} ===")
            val images = folder.listFiles { f: File -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }!!
                .sortedBy { it.name }
            val reads = mutableListOf<ImageRead>()
            val verifyQueue = mutableListOf<Pair<String, String>>()
            images.forEach { file ->
                val img = ImageIO.read(file)
                val precropped = Locate.isPrecropped(img.width, img.height)
                val box = if (precropped) null else Locate.locatePanel(img)
                val prepared = Locate.prepare(img, box)
                val b64 = toBase64Png(prepared.readImage)
                if (verifier != null) verifyQueue += file.name to b64
                val panel = reader.readPanel(b64)
                report.appendLine(
                    "--- ${file.name} (${img.width}×${img.height}, ${prepared.cropMode}) " +
                        "quoted=${panel?.quoted} inManifest=${panel?.inManifest} toRefine=${panel?.toRefine}",
                )
                panel?.rows?.forEach { r ->
                    report.appendLine(
                        "    ${r.name} | quality=${r.quality} qty=${r.qty} yield=${r.yield_} " +
                            "refine=${r.refine}${if (r.partial) " PARTIAL" else ""}",
                    )
                }
                if (panel != null) reads += ImageRead(file.name, panel)
            }
            if (reads.isEmpty()) {
                report.appendLine("  !! no readable panel in this order")
                return@forEach
            }
            var stitched = Stitcher.stitch(reads)
            var crossCheck: CrossModelVerify.Outcome? = null
            if (verifier != null) {
                val secondReads = verifyQueue.mapNotNull { (name, b64) ->
                    verifier.readPanel(b64)?.let { ImageRead(name, it) }
                }
                if (secondReads.size == verifyQueue.size) {
                    val secondStitch = Stitcher.stitch(secondReads)
                    crossCheck = CrossModelVerify.merge(stitched, secondStitch)
                    stitched = stitched.copy(rows = crossCheck.rows)
                    report.appendLine(
                        "  VERIFY ($verifyName) comparable=${crossCheck.comparable} " +
                            "corrected=${crossCheck.corrected} contested=${crossCheck.contested}",
                    )
                    if (!crossCheck.comparable) {
                        report.appendLine("    primary rows=${stitched.rows.size}, secondary rows=${secondStitch.rows.size}:")
                        secondStitch.rows.forEach { r ->
                            report.appendLine("      2nd: ${r.name} | quality=${r.quality} qty=${r.qty} yield=${r.yield_} refine=${r.refine}")
                        }
                    }
                } else {
                    report.appendLine("  VERIFY ($verifyName) incomplete second read — skipped")
                }
            }
            val validated = Validation.validate(stitched, crossCheck)
            report.appendLine(
                "  VALIDATED quoted=${validated.quoted} toRefine=${validated.toRefineTotal} " +
                    "warnings=${validated.warnings}",
            )
            validated.goods.forEach { g ->
                report.appendLine(
                    "    [${g.rowIndex}] ${g.rawMaterialName} quality=${g.quality} in=${g.inputQuantity} " +
                        "out=${g.outputQuantity} refine=${g.refine} conf=${g.confidence}",
                )
            }
            actualOrders[folder.name] = ExpectedOrder(
                toRefine = validated.toRefineTotal,
                rows = validated.goods.map {
                    "${it.rawMaterialName}|${it.quality}|${it.inputQuantity}|${it.outputQuantity}|${it.refine}"
                },
            )
        }

        val diffs = diffAgainstExpected(actualOrders, report)

        val out = File(System.getenv("PROMPT_SMOKE_OUT") ?: "build/prompt-smoke.txt")
        out.absoluteFile.parentFile?.mkdirs()
        out.writeText(report.toString())
        println("prompt smoke report -> ${out.absolutePath}")
        check(diffs == 0) { "$diffs golden-expected mismatch(es) — see ${out.absolutePath}" }
    }

    /**
     * Diff the run against the golden-expected file (env-gated): returns the mismatch count and
     * appends a DIFF section to the report. With `PROMPT_SMOKE_WRITE_EXPECTED=1` (or a missing
     * file) the current state is WRITTEN as the new expectation instead — bootstrap/update path.
     */
    private fun diffAgainstExpected(actual: Map<String, ExpectedOrder>, report: StringBuilder): Int {
        val path = System.getenv("PROMPT_SMOKE_EXPECTED")?.takeUnless { it.isBlank() } ?: return 0
        val file = File(path)
        val json = Json { prettyPrint = true }
        if (System.getenv("PROMPT_SMOKE_WRITE_EXPECTED") == "1" || !file.exists()) {
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(json.encodeToString(actual))
            report.appendLine("=== EXPECTED written -> ${file.absolutePath} ===")
            return 0
        }
        val expected: Map<String, ExpectedOrder> = json.decodeFromString(file.readText())
        var diffs = 0
        report.appendLine("=== DIFF vs ${file.absolutePath} ===")
        (expected.keys + actual.keys).distinct().sorted().forEach { order ->
            val e = expected[order]
            val a = actual[order]
            when {
                e == null -> report.appendLine("  + $order: not in the expected file (new order?)")
                a == null -> {
                    diffs++
                    report.appendLine("  ! $order: produced no validated order")
                }
                e == a -> Unit
                else -> {
                    diffs++
                    report.appendLine("  ! $order: toRefine ${e.toRefine} -> ${a.toRefine}")
                    (e.rows.filterNot { it in a.rows }).forEach { report.appendLine("    - $it") }
                    (a.rows.filterNot { it in e.rows }).forEach { report.appendLine("    + $it") }
                }
            }
        }
        report.appendLine(if (diffs == 0) "  all orders match" else "  $diffs order(s) differ")
        return diffs
    }

    private fun toBase64Png(img: java.awt.image.BufferedImage): String {
        val buffer = ByteArrayOutputStream()
        ImageIO.write(img, "png", buffer)
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
}
