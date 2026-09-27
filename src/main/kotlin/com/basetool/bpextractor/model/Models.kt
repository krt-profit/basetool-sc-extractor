package com.basetool.bpextractor.model

/**
 * One received-blueprint event, parsed from a single
 * `Added notification "Received Blueprint: <name>: " [id]` line in a `Game.log`. It stays on the PC;
 * what leaves it is a [BlueprintItem].
 *
 * All fields except [productName] and [receivedAt] are nullable because not every log build writes
 * them.
 */
data class BlueprintEvent(
    /** Localised item name, e.g. `Yubarev "Mirage" Pistol`, `Palatino Core Daystar`. */
    val productName: String,
    /** Best-effort item category derived from the name (Weapon / Armor / …). */
    val category: String,
    /** ISO-8601 UTC timestamp the blueprint notification fired, e.g. `2026-03-26T16:49:31.050Z`. */
    val receivedAt: String,
    /** Player handle active in the source log file (the receiver), or null if undetected. */
    val player: String? = null,
    /** In-game notification queue index from the log line (`[19]`). */
    val notificationId: Int? = null,
    /** Notification queue size reported on the line (`New queue size: 2`). */
    val queueSize: Int? = null,
    /** Star Citizen build number taken from the log file name (`Game Build(11518367)`). */
    val gameBuild: String? = null,
    /** Name of the log file this event came from. */
    val sourceFile: String,
    /**
     * The localisation key the game wrote instead of a name when it had no translation for it, without
     * the leading `@`; [productName] then carries that key's value from the installed `global.ini`, or
     * the raw `@key` when none has it. `null` for every normally rendered name.
     */
    val localizationKey: String? = null,
)

/**
 * One game account the scanned logs belong to, detected per log file from its login lines. Shown to the
 * member to pick their own account; never exported.
 *
 * @param handle the account's handle, or `null` for the log files that name no account
 * @param logFiles how many readable log files belong to it
 * @param blueprintCount how many distinct blueprint events it received
 */
data class LogAccount(
    val handle: String?,
    val logFiles: Int,
    val blueprintCount: Int,
)

/**
 * What a scan found, held in memory for the summary screen and never serialised; the file and the
 * draft are a [BlueprintEnvelope] built from it.
 *
 * @param sourceFolder the channel folder the member picked
 * @param additionalSourceFolders extra channel folders swept besides it (the sibling LIVE/HOTFIX)
 * @param logFilesScanned how many log files were read
 * @param blueprints the events, sorted chronologically
 */
data class BlueprintScan(
    val sourceFolder: String,
    val additionalSourceFolders: List<String> = emptyList(),
    val logFilesScanned: Int,
    val blueprints: List<BlueprintEvent>,
) {
    /** How many events the scan holds. */
    val blueprintCount: Int get() = blueprints.size
}
