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

    /**
     * Collect the SC log files to scan from a channel folder (e.g. `…\StarCitizen\LIVE`):
     * the current `Game.log` in that folder plus every `*.log` in its `logbackups`
     * subfolder (the `Game Build(...).log` session backups). Current first, then backups
     * by name; the export is re-sorted by blueprint timestamp anyway.
     */
    fun findLogFiles(channelFolder: File): List<File> {
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
