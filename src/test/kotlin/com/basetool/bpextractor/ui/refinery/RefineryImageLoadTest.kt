package com.basetool.bpextractor.ui.refinery

import com.basetool.bpextractor.refinery.Locate
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The §5.2 grid tile decode (SIB-PERF-01): native size from the image header, the crop tag from
 * exactly that size, and a subsampled thumbnail of the same size the full decode used to produce.
 */
class RefineryImageLoadTest {

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("image-load-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun image(dir: File, name: String, width: Int, height: Int, format: String = "png"): File {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = java.awt.Color(0xE7, 0x7E, 0x23)
            g.fillRect(0, 0, width / 2, height / 2)
        } finally {
            g.dispose()
        }
        val file = File(dir, name)
        assertTrue(ImageIO.write(img, format, file))
        return file
    }

    @Test
    fun `a 4K full frame keeps its native size and gets a 240 px thumbnail`() = withDir { dir ->
        val tile = assertNotNull(RefineryUiState.loadImage(image(dir, "full.png", 3840, 2160)))
        assertEquals(3840, tile.width)
        assertEquals(2160, tile.height)
        assertFalse(tile.precropped)
        val thumb = assertNotNull(tile.thumbnail)
        assertEquals(240, thumb.width)
        assertEquals(135, thumb.height)
        assertTrue(tile.selected)
    }

    @Test
    fun `the crop tag is decided on the header size exactly as Locate defines it`() = withDir { dir ->
        val panel = assertNotNull(RefineryUiState.loadImage(image(dir, "panel.png", 500, 1000)))
        assertTrue(panel.precropped)
        assertEquals(Locate.isPrecropped(500, 1000), panel.precropped)
        val thumb = assertNotNull(panel.thumbnail)
        assertEquals(120, thumb.width)
        assertEquals(240, thumb.height)

        val terminal = assertNotNull(RefineryUiState.loadImage(image(dir, "terminal.png", 1200, 1250)))
        assertEquals(Locate.isPrecropped(1200, 1250), terminal.precropped)
        assertFalse(terminal.precropped)
        assertTrue(terminal.lowResolution)
    }

    @Test
    fun `jpeg captures decode too, with odd sizes rounding like the full decode did`() = withDir { dir ->
        val tile = assertNotNull(RefineryUiState.loadImage(image(dir, "shot.jpg", 2561, 1441, "jpg")))
        assertEquals(2561, tile.width)
        assertEquals(1441, tile.height)
        val thumb = assertNotNull(tile.thumbnail)
        assertEquals(RefineryUiState.thumbnailSize(2561, 1441), thumb.width to thumb.height)
        assertEquals(240, thumb.width)
    }

    @Test
    fun `an image smaller than a thumbnail is scaled up to the thumbnail size as before`() = withDir { dir ->
        val tile = assertNotNull(RefineryUiState.loadImage(image(dir, "tiny.png", 100, 50)))
        val thumb = assertNotNull(tile.thumbnail)
        assertEquals(240, thumb.width)
        assertEquals(120, thumb.height)
    }

    @Test
    fun `unreadable and half-written files are skipped, not fatal`() = withDir { dir ->
        val garbage = File(dir, "garbage.png").apply { writeText("not an image") }
        assertNull(RefineryUiState.loadImage(garbage))
        val full = image(dir, "full.png", 1920, 1080).readBytes()
        val truncated = File(dir, "truncated.png").apply { writeBytes(full.copyOf(full.size / 3)) }
        assertNull(RefineryUiState.loadImage(truncated))
        assertNull(RefineryUiState.loadImage(File(dir, "missing.png")))
    }

    @Test
    fun `loadFolder decodes the folder in name order and drops unreadable files`() = withDir { dir ->
        image(dir, "c.png", 1920, 1080)
        image(dir, "a.png", 640, 360)
        File(dir, "b.png").writeText("broken")
        image(dir, "d.jpg", 800, 1600, "jpg")
        File(dir, "notes.txt").writeText("ignored")
        val state = RefineryUiState()

        runBlocking { state.loadFolder(this, dir.absolutePath) }

        assertEquals(listOf("a.png", "c.png", "d.jpg"), state.images.map { it.file.name })
        assertEquals(listOf(640 to 360, 1920 to 1080, 800 to 1600), state.images.map { it.width to it.height })
        assertEquals(listOf(false, false, true), state.images.map { it.precropped })
        assertFalse(state.loadingImages)
    }
}
