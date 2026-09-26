package com.basetool.bpextractor.refinery

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OcrModelsTest {

    @Test
    fun `a dictionary keeps whitespace entries and drops only line endings`() {
        assertEquals(listOf("!", " ", "0", "1"), OcrModels.readDictionary("﻿!\r\n \r\n0\n1\n"))
        assertEquals(listOf("a", "b"), OcrModels.readDictionary("a\nb"))
    }

    @Test
    fun `the bundled dictionary matches the bundled recognition model`() {
        val rec = resource(OcrModels.REC_FILE)
        val dictionary = OcrModels.readDictionary(resource(OcrModels.DICT_FILE).toString(Charsets.UTF_8))

        DigitOcr(rec, dictionary).close()
        assertFailsWith<IllegalStateException> { DigitOcr(rec, dictionary.drop(1)) }
    }

    @Test
    fun `the bundled models read rendered table digits`() {
        val ocr = assertNotNull(OcrModels.get(), "bundled OCR models did not load")
        val rows = listOf(listOf(330L, 6062L, 2728L), listOf(517L, 3193L, 1437L), listOf(874L, 426L, 191L))

        val read = ocr.readNumericGrid(renderTable(rows)).map { row -> row.map { it.value } }

        assertEquals(rows, read)
    }

    @Test
    fun `the bundled models survive a panel without digits`() {
        val ocr = assertNotNull(OcrModels.get())
        val blank = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)

        assertTrue(ocr.readNumericGrid(blank).isEmpty())
    }

    private fun resource(name: String): ByteArray =
        assertNotNull(OcrModels::class.java.getResourceAsStream("/ocr/$name"), "missing /ocr/$name").use { it.readBytes() }

    private fun renderTable(rows: List<List<Long>>): BufferedImage {
        val font = OcrModelsTest::class.java.getResourceAsStream("/fonts/Lato-Bold.ttf")!!.use {
            Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(26f)
        }
        val image = BufferedImage(640, 80 + rows.size * 75, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().run {
            color = Color(24, 27, 25)
            fillRect(0, 0, image.width, image.height)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            this.font = font
            color = Color(240, 234, 222)
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, value -> drawString(value.toString(), 300 + c * 110, 80 + r * 75) }
            }
            dispose()
        }
        return image
    }
}
