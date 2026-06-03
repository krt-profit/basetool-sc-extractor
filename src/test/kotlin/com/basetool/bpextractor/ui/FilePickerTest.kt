package com.basetool.bpextractor.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePickerTest {

    private fun tempDir(): File = Files.createTempDirectory("picker-test").toFile()

    private fun namesOf(dir: File?, mode: PickerMode, ext: String = "json"): List<String> {
        val listing = listChildren(dir, mode, ext)
        assertTrue(listing is Listing.Ok, "expected Listing.Ok but got $listing")
        return listing.entries.map { it.file.name }
    }

    @Test
    fun `folder mode lists only directories, sorted case-insensitively`() {
        val dir = tempDir()
        try {
            File(dir, "Zeta").mkdirs()
            File(dir, "alpha").mkdirs()
            File(dir, "Beta").mkdirs()
            File(dir, "ignored.json").writeText("x")
            File(dir, "ignored.txt").writeText("x")

            assertEquals(listOf("alpha", "Beta", "Zeta"), namesOf(dir, PickerMode.FOLDER))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `save mode lists directories first, then matching files (extension case-insensitive)`() {
        val dir = tempDir()
        try {
            File(dir, "sub").mkdirs()
            File(dir, "a.json").writeText("x")
            File(dir, "B.JSON").writeText("x") // matches .json case-insensitively
            File(dir, "c.txt").writeText("x")  // excluded

            val names = namesOf(dir, PickerMode.SAVE_FILE, "json")

            assertEquals(listOf("sub", "a.json", "B.JSON"), names)
            assertFalse(names.contains("c.txt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `listing a non-existent directory is Denied, not an exception`() {
        val dir = tempDir()
        try {
            val missing = File(dir, "does-not-exist")
            assertEquals(Listing.Denied, listChildren(missing, PickerMode.FOLDER, "json"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `null directory yields the drive roots`() {
        val listing = listChildren(null, PickerMode.FOLDER, "json")
        assertTrue(listing is Listing.Ok)
        val roots = listing.entries
        assertTrue(roots.isNotEmpty(), "expected at least one drive root")
        assertTrue(roots.all { it.isDirectory }, "roots should all be directories")
    }

    @Test
    fun `ensureExtension appends only when missing`() {
        assertEquals("a.json", ensureExtension("a", "json"))
        assertEquals("a.json", ensureExtension("a.json", "json"))
        assertEquals("a.JSON", ensureExtension("a.JSON", "json")) // already has it (case-insensitive)
        assertEquals("a.txt.json", ensureExtension("a.txt", "json"))
        assertEquals("a.json", ensureExtension("a", ".json")) // leading dot tolerated
        assertEquals("a", ensureExtension("a", "")) // no extension configured
    }

    @Test
    fun `initialDirectory resolves a folder, a file's parent, and blank`() {
        val dir = tempDir()
        try {
            val file = File(dir, "out.json")
            assertEquals(dir.absolutePath, initialDirectory(dir.absolutePath, PickerMode.FOLDER)?.absolutePath)
            assertEquals(dir.absolutePath, initialDirectory(file.absolutePath, PickerMode.FOLDER)?.absolutePath)
            assertEquals(dir.absolutePath, initialDirectory(file.absolutePath, PickerMode.SAVE_FILE)?.absolutePath)
            assertNull(initialDirectory("", PickerMode.FOLDER))
            assertNull(initialDirectory("   ", PickerMode.SAVE_FILE))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `initialFileName takes the last segment or the default`() {
        assertEquals("blueprints.json", initialFileName("C:\\Users\\x\\blueprints.json", "default.json"))
        assertEquals("default.json", initialFileName("", "default.json"))
        assertEquals("default.json", initialFileName("   ", "default.json"))
    }

    @Test
    fun `resolveTypedPath handles directories, quoted paths, files and unknowns`() {
        val dir = tempDir()
        try {
            val sub = File(dir, "sub").apply { mkdirs() }
            val file = File(dir, "out.json")

            // a plain directory
            assertEquals(sub.absolutePath, resolveTypedPath(sub.absolutePath, PickerMode.FOLDER)?.dir?.absolutePath)
            // Windows Explorer "Copy as path" wraps the path in quotes
            assertEquals(sub.absolutePath, resolveTypedPath("\"" + sub.absolutePath + "\"", PickerMode.FOLDER)?.dir?.absolutePath)
            // a (possibly non-existent) file path in SAVE mode -> parent dir + filename
            val saved = resolveTypedPath(file.absolutePath, PickerMode.SAVE_FILE)
            assertEquals(dir.absolutePath, saved?.dir?.absolutePath)
            assertEquals("out.json", saved?.fileName)
            // the same path in FOLDER mode -> parent dir, no filename
            val folder = resolveTypedPath(file.absolutePath, PickerMode.FOLDER)
            assertEquals(dir.absolutePath, folder?.dir?.absolutePath)
            assertNull(folder?.fileName)
            // empty input -> drive roots (dir == null), not an error
            assertEquals(TypedPath(null, null), resolveTypedPath("   ", PickerMode.FOLDER))
            // unknown path (parent doesn't exist either) -> null
            assertNull(resolveTypedPath(File(dir, "nope\\deeper").absolutePath, PickerMode.FOLDER))
        } finally {
            dir.deleteRecursively()
        }
    }
}
