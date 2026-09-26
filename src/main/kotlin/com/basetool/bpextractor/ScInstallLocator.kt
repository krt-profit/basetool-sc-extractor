package com.basetool.bpextractor

import java.io.File

/**
 * Finds the Star Citizen channel folder to pre-fill when the user has not picked one yet.
 *
 * Read-only and best-effort: every source is optional, a failing one is skipped, and nothing is logged
 * or kept beyond the folder path it yields.
 */
object ScInstallLocator {

    /** The launcher's own log line naming the folder it started the game from. */
    private val LAUNCH_LINE = Regex("""Launching Star Citizen (\S+) from \((.+?)\)""")

    /** Literal prefilter for [LAUNCH_LINE]; the launcher log also carries account activity, never read further. */
    private const val LAUNCH_MARKER = "Launching Star Citizen "

    /** The game executable inside `<channel>\Bin64`. */
    private const val SC_EXECUTABLE = "StarCitizen.exe"

    /** The subfolder of a channel that holds [SC_EXECUTABLE]. */
    private const val BIN_FOLDER = "Bin64"

    /** The folder the launcher installs every channel into. */
    private const val INSTALL_ROOT_NAME = "StarCitizen"

    /** Where the launcher installs by default, relative to a drive root. */
    private val DEFAULT_INSTALL_ROOTS = listOf(
        "Program Files/Roberts Space Industries/StarCitizen",
        "Program Files (x86)/Roberts Space Industries/StarCitizen",
        "Roberts Space Industries/StarCitizen",
    )

    /** The two channels the blueprint workflow reads, in preference order. */
    private val SHARED_CHANNELS = listOf(BlueprintExtractor.PRIMARY_CHANNEL_NAME, BlueprintExtractor.SIBLING_CHANNEL_NAME)

    /**
     * The channel folder to pre-fill, or `null` when nothing fits.
     *
     * Order: the [remembered] folder when it still exists, else its LIVE/HOTFIX sibling; then the folder
     * the launcher last started LIVE or HOTFIX from; then a running game; then the default install
     * roots on every drive. A candidate holding logs wins over one that merely exists.
     */
    fun locate(
        remembered: String?,
        launcherLogs: List<File> = defaultLauncherLogs(),
        runningExecutables: () -> List<String> = ::runningExecutables,
        driveRoots: List<File> = File.listRoots()?.toList().orEmpty(),
    ): File? {
        if (!remembered.isNullOrBlank()) {
            val folder = File(remembered)
            if (folder.isDirectory) return folder
            movedChannel(folder)?.let { return it }
        }
        val launched = runCatching { launchedFolders(launcherLogs) }.getOrElse { emptyList() }
        val running = runCatching { runningExecutables() }.getOrElse { emptyList() }.mapNotNull(::channelOfExecutable)
        val installed = driveRoots.flatMap { root -> DEFAULT_INSTALL_ROOTS.map { File(root, it) } }
        val candidates = (sharedChannelsOf(launched.asReversed()) + sharedChannelsOf(running) + sharedChannelsOf(installed))
            .distinctBy { it.absolutePath.lowercase() }
            .toList()
        return candidates.firstOrNull { BlueprintExtractor.holdsChannelLogs(it) } ?: candidates.firstOrNull { it.isDirectory }
    }

    /**
     * The LIVE/HOTFIX sibling of a remembered channel [folder] that no longer exists, when it holds logs —
     * the launcher renames LIVE to HOTFIX and back to install a patch.
     */
    private fun movedChannel(folder: File): File? {
        val siblingName = BlueprintExtractor.siblingChannelName(folder.name) ?: return null
        val parent = folder.absoluteFile.parentFile ?: return null
        return File(parent, siblingName).takeIf { BlueprintExtractor.holdsChannelLogs(it) }
    }

    /**
     * The channel folder a user most likely meant when [picked] holds no logs itself: a LIVE/HOTFIX
     * channel inside a picked `StarCitizen` install root, or the channel a picked subfolder such as
     * `logbackups` or `Bin64` belongs to. `null` when neither holds logs.
     */
    fun suggestChannel(picked: File): File? {
        if (!picked.isDirectory || BlueprintExtractor.holdsChannelLogs(picked)) return null
        val inside = SHARED_CHANNELS.map { File(picked, it) }.firstOrNull { BlueprintExtractor.holdsChannelLogs(it) }
        if (inside != null) return inside
        return generateSequence(picked.absoluteFile.parentFile) { it.parentFile }
            .take(MAX_PARENT_LEVELS)
            .firstOrNull { BlueprintExtractor.holdsChannelLogs(it) }
    }

    /** How far [suggestChannel] walks up from a picked subfolder. */
    private const val MAX_PARENT_LEVELS = 3

    /**
     * For each folder, the LIVE/HOTFIX channel it stands for: itself when it is one, else the LIVE and
     * HOTFIX folders inside its `StarCitizen` install root.
     */
    private fun sharedChannelsOf(folders: List<File>): Sequence<File> = folders.asSequence().flatMap { folder ->
        when {
            BlueprintExtractor.siblingChannelName(folder.name) != null -> sequenceOf(folder)
            folder.name.equals(INSTALL_ROOT_NAME, ignoreCase = true) -> SHARED_CHANNELS.asSequence().map { File(folder, it) }
            else -> folder.absoluteFile.parentFile
                ?.takeIf { it.name.equals(INSTALL_ROOT_NAME, ignoreCase = true) }
                ?.let { root -> SHARED_CHANNELS.asSequence().map { File(root, it) } }
                .orEmpty()
        }
    }

    /** Every channel folder named by a launch line in [logs], oldest file and line first. */
    fun launchedFolders(logs: List<File>): List<File> =
        logs.filter { it.isFile }.flatMap { log ->
            log.useLines(Charsets.UTF_8) { lines ->
                lines.filter { LAUNCH_MARKER in it }.mapNotNull(::parseLaunchLine).toList()
            }
        }

    /**
     * The channel folder named by one launcher log line, with the JSON escaping of its backslashes
     * undone; `null` for any other line.
     */
    fun parseLaunchLine(line: String): File? {
        val match = LAUNCH_LINE.find(line) ?: return null
        val path = match.groupValues[2].replace("\\\\", "\\").trim()
        return if (path.isEmpty()) null else File(path)
    }

    /** The channel folder of a `<channel>\Bin64\StarCitizen.exe` path, or `null` for any other path. */
    fun channelOfExecutable(path: String): File? {
        val exe = File(path)
        if (!exe.name.equals(SC_EXECUTABLE, ignoreCase = true)) return null
        val bin = exe.parentFile ?: return null
        if (!bin.name.equals(BIN_FOLDER, ignoreCase = true)) return null
        return bin.parentFile
    }

    /** The launcher's current and previous log, oldest first. */
    fun defaultLauncherLogs(): List<File> {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val logs = File(appData, "rsilauncher/logs")
        return listOf(File(logs, "log.old.log"), File(logs, "log.log"))
    }

    /** Command paths of the running processes this user may see. */
    private fun runningExecutables(): List<String> =
        ProcessHandle.allProcesses()
            .map { it.info().command().orElse("") }
            .filter { it.isNotEmpty() }
            .toList()
}
