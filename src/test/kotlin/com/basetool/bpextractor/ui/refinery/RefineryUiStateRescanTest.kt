package com.basetool.bpextractor.ui.refinery

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The §5.2 folder watch: [RefineryUiState.rescanFolder] diffs the folder against the grid. */
class RefineryUiStateRescanTest {

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("rescan-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun pngFile(dir: File, name: String): File {
        val img = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
        val file = File(dir, name)
        ImageIO.write(img, "png", file)
        return file
    }

    /** A grid tile as the initial load would have produced it (thumbnail irrelevant here). */
    private fun tile(file: File, selected: Boolean = true) =
        RefineryImage(file, width = 4, height = 3, precropped = false, thumbnail = null, selected = selected)

    /** One synchronous watch tick: the IO coroutine completes before runBlocking returns. */
    private fun rescan(state: RefineryUiState, path: String) =
        runBlocking { state.rescanFolder(this, path) }

    @Test
    fun `new files appear while existing checkbox choices survive`() = withDir { dir ->
        val a = pngFile(dir, "a.png")
        val b = pngFile(dir, "b.png")
        val state = RefineryUiState()
        state.loadedFolder = dir.absolutePath
        state.images.addAll(listOf(tile(a, selected = false), tile(b)))

        pngFile(dir, "c.png")
        rescan(state, dir.absolutePath)

        assertEquals(listOf("a.png", "b.png", "c.png"), state.images.map { it.file.name })
        // The deselected tile stays deselected; the new arrival is selected by default.
        assertEquals(listOf(false, true, true), state.images.map { it.selected })
    }

    @Test
    fun `vanished folder files drop out but images from other folders stay`() = withDir { dir ->
        withDir { other ->
            val a = pngFile(dir, "a.png")
            val pasted = pngFile(other, "clipboard.png")
            val state = RefineryUiState()
            state.loadedFolder = dir.absolutePath
            state.images.addAll(listOf(tile(a), tile(pasted)))

            assertTrue(a.delete())
            rescan(state, dir.absolutePath)

            assertEquals(listOf("clipboard.png"), state.images.map { it.file.name })
        }
    }

    @Test
    fun `images removed via the tile are not re-added by a rescan`() = withDir { dir ->
        val a = pngFile(dir, "a.png")
        val state = RefineryUiState()
        state.loadedFolder = dir.absolutePath
        val tileA = tile(a)
        state.images.add(tileA)

        state.removeImage(tileA)
        rescan(state, dir.absolutePath)

        assertTrue(state.images.isEmpty())
    }

    @Test
    fun `rescan is a no-op when the path is not the loaded folder`() = withDir { dir ->
        pngFile(dir, "a.png")
        val state = RefineryUiState()
        rescan(state, dir.absolutePath)
        assertTrue(state.images.isEmpty())
    }
}
