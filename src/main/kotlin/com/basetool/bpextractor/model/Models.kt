package com.basetool.bpextractor.model

import kotlinx.serialization.Serializable

/**
 * One received-blueprint event, parsed from a single
 * `Added notification "Received Blueprint: <name>: " [id]` line in a Game.log.
 *
 * All fields except [productName] and [receivedAt] may be absent depending on
 * what a given log build wrote, so they are nullable. We intentionally keep
 * every scrap of information the log carries about the blueprint — the player
 * who received it, when, in which game build, and from which file — because the
 * blueprint detail is the whole point of this tool.
 */
@Serializable
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
)

/** A player seen across the scanned logs, with how many blueprints they received. */
@Serializable
data class PlayerSummary(
    val handle: String,
    val blueprintCount: Int,
)

/** Top-level JSON document written to the user-chosen output file. */
@Serializable
data class BlueprintExport(
    val schemaVersion: Int = 1,
    val tool: String,
    val toolVersion: String,
    val generatedAt: String,
    val sourceFolder: String,
    /**
     * Extra channel folders swept besides [sourceFolder] (currently the sibling HOTFIX
     * channel next to LIVE), or null when only [sourceFolder] was scanned. Additive
     * nullable field within schemaVersion 1 (basetool ADR-0008 evolution rule).
     */
    val additionalSourceFolders: List<String>? = null,
    val logFilesScanned: Int,
    val blueprintCount: Int,
    val players: List<PlayerSummary>,
    val blueprints: List<BlueprintEvent>,
)
