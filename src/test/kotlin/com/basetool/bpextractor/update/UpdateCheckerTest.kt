package com.basetool.bpextractor.update

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    // --- version comparison ------------------------------------------------------------------

    @Test
    fun `newer major, minor and patch are detected`() {
        assertTrue(UpdateChecker.isNewerVersion("3.0.0", "2.3.3"))
        assertTrue(UpdateChecker.isNewerVersion("2.4.0", "2.3.3"))
        assertTrue(UpdateChecker.isNewerVersion("2.3.4", "2.3.3"))
    }

    @Test
    fun `equal and older versions are not updates`() {
        assertFalse(UpdateChecker.isNewerVersion("2.3.3", "2.3.3"))
        assertFalse(UpdateChecker.isNewerVersion("2.3.2", "2.3.3"))
        assertFalse(UpdateChecker.isNewerVersion("1.9.9", "2.0.0"))
    }

    @Test
    fun `leading v and a missing patch part are accepted`() {
        assertTrue(UpdateChecker.isNewerVersion("v2.4.0", "2.3.3"))
        assertTrue(UpdateChecker.isNewerVersion("2.4.0", "v2.3.3"))
        // "2.4" reads as 2.4.0.
        assertTrue(UpdateChecker.isNewerVersion("2.4", "2.3.3"))
        assertFalse(UpdateChecker.isNewerVersion("2.3", "2.3.0"))
    }

    @Test
    fun `a final release beats a pre-release of the same version but not vice versa`() {
        assertTrue(UpdateChecker.isNewerVersion("2.4.0", "2.4.0-rc1"))
        assertFalse(UpdateChecker.isNewerVersion("2.4.0-rc1", "2.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("2.4.0-rc2", "2.4.0-rc1"))
    }

    @Test
    fun `unparseable versions never produce an update`() {
        assertFalse(UpdateChecker.isNewerVersion("latest", "2.3.3"))
        assertFalse(UpdateChecker.isNewerVersion("", "2.3.3"))
        assertFalse(UpdateChecker.isNewerVersion("2.4.0", "dev"))
        assertFalse(UpdateChecker.isNewerVersion("99999999999999999999.0.0", "2.3.3"))
    }

    // --- release JSON parsing ----------------------------------------------------------------

    /** Mirrors the real `releases/latest` answer shape, including fields the app ignores. */
    private val releaseJson = """
        {
          "url": "https://api.github.com/repos/${UpdateChecker.REPO}/releases/255",
          "html_url": "https://github.com/${UpdateChecker.REPO}/releases/tag/v9.9.9",
          "tag_name": "v9.9.9",
          "name": "v9.9.9",
          "draft": false,
          "prerelease": false,
          "author": { "login": "greluc", "id": 1 },
          "assets": [
            {
              "name": "Basetool.SC.Extractor-9.9.9.msi.sha256",
              "browser_download_url": "https://github.com/${UpdateChecker.REPO}/releases/download/v9.9.9/Basetool.SC.Extractor-9.9.9.msi.sha256",
              "size": 64,
              "content_type": "text/plain"
            },
            {
              "name": "Basetool.SC.Extractor-9.9.9.msi",
              "browser_download_url": "https://github.com/${UpdateChecker.REPO}/releases/download/v9.9.9/Basetool.SC.Extractor-9.9.9.msi",
              "size": 58827316,
              "digest": "sha256:e3299dd3a45ab325824c1a8fbb6b5eb099cbec977249b57cb1a8a282f24fc3a3",
              "content_type": "application/x-msdownload"
            }
          ],
          "body": "release notes"
        }
    """.trimIndent()

    @Test
    fun `a real releases-latest payload parses with unknown fields ignored`() {
        val release = assertNotNull(UpdateChecker.parseLatestRelease(releaseJson))
        assertEquals("v9.9.9", release.tagName)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        assertEquals(2, release.assets.size)
        assertEquals(58827316L, release.assets[1].size)
    }

    @Test
    fun `garbage is not a release`() {
        assertNull(UpdateChecker.parseLatestRelease("not json at all"))
        assertNull(UpdateChecker.parseLatestRelease("[1, 2, 3]"))
    }

    @Test
    fun `an API error answer yields no update`() {
        // 404 bodies ({"message": "Not Found"}) parse into defaults — the empty tag is not newer.
        val release = UpdateChecker.parseLatestRelease("""{"message": "Not Found"}""")
        assertNull(UpdateChecker.selectUpdate(release, "1.0.0"))
    }

    // --- update selection --------------------------------------------------------------------

    @Test
    fun `selectUpdate picks the msi asset and strips the tag prefix`() {
        val info = assertNotNull(UpdateChecker.selectUpdate(UpdateChecker.parseLatestRelease(releaseJson), "2.3.3"))
        assertEquals("9.9.9", info.version)
        assertEquals("v9.9.9", info.tagName)
        // The .msi.sha256 sibling asset must not be mistaken for the installer.
        assertTrue(info.msiUrl.endsWith("Basetool.SC.Extractor-9.9.9.msi"))
        assertEquals(58827316L, info.msiSizeBytes)
        assertEquals("e3299dd3a45ab325824c1a8fbb6b5eb099cbec977249b57cb1a8a282f24fc3a3", info.msiSha256)
    }

    @Test
    fun `selectUpdate offers nothing when already up to date`() {
        assertNull(UpdateChecker.selectUpdate(UpdateChecker.parseLatestRelease(releaseJson), "9.9.9"))
        assertNull(UpdateChecker.selectUpdate(UpdateChecker.parseLatestRelease(releaseJson), "10.0.0"))
    }

    @Test
    fun `drafts and pre-releases are never offered`() {
        val release = assertNotNull(UpdateChecker.parseLatestRelease(releaseJson))
        assertNull(UpdateChecker.selectUpdate(release.copy(draft = true), "1.0.0"))
        assertNull(UpdateChecker.selectUpdate(release.copy(prerelease = true), "1.0.0"))
        assertNull(UpdateChecker.selectUpdate(null, "1.0.0"))
    }

    @Test
    fun `a release without an msi asset or with a non-https url is not offered`() {
        val release = assertNotNull(UpdateChecker.parseLatestRelease(releaseJson))
        assertNull(UpdateChecker.selectUpdate(release.copy(assets = emptyList()), "1.0.0"))
        assertNull(UpdateChecker.selectUpdate(release.copy(assets = release.assets.take(1)), "1.0.0"))
        val insecure = release.assets.map { it.copy(downloadUrl = it.downloadUrl.replace("https://", "http://")) }
        assertNull(UpdateChecker.selectUpdate(release.copy(assets = insecure), "1.0.0"))
    }

    @Test
    fun `only well-formed sha256 digests are kept`() {
        val hex = "e3299dd3a45ab325824c1a8fbb6b5eb099cbec977249b57cb1a8a282f24fc3a3"
        assertEquals(hex, UpdateChecker.sha256FromDigest("sha256:$hex"))
        assertEquals(hex, UpdateChecker.sha256FromDigest("sha256:${hex.uppercase()}"))
        assertNull(UpdateChecker.sha256FromDigest(null))
        assertNull(UpdateChecker.sha256FromDigest("md5:abc"))
        assertNull(UpdateChecker.sha256FromDigest("sha256:tooshort"))
        assertNull(UpdateChecker.sha256FromDigest("sha256:${"z".repeat(64)}"))
    }

    // --- installer helper --------------------------------------------------------------------

    @Test
    fun `installer command runs the hidden powershell helper with plain path arguments`() {
        val script = File("""C:\Users\Tim O'Brien\AppData\Local\Temp\basetool-sc-extractor-update\install-update.ps1""")
        val msi = File("""C:\Users\Tim O'Brien\AppData\Local\Temp\basetool-sc-extractor-update\basetool-sc-extractor-9.9.9.msi""")
        val command = UpdateChecker.installerCommand(script, msi)
        assertTrue(command.first().endsWith("powershell.exe"))
        assertTrue(command.containsAll(listOf("-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden")))
        // -File takes the script, then the MSI path as a plain positional argument — paths with
        // spaces/apostrophes need no embedded quoting anywhere.
        assertEquals(script.absolutePath, command[command.indexOf("-File") + 1])
        assertEquals(msi.absolutePath, command.last())
        assertTrue(command.none { it.contains('"') })
    }

    @Test
    fun `installer script installs, waits and deletes the update files`() {
        val script = UpdateChecker.INSTALLER_SCRIPT
        assertTrue(script.startsWith("param([string]\$MsiPath)"))
        assertTrue(script.contains("msiexec.exe"))
        assertTrue(script.contains("-Wait"))
        // The MSI itself and the whole update folder (incl. the script) are removed afterwards.
        assertTrue(script.contains("Remove-Item -LiteralPath \$MsiPath"))
        assertTrue(script.contains("Split-Path -Parent \$MsiPath"))
        // The helper must leave the update folder before deleting it (CWD blocks deletion).
        assertTrue(script.contains("Set-Location"))
    }

    // --- temp dir handling -------------------------------------------------------------------

    @Test
    fun `update dir lives under the user temp dir, never the install dir`() {
        val tmp = File(System.getProperty("java.io.tmpdir")).absoluteFile
        assertEquals(tmp, UpdateChecker.updateDir().absoluteFile.parentFile)
    }

    @Test
    fun `cleanupLeftovers removes a stale update folder and tolerates a missing one`() {
        val dir = Files.createTempDirectory("update-test").toFile()
        File(dir, "basetool-sc-extractor-9.9.9.msi").writeText("stale")
        File(dir, "install-update.ps1").writeText("stale")
        UpdateChecker.cleanupLeftovers(dir)
        assertFalse(dir.exists())
        UpdateChecker.cleanupLeftovers(dir) // already gone — must not throw
    }

    @Test
    fun `download refuses non-https sources`() {
        val info = UpdateInfo(
            version = "9.9.9",
            tagName = "v9.9.9",
            msiUrl = "http://github.com/insecure.msi",
            msiSizeBytes = 1,
        )
        assertFailsWith<IllegalArgumentException> { UpdateChecker.downloadMsi(info) }
    }
}
