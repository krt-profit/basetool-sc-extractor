package com.basetool.bpextractor.update

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

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
        val release = UpdateChecker.parseLatestRelease("""{"message": "Not Found"}""")
        assertNull(UpdateChecker.selectUpdate(release, "1.0.0"))
    }

    @Test
    fun `selectUpdate picks the msi asset and strips the tag prefix`() {
        val info = assertNotNull(UpdateChecker.selectUpdate(UpdateChecker.parseLatestRelease(releaseJson), "2.3.3"))
        assertEquals("9.9.9", info.version)
        assertEquals("v9.9.9", info.tagName)
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
    fun `a release whose msi carries no usable digest is not offered`() {
        val release = assertNotNull(UpdateChecker.parseLatestRelease(releaseJson))
        val noDigest = release.assets.map { it.copy(digest = null) }
        assertNull(UpdateChecker.selectUpdate(release.copy(assets = noDigest), "1.0.0"))
        val malformed = release.assets.map { it.copy(digest = "sha256:tooshort") }
        assertNull(UpdateChecker.selectUpdate(release.copy(assets = malformed), "1.0.0"))
        val otherAlgorithm = release.assets.map { it.copy(digest = "sha512:${"a".repeat(128)}") }
        assertNull(UpdateChecker.selectUpdate(release.copy(assets = otherAlgorithm), "1.0.0"))
    }

    @Test
    fun `an msi hosted anywhere but this repo's release downloads is not offered`() {
        val release = assertNotNull(UpdateChecker.parseLatestRelease(releaseJson))
        val file = "/releases/download/v9.9.9/Basetool.SC.Extractor-9.9.9.msi"
        listOf(
            "https://evil.example/krt-profit/basetool-sc-extractor$file",
            "https://github.com/someone-else/basetool-sc-extractor$file",
            "https://github.com.evil.example/krt-profit/basetool-sc-extractor$file",
            "https://objects.githubusercontent.com/github-production-release-asset/x.msi",
            "https://github.com/krt-profit/basetool-sc-extractor/releases/download/../../../evil/x.msi",
            "https://github.com/krt-profit/basetool-sc-extractor/releases/download/%2e%2e/%2e%2e/evil/x.msi",
        ).forEach { url ->
            val foreign = release.assets.map { if (it.name.endsWith(".msi")) it.copy(downloadUrl = url) else it }
            assertNull(UpdateChecker.selectUpdate(release.copy(assets = foreign), "1.0.0"), url)
        }
    }

    @Test
    fun `trusted download urls are exactly this repo's release assets`() {
        assertTrue(UpdateChecker.isTrustedDownloadUrl("${UpdateChecker.RELEASE_DOWNLOAD_PREFIX}v2.9.1/Basetool.SC.Extractor-2.9.1.msi"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("http://github.com/${UpdateChecker.REPO}/releases/download/v1/x.msi"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("https://github.com/${UpdateChecker.REPO}/releases/tag/v1"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl(""))
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

    @Test
    fun `installer command runs the hidden powershell helper with plain path arguments`() {
        val script = File("""C:\Users\Tim O'Brien\AppData\Local\Temp\basetool-sc-extractor-update\install-update.ps1""")
        val msi = File("""C:\Users\Tim O'Brien\AppData\Local\Temp\basetool-sc-extractor-update\basetool-sc-extractor-9.9.9.msi""")
        val app = File("""C:\Users\Tim O'Brien\AppData\Local\Basetool SC Extractor\Basetool SC Extractor.exe""")
        val sha = "e3299dd3a45ab325824c1a8fbb6b5eb099cbec977249b57cb1a8a282f24fc3a3"
        val command = UpdateChecker.installerCommand(script, msi, sha, "de", app.absolutePath)
        assertTrue(command.first().endsWith("powershell.exe"))
        assertTrue(command.containsAll(listOf("-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden")))
        assertEquals(script.absolutePath, command[command.indexOf("-File") + 1])
        assertEquals(msi.absolutePath, command[command.size - 4])
        assertEquals(sha, command[command.size - 3])
        assertEquals("de", command[command.size - 2])
        assertEquals(app.absolutePath, command.last())
        assertTrue(command.none { it.contains('"') })
        assertEquals("en", UpdateChecker.installerCommand(script, msi, sha, "en", "").let { it[it.size - 2] })
        assertEquals("de", UpdateChecker.installerCommand(script, msi, sha, "fr", "").let { it[it.size - 2] })
        assertEquals("", UpdateChecker.installerCommand(script, msi, sha, "de", "").last())
    }

    @Test
    fun `installed app launcher resolves from the jpackage app-image layout`() {
        val root = Files.createTempDirectory("bpx-appimage").toFile()
        try {
            val install = File(root, "Basetool SC Extractor").apply { mkdirs() }
            val runtime = File(install, "runtime").apply { mkdirs() }
            val launcher = File(install, "Basetool SC Extractor.exe").apply { writeText("stub") }

            assertEquals(launcher, UpdateChecker.installedAppLauncher(appPath = null, javaHome = runtime.absolutePath))
            assertEquals(launcher, UpdateChecker.installedAppLauncher(appPath = launcher.absolutePath, javaHome = null))
            File(install, "Basetool SC Extractor.exe").renameTo(File(install, "Launcher.exe"))
            assertEquals(File(install, "Launcher.exe"), UpdateChecker.installedAppLauncher(appPath = null, javaHome = runtime.absolutePath))
            val jdk = File(root, "jdk-25").apply { mkdirs() }
            assertNull(UpdateChecker.installedAppLauncher(appPath = null, javaHome = jdk.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installer script installs, waits and deletes the update files`() {
        val script = UpdateChecker.INSTALLER_SCRIPT
        assertTrue(script.startsWith("param([string]\$MsiPath"))
        assertTrue(script.contains("\$Lang"))
        assertTrue(script.contains("\$AppPath"))
        assertTrue(script.contains("msiexec.exe"))
        assertTrue(script.contains("-Wait"))
        assertTrue(script.contains("-PassThru"))
        assertTrue(script.contains("-Verb RunAs"))
        assertTrue(script.contains("Remove-Item -LiteralPath \$MsiPath"))
        assertTrue(script.contains("Split-Path -Parent \$MsiPath"))
        assertTrue(script.contains("Set-Location"))
        assertTrue(script.contains("Start-Process -FilePath \$AppPath"))
        assertTrue(script.contains("Test-Path -LiteralPath \$AppPath"))
    }

    @Test
    fun `installer script re-hashes the msi before every msiexec run, the elevated retry included`() {
        val script = UpdateChecker.INSTALLER_SCRIPT
        assertTrue(script.contains("[System.Security.Cryptography.SHA256]::Create().ComputeHash("))
        assertFalse(script.contains("Get-FileHash"))
        assertTrue(script.startsWith("param([string]\$MsiPath, [string]\$Sha256,"))
        val body = script.substringAfter("function Invoke-MsiInstall").substringBefore("\n}")
        val check = body.indexOf("Test-MsiDigest")
        assertTrue(check >= 0)
        assertTrue(check < body.indexOf("-Verb RunAs"))
        assertTrue(check < body.indexOf("msiexec.exe"))
        assertEquals(2, Regex("Start-Process -FilePath 'msiexec.exe'").findAll(script).count())
        assertEquals(2, Regex("Start-Process -FilePath 'msiexec.exe'").findAll(body).count())
        assertTrue(script.contains("Invoke-MsiInstall -Elevated"))
        assertTrue(script.contains("\$code -ne \$digestMismatch"))
    }

    /**
     * Runs the helper's `Test-MsiDigest` and `Invoke-MsiInstall`, extracted from
     * [UpdateChecker.INSTALLER_SCRIPT], in Windows PowerShell with `Start-Process` stubbed, so no msiexec
     * or UAC prompt can start. Skipped where Windows PowerShell is unavailable.
     */
    @Test
    fun `the helper refuses to launch msiexec for a file whose hash does not match`() {
        val powershell = File(System.getenv("SystemRoot") ?: """C:\Windows""", """System32\WindowsPowerShell\v1.0\powershell.exe""")
        if (!powershell.isFile) return
        val dir = Files.createTempDirectory("update-helper-test").toFile()
        try {
            val msi = File(dir, "fake.msi").apply { writeText("not really an msi") }
            val good = MessageDigest.getInstance("SHA-256").digest(msi.readBytes())
                .joinToString("") { "%02x".format(it) }
            val helper = File(dir, "install-update.ps1").apply { writeText(UpdateChecker.INSTALLER_SCRIPT) }
            val driver = File(dir, "driver.ps1").apply { writeText(HELPER_DRIVER) }
            fun run(sha: String): String {
                val process = ProcessBuilder(
                    powershell.absolutePath, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", driver.absolutePath, helper.absolutePath, msi.absolutePath, sha,
                ).redirectErrorStream(true).start()
                val out = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                println("installer-helper driver [digest ${sha.take(8)}…]: $out")
                return out
            }
            assertEquals("plain=0 started=1 elevated=0 started=2", run(good))
            assertEquals("plain=0 started=1 elevated=0 started=2", run(good.uppercase()))
            assertEquals("plain=-1 started=0 elevated=-1 started=0", run("0".repeat(64)))
            assertEquals("plain=-1 started=0 elevated=-1 started=0", run(""))
            assertEquals("plain=-1 started=0 elevated=-1 started=0", run("not-a-digest"))
            msi.appendText("tampered")
            assertEquals("plain=-1 started=0 elevated=-1 started=0", run(good))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `each update folder is a fresh unpredictable folder under the user temp dir`() {
        val root = Files.createTempDirectory("update-root").toFile()
        try {
            val a = UpdateChecker.newUpdateDir(root)
            val b = UpdateChecker.newUpdateDir(root)
            assertEquals(root.absoluteFile, a.absoluteFile.parentFile)
            assertTrue(a.isDirectory && b.isDirectory)
            assertTrue(a.name.startsWith(UpdateChecker.UPDATE_DIR_PREFIX))
            assertNotEquals(a, b)
            assertNotEquals(UpdateChecker.UPDATE_DIR_PREFIX, a.name)
            val real = UpdateChecker.newUpdateDir()
            try {
                assertEquals(File(System.getProperty("java.io.tmpdir")).absoluteFile, real.absoluteFile.parentFile)
            } finally {
                real.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cleanupLeftovers removes stale update folders, legacy one included, and nothing else`() {
        val root = Files.createTempDirectory("update-test").toFile()
        try {
            val stale = UpdateChecker.newUpdateDir(root)
            File(stale, "basetool-sc-extractor-9.9.9.msi").writeText("stale")
            File(stale, "install-update.ps1").writeText("stale")
            val legacy = File(root, UpdateChecker.UPDATE_DIR_PREFIX).apply { mkdirs() }
            File(legacy, "basetool-sc-extractor-9.9.8.msi").writeText("stale")
            val unrelated = File(root, "something-else").apply { mkdirs() }
            UpdateChecker.cleanupLeftovers(root)
            assertFalse(stale.exists())
            assertFalse(legacy.exists())
            assertTrue(unrelated.exists())
            UpdateChecker.cleanupLeftovers(root)
            UpdateChecker.cleanupLeftovers(File(root, "missing"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `download refuses sources outside this repo's releases and missing digests`() {
        val info = UpdateInfo(
            version = "9.9.9",
            tagName = "v9.9.9",
            msiUrl = "http://github.com/insecure.msi",
            msiSizeBytes = 1,
            msiSha256 = "e3299dd3a45ab325824c1a8fbb6b5eb099cbec977249b57cb1a8a282f24fc3a3",
        )
        assertFailsWith<IllegalArgumentException> { UpdateChecker.downloadMsi(info) }
        assertFailsWith<IllegalArgumentException> { UpdateChecker.downloadMsi(info.copy(msiUrl = "https://evil.example/x.msi")) }
        val noDigest = info.copy(msiUrl = "${UpdateChecker.RELEASE_DOWNLOAD_PREFIX}v9.9.9/x.msi", msiSha256 = "")
        assertFailsWith<IllegalArgumentException> { UpdateChecker.downloadMsi(noDigest) }
    }

    private companion object {
        /**
         * Test driver: parses the helper, defines only its two functions (never its top level,
         * which would install), shadows `Start-Process` with a counting stub and reports what
         * `Invoke-MsiInstall` returned and how often it tried to start msiexec.
         */
        val HELPER_DRIVER = """
            param([string]${'$'}HelperPath, [string]${'$'}MsiPath, [string]${'$'}Sha256)
            ${'$'}errs = ${'$'}null
            ${'$'}ast = [System.Management.Automation.Language.Parser]::ParseFile(${'$'}HelperPath, [ref]${'$'}null, [ref]${'$'}errs)
            if (${'$'}errs) { 'PARSE-ERROR: ' + ((${'$'}errs | ForEach-Object { ${'$'}_.Message }) -join '; '); exit 1 }
            foreach (${'$'}name in @('Test-MsiDigest', 'Invoke-MsiInstall')) {
                ${'$'}fn = ${'$'}ast.Find({ param(${'$'}n) ${'$'}n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and ${'$'}n.Name -eq ${'$'}name }, ${'$'}true)
                Invoke-Expression ${'$'}fn.Extent.Text
            }
            ${'$'}digestMismatch = -1
            ${'$'}script:started = 0
            function Start-Process { ${'$'}script:started++; [pscustomobject]@{ ExitCode = 0 } }
            ${'$'}plain = Invoke-MsiInstall
            ${'$'}afterPlain = ${'$'}script:started
            ${'$'}elevated = Invoke-MsiInstall -Elevated
            "plain=${'$'}plain started=${'$'}afterPlain elevated=${'$'}elevated started=${'$'}(${'$'}script:started)"
        """.trimIndent()
    }
}
