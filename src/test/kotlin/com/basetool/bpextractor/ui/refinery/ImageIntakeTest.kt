package com.basetool.bpextractor.ui.refinery

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A minimal [Transferable] serving a file list and/or a raw image, like clipboard/drop data. */
private class FakeTransferable(
    private val files: List<File>? = null,
    private val image: BufferedImage? = null,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = buildList {
        if (files != null) add(DataFlavor.javaFileListFlavor)
        if (image != null) add(DataFlavor.imageFlavor)
    }.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor in transferDataFlavors

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor == DataFlavor.javaFileListFlavor && files != null -> files
        flavor == DataFlavor.imageFlavor && image != null -> image
        else -> throw UnsupportedFlavorException(flavor)
    }
}

class ImageIntakeTest {

    private fun tempDir(): File = Files.createTempDirectory("intake-test").toFile()

    private fun pngFile(dir: File, name: String, rgb: Int = 0xFF8800): File {
        val img = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 4) for (y in 0 until 3) img.setRGB(x, y, rgb)
        val file = File(dir, name)
        ImageIO.write(img, "png", file)
        return file
    }

    private fun withDir(block: (File) -> Unit) {
        val dir = tempDir()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `uniqueTarget dodges existing names with a -N suffix`() = withDir { dir ->
        assertEquals("clipboard.png", ImageIntake.uniqueTarget(dir, "clipboard", "png").name)
        pngFile(dir, "clipboard.png")
        assertEquals("clipboard-2.png", ImageIntake.uniqueTarget(dir, "clipboard", "png").name)
        pngFile(dir, "clipboard-2.png")
        assertEquals("clipboard-3.png", ImageIntake.uniqueTarget(dir, "clipboard", "png").name)
    }

    @Test
    fun `imageFilesFrom keeps only existing png-jpg-jpeg files`() = withDir { dir ->
        val png = pngFile(dir, "shot.png")
        val txt = File(dir, "note.txt").apply { writeText("not an image") }
        val missing = File(dir, "ghost.jpg")
        val files = ImageIntake.imageFilesFrom(FakeTransferable(files = listOf(png, txt, missing)))
        assertEquals(listOf(png), files)
    }

    @Test
    fun `imageFilesFrom is empty without a file-list flavor`() {
        val t = FakeTransferable(image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
        assertTrue(ImageIntake.imageFilesFrom(t).isEmpty())
    }

    @Test
    fun `accepts raw images but not unusable content`() = withDir { dir ->
        assertTrue(ImageIntake.accepts(FakeTransferable(image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))))
        val txt = File(dir, "note.txt").apply { writeText("x") }
        assertFalse(ImageIntake.accepts(FakeTransferable(files = listOf(txt))))
    }

    @Test
    fun `saveClipboardImage writes a decodable png`() = withDir { dir ->
        val saved = ImageIntake.saveClipboardImage(BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB), dir)
        assertTrue(saved.isFile)
        val decoded = requireNotNull(ImageIO.read(saved))
        assertEquals(7, decoded.width)
        assertEquals(5, decoded.height)
    }

    @Test
    fun `rawImageFrom converts the transferable image`() {
        val raw = requireNotNull(ImageIntake.rawImageFrom(FakeTransferable(image = BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB))))
        assertEquals(6, raw.width)
        assertEquals(4, raw.height)
    }

    @Test
    fun `copyInto is a no-op for files already in the target folder`() = withDir { dir ->
        val png = pngFile(dir, "shot.png")
        assertEquals(png.absoluteFile, ImageIntake.copyInto(png, dir))
    }

    @Test
    fun `copyInto copies foreign files and reuses byte-identical re-drops`() = withDir { source ->
        withDir { target ->
            val png = pngFile(source, "shot.png")
            val first = ImageIntake.copyInto(png, target)
            assertEquals(File(target, "shot.png").absolutePath, first.absolutePath)
            // Same bytes again -> the existing copy is reused, no duplicate appears.
            val again = ImageIntake.copyInto(png, target)
            assertEquals(first.absolutePath, again.absolutePath)
            assertEquals(1, target.listFiles()!!.size)
            // Different content under the same name -> dodged to clash-2.png.
            val clash = pngFile(source, "clash.png", rgb = 0x123456)
            pngFile(target, "clash.png", rgb = 0x654321)
            assertEquals("clash-2.png", ImageIntake.copyInto(clash, target).name)
        }
    }

    @Test
    fun `tempFolder exists, is reused and marks its files as temp`() {
        val a = ImageIntake.tempFolder()
        val b = ImageIntake.tempFolder()
        assertTrue(a.isDirectory)
        assertEquals(a.absolutePath, b.absolutePath)
        assertTrue(ImageIntake.isTempFile(File(a, "clipboard.png")))
        assertFalse(ImageIntake.isTempFile(File("clipboard.png").absoluteFile))
    }
}
