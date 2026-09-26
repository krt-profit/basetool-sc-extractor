package com.basetool.bpextractor.refinery

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [GlyphTopology] on synthetic glyphs: white strokes on the terminal's dark background, each a
 * solid block with the holes the HUD font's digit encloses.
 */
class GlyphTopologyTest {

    /** A hole as fractions of the glyph box: left, top, right, bottom. */
    private data class Cut(val l: Double, val t: Double, val r: Double, val b: Double)

    private val shapes = mapOf(
        "8" to listOf(Cut(0.3, 0.15, 0.7, 0.42), Cut(0.3, 0.58, 0.7, 0.85)),
        "0" to listOf(Cut(0.2, 0.15, 0.5, 0.45), Cut(0.5, 0.55, 0.8, 0.85)),
        "6" to listOf(Cut(0.3, 0.52, 0.7, 0.85)),
        "9" to listOf(Cut(0.3, 0.15, 0.7, 0.48)),
        "6hook" to listOf(Cut(0.4, 0.2, 0.6, 0.33), Cut(0.3, 0.52, 0.7, 0.85)),
        "1" to emptyList(),
    )

    private fun cell(vararg digits: String): BufferedImage {
        val gw = 14
        val gh = 22
        val gap = 6
        val img = BufferedImage(8 + digits.size * (gw + gap), gh + 8, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(28, 30, 30)
        g.fillRect(0, 0, img.width, img.height)
        digits.forEachIndexed { i, d ->
            val x0 = 4 + i * (gw + gap)
            val y0 = 4
            g.color = Color(235, 230, 215)
            g.fillRect(x0, y0, gw, gh)
            g.color = Color(28, 30, 30)
            for (c in shapes.getValue(d)) {
                g.fillRect(
                    x0 + (c.l * gw).toInt(),
                    y0 + (c.t * gh).toInt(),
                    ((c.r - c.l) * gw).toInt(),
                    ((c.b - c.t) * gh).toInt(),
                )
            }
        }
        g.dispose()
        return img
    }

    @Test
    fun `stacked equal holes are an 8 and diagonal ones a slashed 0`() {
        assertEquals("80", GlyphTopology.read(cell("8", "0"), 2))
    }

    @Test
    fun `a lone low hole is a 6 and a lone high one a 9`() {
        assertEquals("69", GlyphTopology.read(cell("6", "9"), 2))
    }

    @Test
    fun `a 6 whose hook blurred shut into a small second hole is still a 6`() {
        assertEquals("6", GlyphTopology.read(cell("6hook"), 1))
    }

    @Test
    fun `a glyph without holes is not one of the four`() {
        assertEquals("?", GlyphTopology.read(cell("1"), 1))
    }

    @Test
    fun `a cell that splits into a different number of glyphs is not read`() {
        assertNull(GlyphTopology.read(cell("8", "0"), 3))
    }

    @Test
    fun `arbitration picks the reading the glyph shows and abstains otherwise`() {
        val img = cell("9", "8")
        assertEquals("98", GlyphTopology.arbitrate(img, "98", "96"))
        assertEquals("98", GlyphTopology.arbitrate(img, "90", "98"))
        assertNull(GlyphTopology.arbitrate(img, "96", "90"))
        assertEquals("98", GlyphTopology.arbitrate(img, "98", "68"))
        assertNull(GlyphTopology.arbitrate(img, "98", "17"))
    }
}
