package com.basetool.bpextractor.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

/** One release asset from the GitHub API — only the fields the update check needs. */
@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    /**
     * GitHub-computed checksum (`sha256:<hex>`). Required for an offer ([UpdateChecker.selectUpdate]
     * fails closed without it) and verified after the download and before every install attempt.
     */
    val digest: String? = null,
)

/** The `releases/latest` answer from the GitHub API — only the fields the update check needs. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

/** An offerable update: a released version newer than the running one, with its MSI asset. */
data class UpdateInfo(
    /** Release version without the leading `v`, e.g. `2.4.0` — for display and the file name. */
    val version: String,
    val tagName: String,
    val msiUrl: String,
    val msiSizeBytes: Long,
    /**
     * Expected SHA-256 of the MSI (lowercase hex), from the API's asset `digest`. Never absent: a
     * release whose MSI carries no well-formed digest is not offered at all (fail closed).
     */
    val msiSha256: String,
)

/** Major/minor/patch plus an optional pre-release suffix (`2.4.0-rc1`). */
internal data class ParsedVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String?)

/**
 * The GUI's update check against this repo's public GitHub releases. On start the latest release is
 * fetched, any failure being skipped silently; a newer one is offered for download into a fresh temp
 * folder ([newUpdateDir]), and a detached PowerShell helper installs it after the app exits and
 * cleans up. [cleanupLeftovers] sweeps leftover folders on every start.
 *
 * Fails closed: an offer needs an MSI under [RELEASE_DOWNLOAD_PREFIX] with a well-formed `sha256:`
 * digest, verified after the download and before every `msiexec` run. Only release metadata is
 * fetched; no usage data is sent.
 */
object UpdateChecker {

    /** The public repo whose releases are checked. */
    const val REPO = "krt-profit/basetool-sc-extractor"

    /**
     * The only URL prefix an MSI may be downloaded from: this repo's own release assets. GitHub
     * redirects from there to its object CDN, which the HTTP client follows (https only).
     */
    const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    /** Name prefix of the per-download temp folders — what [cleanupLeftovers] sweeps. */
    internal const val UPDATE_DIR_PREFIX = "basetool-sc-extractor-update"

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * Fetch the latest release and decide whether it is an offerable update over [currentVersion].
     * Returns null on *any* failure (offline, rate-limited, no releases, malformed answer): the
     * check is a courtesy, never an error surface.
     */
    fun checkForUpdate(currentVersion: String): UpdateInfo? =
        try {
            val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$REPO/releases/latest"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "basetool-sc-extractor/$currentVersion")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                selectUpdate(parseLatestRelease(response.body()), currentVersion)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    /** Parse a `releases/latest` JSON body; null when it isn't one (error answers, garbage). */
    fun parseLatestRelease(body: String): GitHubRelease? =
        try {
            json.decodeFromString<GitHubRelease>(body)
        } catch (_: Exception) {
            null
        }

    /**
     * Decides whether [release] is an offerable update over [currentVersion]: published, newer, and
     * carrying an MSI asset under [RELEASE_DOWNLOAD_PREFIX] with a well-formed SHA-256 digest; otherwise
     * `null`.
     */
    fun selectUpdate(release: GitHubRelease?, currentVersion: String): UpdateInfo? {
        if (release == null || release.draft || release.prerelease) return null
        if (!isNewerVersion(release.tagName, currentVersion)) return null
        val msi = release.assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) } ?: return null
        if (!isTrustedDownloadUrl(msi.downloadUrl)) return null
        val sha256 = sha256FromDigest(msi.digest) ?: return null
        return UpdateInfo(
            version = release.tagName.trim().removePrefix("v"),
            tagName = release.tagName,
            msiUrl = msi.downloadUrl,
            msiSizeBytes = msi.size,
            msiSha256 = sha256,
        )
    }

    /**
     * True when [url] is a release asset of this repo: it starts with [RELEASE_DOWNLOAD_PREFIX]
     * and, parsed, is exactly `https://github.com/...` — no user-info, no explicit port, and no
     * `.`/`..` path segment (raw or percent-encoded) that could walk out of the release path.
     */
    internal fun isTrustedDownloadUrl(url: String): Boolean {
        if (!url.startsWith(RELEASE_DOWNLOAD_PREFIX)) return false
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme != "https" || uri.host != "github.com") return false
        if (uri.rawUserInfo != null || uri.port != -1) return false
        val path = uri.path ?: return false
        return path.split('/').none { it == "." || it == ".." }
    }

    /**
     * True when [remote] is a strictly newer version than [current]. Accepts an optional leading
     * `v` and a missing patch part; on equal numbers a final release beats a pre-release of the
     * same version (`2.4.0` > `2.4.0-rc1`). Unparseable versions are never "newer" — a malformed
     * tag must not produce an update offer.
     */
    fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val c = parseVersion(current) ?: return false
        val numeric = compareValuesBy(r, c, { it.major }, { it.minor }, { it.patch })
        if (numeric != 0) return numeric > 0
        return c.preRelease != null && r.preRelease == null
    }

    internal fun parseVersion(raw: String): ParsedVersion? {
        val match = VERSION_REGEX.matchEntire(raw.trim()) ?: return null
        val (major, minor, patch, suffix) = match.destructured
        return try {
            ParsedVersion(major.toInt(), minor.toInt(), if (patch.isEmpty()) 0 else patch.toInt(), suffix.ifEmpty { null })
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Extract the lowercase hex from a GitHub `sha256:<hex>` digest; null for anything else. */
    internal fun sha256FromDigest(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf(::isSha256Hex)
    }

    /** True for exactly 64 lowercase hex characters — the only digest form the updater acts on. */
    internal fun isSha256Hex(value: String): Boolean =
        value.length == 64 && value.all { c -> c in '0'..'9' || c in 'a'..'f' }

    /** The user's temp root, under which every update folder is created and swept. */
    private fun tempRoot(): File = File(System.getProperty("java.io.tmpdir"))

    /**
     * A fresh download folder with an unpredictable name under the user's temp dir. Not the session temp
     * dir, which a shutdown hook removes before the installer helper runs.
     */
    fun newUpdateDir(root: File = tempRoot()): File =
        Files.createTempDirectory(root.toPath(), "$UPDATE_DIR_PREFIX-").toFile()

    /**
     * Best-effort sweep on app start: removes every update folder (name starting with
     * [UPDATE_DIR_PREFIX] — which also matches the fixed folder older builds used) that a crashed
     * or killed helper left behind in [root]. Files still locked by a running helper just stay —
     * never an error.
     */
    fun cleanupLeftovers(root: File = tempRoot()) {
        try {
            root.listFiles { f -> f.isDirectory && f.name.startsWith(UPDATE_DIR_PREFIX) }
                ?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
        }
    }

    /**
     * Downloads the MSI of [info] into [targetDir] (a fresh [newUpdateDir] when `null`), reporting
     * (bytesDone, bytesTotal). Refuses URLs outside [RELEASE_DOWNLOAD_PREFIX] and verifies byte count and
     * SHA-256; a partial or corrupt file is deleted and the failure thrown.
     */
    fun downloadMsi(
        info: UpdateInfo,
        targetDir: File? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        require(isTrustedDownloadUrl(info.msiUrl)) { "refusing a download outside this repo's releases: ${info.msiUrl}" }
        require(isSha256Hex(info.msiSha256)) { "refusing a download without a valid SHA-256" }
        val ownDir = targetDir == null
        val dir = targetDir ?: newUpdateDir()
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("cannot create download folder: ${dir.absolutePath}")
        }
        try {
            return downloadInto(info, dir, onProgress)
        } catch (t: Throwable) {
            if (ownDir) dir.deleteRecursively()
            throw t
        }
    }

    /** The streaming download + size/SHA-256 verification behind [downloadMsi]. */
    private fun downloadInto(info: UpdateInfo, targetDir: File, onProgress: (Long, Long) -> Unit): File {
        val safeVersion = info.version.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "latest" }
        val target = File(targetDir, "basetool-sc-extractor-$safeVersion.msi")
        val request = HttpRequest.newBuilder(URI.create(info.msiUrl))
            .timeout(Duration.ofMinutes(30))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "basetool-sc-extractor/update")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("download failed: HTTP ${response.statusCode()}")
        }
        val total = response.headers().firstValueAsLong("content-length").orElse(info.msiSizeBytes)
        val sha256 = MessageDigest.getInstance("SHA-256")
        try {
            response.body().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sha256.update(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (info.msiSizeBytes > 0 && target.length() != info.msiSizeBytes) {
                throw IOException("download incomplete: ${target.length()} of ${info.msiSizeBytes} bytes")
            }
            val actual = sha256.digest().joinToString("") { "%02x".format(it) }
            if (actual != info.msiSha256) {
                throw IOException("checksum mismatch — the downloaded file is corrupt")
            }
            return target
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /**
     * The app's own launcher `.exe`, for relaunch after an update: [appPath] (`jpackage.app-path`) first,
     * else `<installDir>\<AppName>.exe` beside the bundled runtime at `java.home`. `null` when nothing
     * resolves, e.g. in a dev run.
     */
    internal fun installedAppLauncher(
        appPath: String? = System.getProperty("jpackage.app-path"),
        javaHome: String? = System.getProperty("java.home"),
        launcherName: String = "Basetool SC Extractor.exe",
    ): File? {
        appPath?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile) return f
        }
        val installDir = javaHome?.takeIf { it.isNotBlank() }?.let { File(it).parentFile } ?: return null
        File(installDir, launcherName).let { if (it.isFile) return it }
        return installDir.listFiles { f -> f.isFile && f.extension.equals("exe", ignoreCase = true) }
            ?.singleOrNull()
    }

    /**
     * The detached PowerShell helper that installs the MSI after the app exits, deletes the MSI and its
     * own folder in every outcome, and relaunches the app when `$AppPath` exists.
     *
     * The first `msiexec /i` runs without elevation; an exit code other than 0, 1602, 3010 or 1641 offers
     * an elevated retry in a native dialog localized by `$Lang`. Every `msiexec` run is preceded by a
     * fresh SHA-256 check against `$Sha256`, computed with .NET's `SHA256`; a mismatch skips the install.
     */
    internal val INSTALLER_SCRIPT = """
        param([string]${'$'}MsiPath, [string]${'$'}Sha256, [string]${'$'}Lang = 'de', [string]${'$'}AppPath = '')
        Start-Sleep -Seconds 2

        ${'$'}digestMismatch = -1

        function Test-MsiDigest {
            if (${'$'}Sha256 -notmatch '^[0-9a-fA-F]{64}${'$'}') { return ${'$'}false }
            try {
                ${'$'}stream = [System.IO.File]::OpenRead(${'$'}MsiPath)
                try {
                    ${'$'}bytes = [System.Security.Cryptography.SHA256]::Create().ComputeHash(${'$'}stream)
                } finally {
                    ${'$'}stream.Dispose()
                }
                ${'$'}actual = [System.BitConverter]::ToString(${'$'}bytes).Replace('-', '').ToLowerInvariant()
                return ${'$'}actual -eq ${'$'}Sha256.ToLowerInvariant()
            } catch {
                return ${'$'}false
            }
        }

        function Invoke-MsiInstall([switch]${'$'}Elevated) {
            if (-not (Test-MsiDigest)) { return ${'$'}digestMismatch }
            ${'$'}msiArgs = @('/i', ('"{0}"' -f ${'$'}MsiPath))
            if (${'$'}Elevated) {
                ${'$'}proc = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${'$'}msiArgs -Verb RunAs -Wait -PassThru
            } else {
                ${'$'}proc = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${'$'}msiArgs -Wait -PassThru
            }
            return ${'$'}proc.ExitCode
        }

        ${'$'}okCodes = @(0, 1602, 3010, 1641)
        try { ${'$'}code = Invoke-MsiInstall } catch { ${'$'}code = 1603 }
        if (${'$'}code -ne ${'$'}digestMismatch -and ${'$'}okCodes -notcontains ${'$'}code) {
            try {
                Add-Type -AssemblyName System.Windows.Forms
                if (${'$'}Lang -eq 'en') {
                    ${'$'}msg = "The update could not be installed. This usually happens when the app was installed on a non-system drive (D:, E:, ...), where Windows Installer cannot set the required file permissions.`n`nRetry as administrator?"
                } else {
                    ${'$'}msg = "Das Update konnte nicht installiert werden. Häufige Ursache: Installation auf einem Nicht-Systemlaufwerk (D:, E:, ...), auf dem Windows Installer die nötigen Dateirechte nicht setzen kann.`n`nAls Administrator erneut versuchen?"
                }
                ${'$'}btn = [System.Windows.Forms.MessageBoxButtons]::YesNo
                ${'$'}icon = [System.Windows.Forms.MessageBoxIcon]::Warning
                ${'$'}def = [System.Windows.Forms.MessageBoxDefaultButton]::Button1
                ${'$'}opt = [System.Windows.Forms.MessageBoxOptions]::DefaultDesktopOnly
                ${'$'}answer = [System.Windows.Forms.MessageBox]::Show(${'$'}msg, 'Basetool SC Extractor', ${'$'}btn, ${'$'}icon, ${'$'}def, ${'$'}opt)
                if (${'$'}answer -eq [System.Windows.Forms.DialogResult]::Yes) {
                    try { Invoke-MsiInstall -Elevated | Out-Null } catch { }
                }
            } catch { }
        }

        Remove-Item -LiteralPath ${'$'}MsiPath -Force -ErrorAction SilentlyContinue
        Set-Location -LiteralPath ${'$'}env:TEMP
        Remove-Item -LiteralPath (Split-Path -Parent ${'$'}MsiPath) -Recurse -Force -ErrorAction SilentlyContinue

        if (${'$'}AppPath -and (Test-Path -LiteralPath ${'$'}AppPath)) {
            try { Start-Process -FilePath ${'$'}AppPath } catch { }
        }
        """.trimIndent()

    /**
     * The helper invocation: Windows PowerShell 5.1 by absolute path with `-File` and plain positional
     * arguments, so paths with spaces or apostrophes need no quoting. [sha256] is the expected
     * lowercase-hex digest, [lang] localizes the retry dialog (anything but "en" is German), and
     * [appPath] is the launcher to relaunch (empty to skip).
     */
    internal fun installerCommand(
        scriptFile: File,
        msiFile: File,
        sha256: String,
        lang: String,
        appPath: String,
    ): List<String> = listOf(
        File(System.getenv("SystemRoot") ?: """C:\Windows""", """System32\WindowsPowerShell\v1.0\powershell.exe""").absolutePath,
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", scriptFile.absolutePath,
        msiFile.absolutePath,
        sha256,
        if (lang == "en") "en" else "de",
        appPath,
    )

    /**
     * Writes the helper script next to [msiFile] with a UTF-8 BOM and launches it detached, with the temp
     * root as working dir; the caller exits right after. [sha256] is the digest the helper re-verifies,
     * [lang] localizes its retry dialog, and [appLauncher] is the exe to relaunch (`null` skips it).
     */
    fun launchInstaller(msiFile: File, sha256: String, lang: String, appLauncher: File? = installedAppLauncher()) {
        val script = File(msiFile.parentFile, "install-update.ps1")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        script.writeBytes(bom + INSTALLER_SCRIPT.toByteArray(Charsets.UTF_8))
        ProcessBuilder(installerCommand(script, msiFile, sha256, lang, appLauncher?.absolutePath ?: ""))
            .directory(msiFile.parentFile.parentFile ?: msiFile.parentFile)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private val VERSION_REGEX = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?""")
}
