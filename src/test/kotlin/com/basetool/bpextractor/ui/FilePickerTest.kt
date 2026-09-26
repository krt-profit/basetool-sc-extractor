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
    fun `folder mode lists directories first (files follow for the dimmed display)`() {
        val dir = tempDir()
        try {
            File(dir, "Zeta").mkdirs()
            File(dir, "alpha").mkdirs()
            File(dir, "Beta").mkdirs()
            File(dir, "dimmed.json").writeText("x")
            File(dir, "also-dimmed.txt").writeText("x")

            assertEquals(
                listOf("alpha", "Beta", "Zeta", "also-dimmed.txt", "dimmed.json"),
                namesOf(dir, PickerMode.FOLDER),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sortEntries keeps directories first and honours key plus direction`() {
        val dirA = PickerEntry(File("b-dir"), isDirectory = true, size = 0, modified = 50)
        val dirB = PickerEntry(File("a-dir"), isDirectory = true, size = 0, modified = 10)
        val small = PickerEntry(File("small.json"), isDirectory = false, size = 10, modified = 300)
        val big = PickerEntry(File("big.json"), isDirectory = false, size = 999, modified = 100)
        val all = listOf(small, dirA, big, dirB)

        assertEquals(
            listOf("a-dir", "b-dir", "big.json", "small.json"),
            sortEntries(all, PickerSortKey.NAME, ascending = true).map { it.file.name },
        )
        assertEquals(
            listOf("b-dir", "a-dir", "big.json", "small.json"),
            sortEntries(all, PickerSortKey.SIZE, ascending = false).map { it.file.name },
        )
        assertEquals(
            listOf("a-dir", "b-dir", "big.json", "small.json"),
            sortEntries(all, PickerSortKey.MODIFIED, ascending = true).map { it.file.name },
        )
    }

    @Test
    fun `isValidFileName rejects blanks and reserved characters`() {
        assertTrue(isValidFileName("RefineryExtract.json"))
        assertTrue(isValidFileName("  padded.json  "))
        assertFalse(isValidFileName(""))
        assertFalse(isValidFileName("   "))
        for (bad in listOf("a\\b", "a/b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertFalse(isValidFileName(bad), "expected '$bad' to be invalid")
        }
    }

    @Test
    fun `parentChain walks from the root to the directory`() {
        val dir = tempDir()
        try {
            val sub = File(dir, "sub").apply { mkdirs() }
            val chain = parentChain(sub)
            assertTrue(chain.size >= 2, "expected at least root + sub")
            assertEquals(sub.absolutePath, chain.last().absolutePath)
            assertEquals(dir.absolutePath, chain[chain.size - 2].absolutePath)
            assertNull(chain.first().parentFile, "first element should be a filesystem root")
            assertTrue(parentChain(null).isEmpty())
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
            File(dir, "B.JSON").writeText("x")
            File(dir, "c.txt").writeText("x")

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
        assertEquals("a.JSON", ensureExtension("a.JSON", "json"))
        assertEquals("a.txt.json", ensureExtension("a.txt", "json"))
        assertEquals("a.json", ensureExtension("a", ".json"))
        assertEquals("a", ensureExtension("a", ""))
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

            assertEquals(sub.absolutePath, resolveTypedPath(sub.absolutePath, PickerMode.FOLDER)?.dir?.absolutePath)
            assertEquals(sub.absolutePath, resolveTypedPath("\"" + sub.absolutePath + "\"", PickerMode.FOLDER)?.dir?.absolutePath)
            val saved = resolveTypedPath(file.absolutePath, PickerMode.SAVE_FILE)
            assertEquals(dir.absolutePath, saved?.dir?.absolutePath)
            assertEquals("out.json", saved?.fileName)
            val folder = resolveTypedPath(file.absolutePath, PickerMode.FOLDER)
            assertEquals(dir.absolutePath, folder?.dir?.absolutePath)
            assertNull(folder?.fileName)
            assertEquals(TypedPath(null, null), resolveTypedPath("   ", PickerMode.FOLDER))
            assertNull(resolveTypedPath(File(dir, "nope\\deeper").absolutePath, PickerMode.FOLDER))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolveTypedPath normalizes pasted-path variants`() {
        val dir = tempDir().canonicalFile
        try {
            val sub = File(dir, "sub").apply { mkdirs() }
            val expected = sub.absolutePath

            assertEquals(expected, resolveTypedPath(expected.replace('\\', '/'), PickerMode.FOLDER)?.dir?.absolutePath)
            assertEquals(expected, resolveTypedPath(expected + File.separator, PickerMode.FOLDER)?.dir?.absolutePath)
            assertEquals(expected, resolveTypedPath("'$expected'", PickerMode.FOLDER)?.dir?.absolutePath)
            assertEquals(expected, resolveTypedPath(sub.toURI().toString(), PickerMode.FOLDER)?.dir?.absolutePath)
            assertEquals(expected, resolveTypedPath("\n$expected\nC:\\somewhere\\else", PickerMode.FOLDER)?.dir?.absolutePath)
            val root = File.listRoots().first()
            assertEquals(root.absolutePath, resolveTypedPath(root.path.removeSuffix(File.separator), PickerMode.FOLDER)?.dir?.absolutePath)
            assertEquals(
                File(System.getProperty("user.home")).absolutePath,
                resolveTypedPath("~", PickerMode.FOLDER)?.dir?.absolutePath,
            )
            val savedDir = resolveTypedPath(expected, PickerMode.SAVE_FILE)
            assertEquals(expected, savedDir?.dir?.absolutePath)
            assertNull(savedDir?.fileName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolveTypedPath resolves relative input against a base directory`() {
        val dir = tempDir().canonicalFile
        try {
            val sub = File(dir, "sub").apply { mkdirs() }
            assertEquals(sub.absolutePath, resolveTypedPath("sub", PickerMode.FOLDER, base = dir)?.dir?.absolutePath)
            assertEquals(dir.absolutePath, resolveTypedPath("..", PickerMode.FOLDER, base = sub)?.dir?.absolutePath)
            assertEquals(sub.absolutePath, resolveTypedPath("sub\\..\\sub", PickerMode.FOLDER, base = dir)?.dir?.absolutePath)
            assertNull(resolveTypedPath("no-such-dir-xyz", PickerMode.FOLDER))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `normalizePathInput expands env vars and home, keeps unknown vars literal`() {
        val fakeEnv: (String) -> String? = { name -> if (name == "BASE") "C:\\base" else null }
        assertEquals("C:\\base\\docs", normalizePathInput("%BASE%\\docs", fakeEnv))
        assertEquals("%NOPE%\\docs", normalizePathInput("%NOPE%\\docs", fakeEnv))
        assertEquals("C:\\base", normalizePathInput("  \"%BASE%\"  ", fakeEnv))
        assertEquals("C:\\", normalizePathInput("C:", fakeEnv))
        assertEquals(System.getProperty("user.home"), normalizePathInput("~", fakeEnv))
        assertEquals(System.getProperty("user.home") + "\\sub", normalizePathInput("~\\sub", fakeEnv))
        assertEquals("", normalizePathInput("   ", fakeEnv))
    }
}
