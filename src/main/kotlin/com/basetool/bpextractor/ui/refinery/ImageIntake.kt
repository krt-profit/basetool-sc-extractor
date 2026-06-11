package com.basetool.bpextractor.ui.refinery

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Intake of refinery screenshots that do not come from the picked folder: clipboard pastes
 * (Strg+V, e.g. the Windows snipping tool) and external drag & drop. Both routes persist the
 * image into the picked folder when one is set, otherwise into a session temp folder that a JVM
 * shutdown hook removes again — the app must stay residue-free on disk (CLAUDE.md guardrail 2).
 *
 * Pure AWT/file logic, no Compose — [RefineryUiState.importTransferable] does the state glue.
 */
object ImageIntake {
    /** The image file extensions the §5.2 folder loader accepts — intake mirrors it. */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    private var tempDir: File? = null

    /** The session temp folder for pastes/drops without a picked folder (created lazily). */
    @Synchronized
    fun tempFolder(): File {
        tempDir?.takeIf { it.isDirectory }?.let { return it }
        val dir = Files.createTempDirectory("krt-sc-extractor-").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        tempDir = dir
        return dir
    }

    /** True when [file] sits in the session temp folder (drives the §5.2 temp-folder note). */
    fun isTempFile(file: File): Boolean =
        tempDir?.let { file.absoluteFile.parentFile == it.absoluteFile } == true

    /** True when [t] carries something the intake can use: image files or a raw image. */
    fun accepts(t: Transferable): Boolean =
        imageFilesFrom(t).isNotEmpty() || t.isDataFlavorSupported(DataFlavor.imageFlavor)

    /** The image files carried by [t] (Explorer copy/drag), filtered to [IMAGE_EXTENSIONS]. */
    fun imageFilesFrom(t: Transferable): List<File> {
        if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        val list = runCatching { t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> }.getOrNull()
        return list.orEmpty().filterIsInstance<File>()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
    }

    /** The raw image carried by [t] (snipping tool & friends), or null. */
    fun rawImageFrom(t: Transferable): BufferedImage? {
        if (!t.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        val img = runCatching { t.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return null
        return toBuffered(img)
    }

    /** Write [image] as PNG into [dir] under a fresh `clipboard*.png` name. */
    fun saveClipboardImage(image: BufferedImage, dir: File): File {
        val target = uniqueTarget(dir, "clipboard", "png")
        ImageIO.write(image, "png", target)
        return target
    }

    /**
     * Persist a dropped [file] into [dir]: a no-op when it already lives there, a re-drop of
     * byte-identical content reuses the existing copy, and a genuine name clash dodges to a
     * `name-2.ext` style target.
     */
    fun copyInto(file: File, dir: File): File {
        val source = file.absoluteFile
        if (source.parentFile == dir.absoluteFile) return source
        val direct = File(dir, source.name)
        if (!direct.exists()) return source.copyTo(direct)
        if (direct.length() == source.length() && direct.readBytes().contentEquals(source.readBytes())) {
            return direct
        }
        return source.copyTo(uniqueTarget(dir, source.nameWithoutExtension, source.extension))
    }

    /** First non-existing `base.ext`, `base-2.ext`, `base-3.ext`, … inside [dir]. */
    fun uniqueTarget(dir: File, base: String, ext: String): File {
        val plain = File(dir, "$base.$ext")
        if (!plain.exists()) return plain
        var i = 2
        while (true) {
            val candidate = File(dir, "$base-$i.$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    /** Render any AWT [image] (clipboard images are often not [BufferedImage]) into one. */
    fun toBuffered(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val w = maxOf(1, image.getWidth(null))
        val h = maxOf(1, image.getHeight(null))
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
