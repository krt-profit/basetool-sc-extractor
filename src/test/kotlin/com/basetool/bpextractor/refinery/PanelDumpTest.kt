package com.basetool.bpextractor.refinery

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Dev scaffolding, not a behaviour test: writes the normalized panel image (`Locate.prepare(...)`) of
 * every order to `<out>/<order>__<image>.png`. Trivially green unless `PANEL_DUMP_DIR` and
 * `PANEL_DUMP_OUT` are both set; the output is private and must stay outside the repo.
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
