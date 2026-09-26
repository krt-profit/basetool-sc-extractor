package com.basetool.bpextractor

import com.basetool.bpextractor.model.BlueprintEvent
import com.basetool.bpextractor.model.BlueprintExport
import com.basetool.bpextractor.model.PlayerSummary
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Progress callback: the file counter plus a byte counter across ALL files, so a
 * progress bar can keep moving inside one huge `Game.log` instead of stalling
 * until the next file starts.
 */
typealias ProgressListener = (
    filesDone: Int,
    filesTotal: Int,
    bytesDone: Long,
    bytesTotal: Long,
    current: String,
) -> Unit

/**
 * Outcome of one extraction run: the export document plus the names of any log
 * files that could not be read (locked/corrupt) and were skipped. Skips are
 * reported to the user but deliberately kept out of the export JSON.
 */
data class ExtractionResult(
    val export: BlueprintExport,
    val skippedFiles: List<String>,
    /**
     * The localisation the picked folder declared and the notification formats the run matched with;
     * not part of the export JSON. [ScLocalization.Detected.NONE] for a folder without a game install,
     * in which case only the built-in formats were used.
     */
    val localization: ScLocalization.Detected = ScLocalization.Detected.NONE,
    val formatsUsed: List<String> = BlueprintParser.BUILT_IN_FORMATS,
)

/**
 * Scans a folder of Game.log files, extracts every received blueprint, and
 * produces the export document. Orchestration only — the actual line parsing
 * lives in [BlueprintParser].
 */
object BlueprintExtractor {

    const val TOOL_NAME = "Basetool SC Extractor"

    /**
     * App version shown in the GUI and written as the export's `toolVersion`, generated from the
     * project version by the `generateBuildInfo` task ([BuildInfo]).
     */
    val TOOL_VERSION: String = BuildInfo.VERSION

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** The channel folder a user normally points the tool at. */
    const val PRIMARY_CHANNEL_NAME = "LIVE"

    /**
     * The patch-cycle channel next to LIVE. The two share one account's progress, so each is swept along
     * with the other ([siblingChannelFolder]).
     */
    const val SIBLING_CHANNEL_NAME = "HOTFIX"

    /**
     * Collects the SC log files to scan from a channel folder: its `Game.log`, then every `*.log` in
     * `logbackups` by name, then the logs of the sibling LIVE/HOTFIX folder ([siblingChannelFolder]). A
     * folder of neither shape is read as an archive ([looseLogsIn]).
     */
    fun findLogFiles(channelFolder: File): List<File> {
        val files = collectChannelLogs(channelFolder).toMutableList()
        siblingChannelFolder(channelFolder)?.let { files += collectChannelLogs(it) }
        return files
    }

    /**
     * The `Game.log` + every `*.log` in `logbackups` for a single channel folder, current first
     * then backups by name. Empty when [channelFolder] is not a directory or carries no logs.
     *
     * When the folder is neither — no `Game.log`, no `logbackups` — but holds `*.log` files
     * directly, those are taken instead (see [looseLogsIn]).
     */
    private fun collectChannelLogs(channelFolder: File): List<File> {
        if (!channelFolder.isDirectory) return emptyList()
        val files = mutableListOf<File>()
        val current = File(channelFolder, "Game.log")
        if (current.isFile) files += current
        val backups = File(channelFolder, "logbackups")
        if (backups.isDirectory) {
            backups.listFiles()
                ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.let { files += it }
        }
        return files.ifEmpty { looseLogsIn(channelFolder) }
    }

    /**
     * Lists the `*.log` files lying directly in an archive [folder], used only when it has neither a
     * `Game.log` nor a `logbackups` subfolder. Not recursive.
     */
    private fun looseLogsIn(folder: File): List<File> =
        folder.listFiles()
            ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Returns the sibling channel of a LIVE or HOTFIX [channelFolder] — HOTFIX for LIVE, LIVE for HOTFIX —
     * when it exists and holds SC logs (a `Game.log` or a `logbackups` subfolder), otherwise `null`. Any
     * other folder has no sibling. Reads file metadata only.
     */
    fun siblingChannelFolder(channelFolder: File): File? {
        val siblingName = siblingChannelName(channelFolder.name) ?: return null
        val parent = channelFolder.absoluteFile.parentFile ?: return null
        val sibling = File(parent, siblingName)
        return if (holdsChannelLogs(sibling)) sibling else null
    }

    /** `HOTFIX` for `LIVE` and `LIVE` for `HOTFIX`, case-insensitively; `null` for any other name. */
    fun siblingChannelName(folderName: String): String? = when {
        folderName.equals(PRIMARY_CHANNEL_NAME, ignoreCase = true) -> SIBLING_CHANNEL_NAME
        folderName.equals(SIBLING_CHANNEL_NAME, ignoreCase = true) -> PRIMARY_CHANNEL_NAME
        else -> null
    }

    /** Whether [folder] is a directory holding a `Game.log` or a `logbackups` subfolder. */
    fun holdsChannelLogs(folder: File): Boolean =
        folder.isDirectory && (File(folder, "Game.log").isFile || File(folder, "logbackups").isDirectory)

    /** Report within-file byte progress at most every this many bytes, to keep UI churn low. */
    private const val PROGRESS_BYTE_STEP = 4L * 1024 * 1024

    /**
     * Identity of one blueprint event independent of which file it was read from. Two
     * events with the same key are the same in-game notification — seen twice only when
     * the same log content was scanned twice (e.g. a manually copied log file).
     */
    private data class EventKey(
        val player: String?,
        val productName: String,
        val receivedAt: String,
        val notificationId: Int?,
    )

    /**
     * Runs extraction over [channelFolder] and returns the [BlueprintExport] plus the skipped unreadable
     * files, without writing to disk. Events already seen in another file (same player, name, timestamp
     * and notification id) are counted once.
     */
    fun extract(
        channelFolder: File,
        progress: ProgressListener? = null,
    ): ExtractionResult {
        val files = findLogFiles(channelFolder)
        val bytesTotal = files.sumOf { it.length() }
        val sibling = siblingChannelFolder(channelFolder)

        val localization = ScLocalization.detect(channelFolder)
        val siblingFormats = sibling?.let { ScLocalization.detect(it).formats }.orEmpty()
        val formats = (localization.formats + siblingFormats + BlueprintParser.BUILT_IN_FORMATS).distinct()
        val patterns = BlueprintParser.compile(formats)

        val allBlueprints = mutableListOf<BlueprintEvent>()
        val countsByPlayer = linkedMapOf<String, Int>()
        val seenEvents = HashSet<EventKey>()
        val skipped = mutableListOf<String>()
        var bytesBefore = 0L

        files.forEachIndexed { index, file ->
            progress?.invoke(index, files.size, bytesBefore, bytesTotal, file.name)
            var lastReported = 0L
            val result = try {
                BlueprintParser.parseFile(file, patterns) { bytesRead ->
                    if (bytesRead - lastReported >= PROGRESS_BYTE_STEP) {
                        lastReported = bytesRead
                        progress?.invoke(index, files.size, bytesBefore + bytesRead, bytesTotal, file.name)
                    }
                }
            } catch (_: IOException) {
                skipped += file.name
                null
            }
            bytesBefore += file.length()
            result ?: return@forEachIndexed
            for (bp in result.blueprints) {
                if (!seenEvents.add(EventKey(bp.player, bp.productName, bp.receivedAt, bp.notificationId))) continue
                allBlueprints += bp
                bp.player?.let { countsByPlayer.merge(it, 1, Int::plus) }
            }
        }
        progress?.invoke(files.size, files.size, bytesTotal, bytesTotal, "")

        val named = resolveRawKeys(allBlueprints, listOfNotNull(channelFolder, sibling), localization.activeLanguage)
        val sorted = named.sortedBy { it.receivedAt.ifEmpty { "￿" } }

        val players = countsByPlayer.entries
            .sortedByDescending { it.value }
            .map { (handle, count) -> PlayerSummary(handle = handle, blueprintCount = count) }

        val export = BlueprintExport(
            tool = TOOL_NAME,
            toolVersion = TOOL_VERSION,
            generatedAt = Instant.now().toString(),
            sourceFolder = channelFolder.absolutePath,
            additionalSourceFolders = sibling?.let { listOf(it.absolutePath) },
            logFilesScanned = files.size - skipped.size,
            blueprintCount = sorted.size,
            players = players,
            blueprints = sorted,
        )
        return ExtractionResult(export, skipped, localization, formats)
    }

    /**
     * Replaces each untranslated `@key` name in [events] with that key's value from the `global.ini`
     * files of [channelFolders], keeping the key in [BlueprintEvent.localizationKey]; an unresolved key
     * keeps its raw name.
     */
    private fun resolveRawKeys(
        events: List<BlueprintEvent>,
        channelFolders: List<File>,
        preferredLanguage: String?,
    ): List<BlueprintEvent> {
        val keys = events.mapNotNullTo(linkedSetOf()) { ScLocalization.rawKeyOf(it.productName) }
        if (keys.isEmpty()) return events
        val resolved = linkedMapOf<String, String>()
        for (folder in channelFolders) {
            val open = keys.filterTo(linkedSetOf()) { it !in resolved }
            if (open.isEmpty()) break
            ScLocalization.resolveKeys(folder, open, preferredLanguage).forEach { (k, v) -> resolved.putIfAbsent(k, v) }
        }
        return events.map { event ->
            val key = ScLocalization.rawKeyOf(event.productName) ?: return@map event
            val name = resolved[key] ?: return@map event.copy(localizationKey = key)
            event.copy(productName = name, category = BlueprintParser.categorize(name), localizationKey = key)
        }
    }

    /** Serialize an export to pretty JSON text. */
    fun toJson(export: BlueprintExport): String = json.encodeToString(BlueprintExport.serializer(), export)

    /** Write an export to [output] as UTF-8 JSON, creating parent folders as needed. */
    fun writeJson(export: BlueprintExport, output: File) {
        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(toJson(export), Charsets.UTF_8)
    }
}
