package com.basetool.bpextractor

import com.basetool.bpextractor.model.BlueprintEvent
import com.basetool.bpextractor.model.BlueprintExport
import com.basetool.bpextractor.model.PlayerSummary
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** Progress callback: (filesDone, filesTotal, currentFileName). */
typealias ProgressListener = (done: Int, total: Int, current: String) -> Unit

/**
 * Scans a folder of Game.log files, extracts every received blueprint, and
 * produces the export document. Orchestration only — the actual line parsing
 * lives in [BlueprintParser].
 */
object BlueprintExtractor {

    const val TOOL_NAME = "Basetool Blueprint Extractor"
    const val TOOL_VERSION = "1.0.0"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** The channel folder a user normally points the tool at. */
    const val PRIMARY_CHANNEL_NAME = "LIVE"

    /**
     * The patch-cycle channel CIG spins up next to LIVE. Crafting knowledge is account-wide, but
     * each channel writes its own logs, so a blueprint first received while playing HOTFIX is
     * recorded only in the HOTFIX logs. A user who farmed on HOTFIX and then points the tool at
     * LIVE would silently lose those blueprints — so when the picked folder is LIVE we also sweep a
     * sibling HOTFIX folder (see [siblingHotfixFolder]).
     */
    const val SIBLING_CHANNEL_NAME = "HOTFIX"

    /**
     * Collect the SC log files to scan from a channel folder (e.g. `…\StarCitizen\LIVE`): the
     * current `Game.log` in that folder plus every `*.log` in its `logbackups` subfolder (the
     * `Game Build(...).log` session backups). When [channelFolder] is the LIVE channel and a
     * sibling `HOTFIX` folder holding logs sits next to it, that channel's logs are appended too
     * (see [siblingHotfixFolder]). Current first, then backups by name; the export is re-sorted by
     * blueprint timestamp anyway.
     */
    fun findLogFiles(channelFolder: File): List<File> {
        val files = collectChannelLogs(channelFolder).toMutableList()
        siblingHotfixFolder(channelFolder)?.let { files += collectChannelLogs(it) }
        return files
    }

    /**
     * The `Game.log` + every `*.log` in `logbackups` for a single channel folder, current first
     * then backups by name. Empty when [channelFolder] is not a directory or carries no logs.
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
        return files
    }

    /**
     * If [channelFolder] is the LIVE channel directory, returns the sibling [SIBLING_CHANNEL_NAME]
     * (HOTFIX) directory next to it (e.g. `…\StarCitizen\LIVE` → `…\StarCitizen\HOTFIX`) when that
     * folder exists and actually holds SC logs (a `Game.log` or a `logbackups` subfolder). Returns
     * `null` otherwise — when the picked folder isn't LIVE, has no parent, or no usable HOTFIX
     * sibling is present. Pure `File` stats; never writes or browses, so a caller can use it for a
     * cheap live UI hint as well as for the scan itself.
     */
    fun siblingHotfixFolder(channelFolder: File): File? {
        if (!channelFolder.name.equals(PRIMARY_CHANNEL_NAME, ignoreCase = true)) return null
        val parent = channelFolder.absoluteFile.parentFile ?: return null
        val sibling = File(parent, SIBLING_CHANNEL_NAME)
        if (!sibling.isDirectory) return null
        val hasLogs = File(sibling, "Game.log").isFile || File(sibling, "logbackups").isDirectory
        return if (hasLogs) sibling else null
    }

    /**
     * Run extraction over [folder]. Returns the assembled [BlueprintExport]
     * without writing anything to disk (so callers can preview first).
     */
    fun extract(
        channelFolder: File,
        progress: ProgressListener? = null,
    ): BlueprintExport {
        val files = findLogFiles(channelFolder)

        val allBlueprints = mutableListOf<BlueprintEvent>()
        val countsByPlayer = linkedMapOf<String, Int>()

        files.forEachIndexed { index, file ->
            progress?.invoke(index, files.size, file.name)
            val result = BlueprintParser.parseFile(file)
            for (bp in result.blueprints) {
                allBlueprints += bp
                bp.player?.let { countsByPlayer.merge(it, 1, Int::plus) }
            }
        }
        progress?.invoke(files.size, files.size, "")

        // Chronological order; events with an unparseable timestamp sink to the end.
        val sorted = allBlueprints.sortedBy { it.receivedAt.ifEmpty { "￿" } }

        val players = countsByPlayer.entries
            .sortedByDescending { it.value }
            .map { (handle, count) -> PlayerSummary(handle = handle, blueprintCount = count) }

        return BlueprintExport(
            tool = TOOL_NAME,
            toolVersion = TOOL_VERSION,
            generatedAt = Instant.now().toString(),
            sourceFolder = channelFolder.absolutePath,
            logFilesScanned = files.size,
            blueprintCount = sorted.size,
            players = players,
            blueprints = sorted,
        )
    }

    /** Serialize an export to pretty JSON text. */
    fun toJson(export: BlueprintExport): String = json.encodeToString(BlueprintExport.serializer(), export)

    /** Write an export to [output] as UTF-8 JSON, creating parent folders as needed. */
    fun writeJson(export: BlueprintExport, output: File) {
        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(toJson(export), Charsets.UTF_8)
    }
}
