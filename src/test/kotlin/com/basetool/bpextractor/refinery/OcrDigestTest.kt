package com.basetool.bpextractor.refinery

import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The regression check for an **onnxruntime or OCR-model bump**: a deterministic digest of what the
 * classical-OCR cross-reader sees across the whole sample corpus.
 *
 * <p>ONNX Runtime's CPU inference is deterministic, so a clean dependency bump is **bit-for-bit
 * identical** — verified 1.22.0 vs 1.27.0 (2026-07-01) and 1.29.0 vs 1.30.0 (2026-09-14, 35 orders
 * / 1366 cells, same SHA-256). That makes this a far sharper instrument than the live VLM sweep for
 * this one question: it runs offline, needs no Ollama, finishes in under a minute, and **any**
 * difference at all is a real change in what the reader recognises — there is no run noise to argue
 * about. `PromptSmokeTest` still owns the end-to-end contract; onnxruntime never touches the VLM.
 *
 * <p>Run it once before the bump and once after, and diff the two files:
 *
 * ```powershell
 * $env:OCR_DIGEST_DIR = "<the sample corpus>"      # one folder per order
 * $env:OCR_DIGEST_OUT = "<somewhere outside the repo>\ocr-digest-before.txt"
 * .\gradlew.bat test --tests '*OcrDigestTest*' --rerun-tasks
 * ```
 *
 * <p>Trivially green when `OCR_DIGEST_DIR` is unset. Reads a local folder and writes outside the
 * repo — the corpus is private (guardrail 1a) and nothing derived from it is committed.
 */
class OcrDigestTest {

    @Test
    fun `the OCR cross-reader sees the same cells across the corpus`() {
        val corpus = System.getenv("OCR_DIGEST_DIR")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        require(corpus.isDirectory) { "OCR_DIGEST_DIR is not a directory: $corpus" }
        require(System.getenv("OCR_MODELS_DIR").isNullOrBlank()) {
            "unset OCR_MODELS_DIR — this must exercise the BUNDLED models, which are what ships"
        }
        val ocr = OcrModels.get() ?: error("bundled OCR models did not load from the /ocr/ resources")

        // Natural order, so "Auftrag 2" sorts before "Auftrag 10" and the digest of a corpus does
        // not depend on the platform's collation.
        val orders = corpus.listFiles { f: File -> f.isDirectory }.orEmpty().sortedWith(NATURAL)
        check(orders.isNotEmpty()) { "no order folders in $corpus" }

        val lines = StringBuilder()
        var cells = 0
        for (order in orders) {
            val images = order.listFiles { f: File -> f.extension.lowercase() in IMAGE_EXTENSIONS }
                .orEmpty().sortedWith(NATURAL)
            for (image in images) {
                val prepared = Locate.prepare(ImageIO.read(image) ?: error("unreadable image: $image"))
                val grid = ocr.readNumericGrid(prepared.readImage)
                lines.append("${order.name}/${image.name}  crop=${prepared.cropMode}\n")
                grid.forEach { row ->
                    lines.append("  ")
                    lines.append(
                        row.joinToString(" ") { c ->
                            cells++
                            "${c.digits}@${c.box.x0},${c.box.y0}-${c.box.x1},${c.box.y1}"
                        },
                    )
                    lines.append('\n')
                }
            }
        }

        val sha = MessageDigest.getInstance("SHA-256")
            .digest(lines.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        println("OCR digest: ${orders.size} orders, $cells cells, sha256=$sha")
        System.getenv("OCR_DIGEST_OUT")?.takeUnless { it.isBlank() }?.let(::File)?.let {
            it.writeText("sha256=$sha\ncells=$cells\n\n$lines")
            println("digest -> ${it.absolutePath}")
        }
        check(cells > 0) { "the bundled OCR reader found no numeric cells in $corpus at all" }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

        /** Digits inside a name compare numerically, so `Auftrag 2` precedes `Auftrag 10`. */
        val NATURAL = Comparator<File> { a, b -> naturalKey(a.name).compareTo(naturalKey(b.name)) }

        fun naturalKey(name: String): String =
            Regex("""\d+""").replace(name.lowercase()) { it.value.padStart(12, '0') }
    }
}
