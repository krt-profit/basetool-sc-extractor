package com.basetool.bpextractor.refinery

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Manual smoke harness for [PanelOcr] ([TextDetector] + [DigitOcr]) that prints the recovered numeric
 * grid of real normalized panels. Trivially green unless `OCR_DET_MODEL`, `OCR_REC_MODEL` and
 * `PANEL_DIR` are all set; `OCR_DICT` names the dictionary of a model without ONNX `character`
 * metadata.
 */
class PanelOcrSmokeTest {

    @Test
    fun `read numeric grid from normalized panels`() {
        val detPath = System.getenv("OCR_DET_MODEL")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        val recPath = System.getenv("OCR_REC_MODEL")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        val panelDir = System.getenv("PANEL_DIR")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        require(detPath.isFile) { "OCR_DET_MODEL is not a file: $detPath" }
        require(recPath.isFile) { "OCR_REC_MODEL is not a file: $recPath" }
        require(panelDir.isDirectory) { "PANEL_DIR is not a directory: $panelDir" }

        val only = System.getenv("PANEL_FILTER")?.takeUnless { it.isBlank() }
        val panels = panelDir.listFiles { f -> f.extension.lowercase() == "png" }!!
            .filter { only == null || it.name.contains(only) }
            .sortedBy { it.name }
        check(panels.isNotEmpty()) { "no matching .png panels in $panelDir" }

        val gridOut = System.getenv("OCR_GRID_OUT")?.takeUnless { it.isBlank() }?.let(::File)
        val json = StringBuilder("{\n")
        val dictionary = System.getenv("OCR_DICT")?.takeUnless { it.isBlank() }?.let { OcrModels.readDictionary(File(it).readText()) }
        PanelOcr(detPath.toPath(), recPath.toPath(), dictionary = dictionary).use { ocr ->
            panels.forEachIndexed { pi, f ->
                println("\n===== ${f.name} =====")
                val grid = ocr.readNumericGrid(ImageIO.read(f))
                grid.forEach { row ->
                    val cells = row.joinToString("   ") { c ->
                        "${c.digits}@[${c.box.x0},${c.box.y0}-${c.box.x1},${c.box.y1}]"
                    }
                    println("  y~${row.firstOrNull()?.box?.cy?.toString()?.padStart(4)}  $cells")
                }
                if (gridOut != null) {
                    val cells = grid.flatten().joinToString(",") { c ->
                        """{"d":"${c.digits}","x0":${c.box.x0},"y0":${c.box.y0},"x1":${c.box.x1},"y1":${c.box.y1}}"""
                    }
                    json.append("""  "${f.name}": [$cells]""")
                    json.append(if (pi < panels.lastIndex) ",\n" else "\n")
                }
            }
        }
        if (gridOut != null) {
            json.append("}\n")
            gridOut.writeText(json.toString())
            println("grids -> ${gridOut.absolutePath}")
        }
    }
}
