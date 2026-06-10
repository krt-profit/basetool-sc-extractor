package com.basetool.bpextractor.refinery

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the classical-CV Locate + Normalize stages against synthetic frames that reproduce the
 * verified colour anchors (maroon tab strip ~RGB(72,49,45), orange CTA) at known positions —
 * including the owner rule "leftmost panel = newest order = extraction target" with two panels
 * side by side (the Auftrag 2 layout).
 */
class LocateTest {

    private val maroon = Color(72, 49, 45)
    private val cta = Color(231, 126, 35)
    private val dark = Color(18, 18, 18)

    /** Paint a synthetic panel: a maroon tab strip plus an orange CTA bar near the bottom. */
    private fun paintPanel(img: BufferedImage, x: Int, y: Int, panelWidth: Int, panelHeight: Int) {
        val g = img.createGraphics()
        try {
            // Tab strip: left part of the panel, a few dozen native rows tall.
            g.color = maroon
            g.fillRect(x, y, (panelWidth * 0.55).toInt(), 36)
            // CTA button: right-aligned near the panel bottom.
            g.color = cta
            g.fillRect(x + (panelWidth * 0.55).toInt(), y + panelHeight - 60, (panelWidth * 0.4).toInt(), 40)
        } finally {
            g.dispose()
        }
    }

    private fun frame(width: Int = 3840, height: Int = 2160): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = dark
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return img
    }

    @Test
    fun `finds a single panel via the colour anchors`() {
        val img = frame()
        paintPanel(img, x = 1000, y = 400, panelWidth = 900, panelHeight = 1400)

        val boxes = Locate.locatePanels(img)

        assertEquals(1, boxes.size)
        val box = boxes.single()
        // The strip anchors the left edge; tolerances cover the 1/4-scale rounding + margins.
        assertTrue(box.x in 900..1010, "x=${box.x}")
        assertTrue(box.y in 250..410, "y=${box.y}")
        assertTrue(box.x + box.width >= 1850, "right=${box.x + box.width}")
        assertTrue(box.y + box.height >= 1700, "bottom=${box.y + box.height}")
    }

    @Test
    fun `with two panels side by side the LEFTMOST is the extraction target`() {
        // Auftrag 2 layout: the newest order's SETUP panel sits LEFT of a running second panel.
        val img = frame()
        paintPanel(img, x = 700, y = 400, panelWidth = 800, panelHeight = 1400)
        paintPanel(img, x = 2100, y = 400, panelWidth = 800, panelHeight = 1400)

        val boxes = Locate.locatePanels(img)
        val target = Locate.locatePanel(img)

        assertEquals(2, boxes.size)
        assertTrue(boxes[0].x < boxes[1].x, "candidates must come back left to right")
        assertEquals(boxes[0], target, "owner rule: leftmost = newest = target")
        assertTrue(target.x in 600..710)
        // The left panel's box must not bleed into the right panel.
        assertTrue(target.x + target.width < 2100, "right edge ${target.x + target.width} bleeds into the neighbour")
    }

    @Test
    fun `no anchors falls back to the scaled 4K geometry`() {
        val img = frame(1920, 1080)

        val boxes = Locate.locatePanels(img)
        val fallback = Locate.locatePanel(img)

        assertTrue(boxes.isEmpty())
        // 4K geometry (950, 350, 920, 1500) at half scale.
        assertEquals(475, fallback.x)
        assertEquals(175, fallback.y)
        assertEquals(460, fallback.width)
        assertEquals(750, fallback.height)
    }

    @Test
    fun `precropped detection matches the golden-set shapes`() {
        assertTrue(Locate.isPrecropped(500, 1500), "a ~500px portrait panel crop")
        assertFalse(Locate.isPrecropped(3840, 2160), "a full 4K frame")
        assertFalse(Locate.isPrecropped(990, 700), "small but landscape is not a panel crop")
    }

    @Test
    fun `snap32 rounds to multiples of 32 with a floor of 32`() {
        assertEquals(1536, Locate.snap32(1536))
        assertEquals(1536, Locate.snap32(1530))
        assertEquals(1568, Locate.snap32(1553))
        assertEquals(32, Locate.snap32(1))
    }

    @Test
    fun `normalize hits the sweet spot and snaps dimensions`() {
        val img = frame(3840, 2160)

        val out = Locate.normalize(img)

        assertTrue(out.width % 32 == 0 && out.height % 32 == 0)
        assertEquals(Locate.TARGET_LONG_EDGE, maxOf(out.width, out.height))
    }

    @Test
    fun `normalize caps the upscale of pre-cropped panels`() {
        val img = frame(500, 900)

        val out = Locate.normalize(img)

        assertTrue(maxOf(out.width, out.height) <= Locate.PRECROP_MAX_DIM + 31, "cap ~${Locate.PRECROP_MAX_DIM}")
    }

    @Test
    fun `prepare routes pre-cropped input past Locate and skips the location strip`() {
        val img = frame(500, 1400)

        val prepared = Locate.prepare(img)

        assertEquals("precropped", prepared.cropMode)
        assertNull(prepared.panelBox)
        assertNull(prepared.locationImage)
    }

    @Test
    fun `prepare yields a location strip for full frames`() {
        val img = frame()
        paintPanel(img, x = 1000, y = 400, panelWidth = 900, panelHeight = 1400)

        val prepared = Locate.prepare(img)

        assertEquals("vlm", prepared.cropMode)
        assertNotNull(prepared.panelBox)
        assertNotNull(prepared.locationImage)
        assertTrue(prepared.readImage.width % 32 == 0)
    }

    @Test
    fun `gap-tolerant runs bridge text holes in the strip`() {
        val matches = BooleanArray(40)
        for (x in 0..10) matches[x] = true
        for (x in 14..30) matches[x] = true // a 3-px hole (strip text) must not split the run

        val runs = Locate.runs(matches, maxGap = 6)

        assertEquals(listOf(0 to 30), runs)
    }
}
