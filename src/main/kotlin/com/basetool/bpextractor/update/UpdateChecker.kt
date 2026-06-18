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
import java.security.MessageDigest
import java.time.Duration

/** One release asset from the GitHub API — only the fields the update check needs. */
@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    /** GitHub-computed checksum (`sha256:<hex>`), verified after the download when present. */
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
    /** Expected SHA-256 of the MSI (lowercase hex) when the API provided a digest. */
    val msiSha256: String? = null,
)

/** Major/minor/patch plus an optional pre-release suffix (`2.4.0-rc1`). */
internal data class ParsedVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String?)

/**
 * The GUI's update check against the public GitHub releases of this repo: on app start the latest
 * release is fetched (silently skipped on any failure — the check must never block or break the
 * app); when it is newer than the running version, the start screen offers to download the MSI and
 * install it. The download goes to a fixed folder under the user's temp dir — NEVER the install
 * dir (CLAUDE.md guardrail 2) — and a detached PowerShell helper runs `msiexec /i` after the app
 * exits, then deletes the MSI, the helper script and the folder again. [cleanupLeftovers] sweeps
 * that folder on every start as a belt-and-braces guard against a crashed or killed helper.
 *
 * Only release *metadata* is fetched from GitHub; no usage data is sent (the request carries
 * nothing but the standard headers).
 */
object UpdateChecker {

    /** The public repo whose releases are checked. */
    const val REPO = "krt-profit/basetool-sc-extractor"

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // The asset download redirects from github.com to the objects CDN.
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
     * Decide whether [release] is an offerable update over [currentVersion]: a published (not
     * draft/pre-release) version that compares newer and actually carries an MSI asset over https.
     */
    fun selectUpdate(release: GitHubRelease?, currentVersion: String): UpdateInfo? {
        if (release == null || release.draft || release.prerelease) return null
        if (!isNewerVersion(release.tagName, currentVersion)) return null
        val msi = release.assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) } ?: return null
        if (!msi.downloadUrl.startsWith("https://")) return null
        return UpdateInfo(
            version = release.tagName.trim().removePrefix("v"),
            tagName = release.tagName,
            msiUrl = msi.downloadUrl,
            msiSizeBytes = msi.size,
            msiSha256 = sha256FromDigest(msi.digest),
        )
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
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * The fixed download folder under the user's temp dir. Deliberately NOT the session temp dir
     * from `ImageIntake.tempFolder()`: that one is removed by a shutdown hook on exit — which
     * would race the installer helper that still needs the MSI after the app has quit.
     */
    fun updateDir(): File = File(System.getProperty("java.io.tmpdir"), "basetool-sc-extractor-update")

    /**
     * Best-effort sweep of [dir] on app start: removes MSIs/scripts a crashed or killed helper
     * left behind. Files still locked by a running helper just stay — never an error.
     */
    fun cleanupLeftovers(dir: File = updateDir()) {
        try {
            if (dir.exists()) dir.deleteRecursively()
        } catch (_: Exception) {
            // Best effort only — leftovers in the temp dir must never break the app.
        }
    }

    /**
     * Download the MSI of [info] into [targetDir], reporting (bytesDone, bytesTotal) along the
     * way. Verifies the byte count and — when the API provided one — the SHA-256 before returning;
     * a partial or corrupt file is deleted and the failure thrown to the caller (the banner shows
     * it with a retry).
     */
    fun downloadMsi(
        info: UpdateInfo,
        targetDir: File = updateDir(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        require(info.msiUrl.startsWith("https://")) { "refusing non-https download: ${info.msiUrl}" }
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw IOException("cannot create download folder: ${targetDir.absolutePath}")
        }
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
            if (info.msiSha256 != null) {
                val actual = sha256.digest().joinToString("") { "%02x".format(it) }
                if (actual != info.msiSha256) {
                    throw IOException("checksum mismatch — the downloaded file is corrupt")
                }
            }
            return target
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /**
     * The detached helper that performs the install after the app exits. PowerShell reads the
     * whole script file before running it, so the last line can delete the script's own folder.
     * The initial sleep gives the closing JVM time to release its files before msiexec checks
     * files-in-use; the MSI is deleted afterwards in every outcome (installed or cancelled) —
     * the temp folder must end up empty either way.
     */
    internal val INSTALLER_SCRIPT = """
        param([string]${'$'}MsiPath)
        Start-Sleep -Seconds 2
        Start-Process -FilePath 'msiexec.exe' -ArgumentList ('/i', ('"{0}"' -f ${'$'}MsiPath)) -Wait
        Remove-Item -LiteralPath ${'$'}MsiPath -Force -ErrorAction SilentlyContinue
        Set-Location -LiteralPath ${'$'}env:TEMP
        Remove-Item -LiteralPath (Split-Path -Parent ${'$'}MsiPath) -Recurse -Force -ErrorAction SilentlyContinue
        """.trimIndent()

    /**
     * The helper invocation. Windows PowerShell 5.1 by absolute path (always present, unlike
     * pwsh); `-File` passes the MSI path as a plain positional argument, so no string ever needs
     * embedded quotes — paths with spaces or apostrophes survive ProcessBuilder's quoting as-is.
     */
    internal fun installerCommand(scriptFile: File, msiFile: File): List<String> = listOf(
        File(System.getenv("SystemRoot") ?: """C:\Windows""", """System32\WindowsPowerShell\v1.0\powershell.exe""").absolutePath,
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", scriptFile.absolutePath,
        msiFile.absolutePath,
    )

    /**
     * Write the helper script next to [msiFile] and launch it detached. The caller exits the app
     * right after — the helper waits, installs, then removes the MSI and itself. Its working dir
     * is the temp root: never the install dir (which the MSI replaces) and never the update dir
     * (which the helper deletes at the end).
     */
    fun launchInstaller(msiFile: File) {
        val script = File(msiFile.parentFile, "install-update.ps1")
        script.writeText(INSTALLER_SCRIPT)
        ProcessBuilder(installerCommand(script, msiFile))
            .directory(msiFile.parentFile.parentFile ?: msiFile.parentFile)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private val VERSION_REGEX = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?""")
}
