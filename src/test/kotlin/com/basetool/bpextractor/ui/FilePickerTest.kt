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

            // Directories first (case-insensitive), then every file — the UI dims files in
            // FOLDER mode instead of hiding them (REDESIGN_IMPLEMENTATION.md §10).
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

        // by name ascending: dirs (a-dir, b-dir) first, then files by name
        assertEquals(
            listOf("a-dir", "b-dir", "big.json", "small.json"),
            sortEntries(all, PickerSortKey.NAME, ascending = true).map { it.file.name },
        )
        // by size descending: dirs stay first
        assertEquals(
            listOf("b-dir", "a-dir", "big.json", "small.json"),
            sortEntries(all, PickerSortKey.SIZE, ascending = false).map { it.file.name },
        )
        // by modified ascending
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

    @Test
    fun `resolveTypedPath normalizes pasted-path variants`() {
        // canonicalFile so expected values can't differ from resolved ones via 8dot3 short names
        val dir = tempDir().canonicalFile
        try {
            val sub = File(dir, "sub").apply { mkdirs() }
            val expected = sub.absolutePath

            // forward slashes (paths copied from configs / the web)
            assertEquals(expected, resolveTypedPath(expected.replace('\\', '/'), PickerMode.FOLDER)?.dir?.absolutePath)
            // trailing separator
            assertEquals(expected, resolveTypedPath(expected + File.separator, PickerMode.FOLDER)?.dir?.absolutePath)
            // single quotes (PowerShell-style copy)
            assertEquals(expected, resolveTypedPath("'$expected'", PickerMode.FOLDER)?.dir?.absolutePath)
            // file:// URI (browser / Explorer address bar)
            assertEquals(expected, resolveTypedPath(sub.toURI().toString(), PickerMode.FOLDER)?.dir?.absolutePath)
            // multi-line paste -> the first non-blank line wins
            assertEquals(expected, resolveTypedPath("\n$expected\nC:\\somewhere\\else", PickerMode.FOLDER)?.dir?.absolutePath)
            // a bare drive letter means the drive root, not the process CWD on that drive
            val root = File.listRoots().first()
            assertEquals(root.absolutePath, resolveTypedPath(root.path.removeSuffix(File.separator), PickerMode.FOLDER)?.dir?.absolutePath)
            // ~ -> user home
            assertEquals(
                File(System.getProperty("user.home")).absolutePath,
                resolveTypedPath("~", PickerMode.FOLDER)?.dir?.absolutePath,
            )
            // pasting a directory in SAVE mode navigates without touching the filename
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
            // a bare folder name typed into the path bar
            assertEquals(sub.absolutePath, resolveTypedPath("sub", PickerMode.FOLDER, base = dir)?.dir?.absolutePath)
            // dot segments canonicalize away (clean breadcrumb)
            assertEquals(dir.absolutePath, resolveTypedPath("..", PickerMode.FOLDER, base = sub)?.dir?.absolutePath)
            assertEquals(sub.absolutePath, resolveTypedPath("sub\\..\\sub", PickerMode.FOLDER, base = dir)?.dir?.absolutePath)
            // without a base, a bare name is not resolvable
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
