package com.basetool.bpextractor.refinery

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Dev scaffolding (NOT a behaviour test): writes the NORMALIZED panel image for every golden
 * order — exactly the `Locate.prepare(...).readImage` the in-app OCR cross-check will run on — so
 * the classical-OCR cell-finder can be ground-truthed against the same pixels the pipeline feeds.
 *
 * Trivially green unless `PANEL_DUMP_DIR` (a folder of order folders, e.g. the golden set) AND
 * `PANEL_DUMP_OUT` (an output dir) are both set. Output goes to `<out>/<order>__<image>.png`. The
 * inputs are PRIVATE captures (guardrail 1a); the dumped panels are derived from them — they are
 * written OUTSIDE the repo and must never be committed.
 */
class PanelDumpTest {

    @Test
    fun `dump normalized panels for the golden set`() {
        val root = System.getenv("PANEL_DUMP_DIR")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        val outDir = System.getenv("PANEL_DUMP_OUT")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        require(root.isDirectory) { "PANEL_DUMP_DIR is not a directory: $root" }
        outDir.mkdirs()

        val orders = root.listFiles { f: File -> f.isDirectory }!!
            .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
        var dumped = 0
        orders.forEach { folder ->
            val images = folder.listFiles { f: File -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }!!
                .sortedBy { it.name }
            images.forEach { file ->
                val img = ImageIO.read(file) ?: return@forEach
                val precropped = Locate.isPrecropped(img.width, img.height)
                val box = if (precropped) null else Locate.locatePanel(img)
                val prepared = Locate.prepare(img, box)
                val safeOrder = folder.name.replace(Regex("\\s+"), "_")
                val safeImg = file.nameWithoutExtension.replace(Regex("\\s+"), "_")
                val target = File(outDir, "${safeOrder}__${safeImg}.png")
                ImageIO.write(prepared.readImage, "png", target)
                dumped++
                println("  ${target.name}  ${prepared.readImage.width}x${prepared.readImage.height} (${prepared.cropMode})")
            }
        }
        println("dumped $dumped normalized panel(s) -> ${outDir.absolutePath}")
    }
}
