package com.basetool.bpextractor

import com.basetool.bpextractor.model.BlueprintEvent
import java.io.File

/**
 * Pure, side-effect-free parsing of Star Citizen Game.log files for received
 * blueprints. No mission logic — by design we only care about what blueprints a
 * player received and everything the log tells us about each one.
 *
 * The signal line looks like:
 * ```
 * <2026-03-26T16:49:31.050Z> [Notice] <SHUDEvent_OnNotification> Added notification
 *   "Received Blueprint: Yubarev "Mirage" Pistol: " [19] to queue. New queue size: 2,
 *   MissionId: [00000000-0000-0000-0000-000000000000], ObjectiveId: [] [...]
 * ```
 * Note three real-world quirks the patterns below handle:
 *  - the item name can itself contain double quotes (`Yubarev "Mirage" Pistol`),
 *  - it can contain parentheses, slashes and hyphens (`Yubarev Pistol Battery (10 cap)`,
 *    `Sth/2/C Cirrus`, `ADP-mk4 Core Woodland`),
 *  - it can carry a trailing space (`Antium Legs Moss Camo `) which we trim.
 *
 * The MissionId on a blueprint line is always all-zero, so it is useless for
 * attribution — which is fine, because mission data is explicitly out of scope.
 * The *receiving player* instead comes from the login lines of the same file.
 */
object BlueprintParser {

    /** Leading `<ISO-8601>` timestamp at the very start of every log line. */
    private val TIMESTAMP = Regex("""^<([^>]+)>""")

    /**
     * The one authoritative "you received a blueprint" line. Anchored on
     * `Added notification` so the noisy follow-up lines (the bare queue echo and
     * the later `UpdateNotificationItem` Next/StartFade/Remove lines) are ignored
     * — they all repeat the same text and would otherwise inflate the count ~6x.
     *
     * Group 1 = item name (non-greedy, up to the `: " [<digits>]` terminator),
     * Group 2 = notification id.
     */
    private val BLUEPRINT = Regex("""Added notification "Received Blueprint: (.+?): " \[(\d+)]""")

    /** Optional sibling fields on the same blueprint line. */
    private val QUEUE_SIZE = Regex("""New queue size: (\d+)""")

    /** Build number embedded in the SC backup-log file name: `Game Build(11518367) ...`. */
    private val BUILD_FROM_NAME = Regex("""Build\((\d+)\)""")

    // --- Player identity lines (first match in a file wins) ----------------

    /** `<Legacy login response> ... User Login Success - Handle[greluc] - ...` */
    private val LOGIN_HANDLE = Regex("""User Login Success - Handle\[([^\]]+)]""")

    /**
     * `<AccountLoginCharacterStatus_Character> Character: ... geid 202153876894 -
     *  accountId 412645 - name greluc - state STATE_CURRENT`
     * Most reliable identity line — we anchor on the full geid/accountId/name
     * pattern for precision but keep only the handle (name); geid and accountId
     * are deliberately not stored or exported.
     */
    private val CHAR_STATUS = Regex(
        """geid (\d+) - accountId (\d+) - name (\S+) - state STATE_CURRENT"""
    )

    /** `... nickname="greluc" playerGEID=202153876894 ...` (network handshake fallback). */
    private val NICKNAME = Regex("""nickname="([^"]+)"""")

    /** Identity of the player a single log file belongs to. */
    data class PlayerIdentity(
        val handle: String,
    )

    /** Result of parsing one file: the player it belongs to plus its blueprints. */
    data class FileResult(
        val player: PlayerIdentity?,
        val blueprints: List<BlueprintEvent>,
    )

    /**
     * Parse a single Game.log file. Streams line by line so multi-hundred-MB
     * logs never get loaded whole. Unreadable bytes are replaced, never fatal.
     */
    fun parseFile(file: File): FileResult {
        val gameBuild = BUILD_FROM_NAME.find(file.name)?.groupValues?.get(1)
        val blueprints = mutableListOf<BlueprintEvent>()
        var player: PlayerIdentity? = null

        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                // Resolve the player once, from whichever identity line shows up first.
                if (player == null) {
                    player = extractPlayer(line)
                }

                val bp = BLUEPRINT.find(line) ?: continue
                val name = bp.groupValues[1].trim()
                if (name.isEmpty()) continue

                blueprints += BlueprintEvent(
                    productName = name,
                    category = categorize(name),
                    receivedAt = TIMESTAMP.find(line)?.groupValues?.get(1) ?: "",
                    player = player?.handle,
                    notificationId = bp.groupValues[2].toIntOrNull(),
                    queueSize = QUEUE_SIZE.find(line)?.groupValues?.get(1)?.toIntOrNull(),
                    gameBuild = gameBuild,
                    sourceFile = file.name,
                )
            }
        }

        // A player line can appear *after* an early blueprint in rare logs; back-fill.
        val resolved = player
        val finalBlueprints =
            if (resolved != null && blueprints.any { it.player == null }) {
                blueprints.map { if (it.player == null) it.copy(player = resolved.handle) else it }
            } else {
                blueprints
            }

        return FileResult(resolved, finalBlueprints)
    }

    private fun extractPlayer(line: String): PlayerIdentity? {
        CHAR_STATUS.find(line)?.let {
            return PlayerIdentity(handle = it.groupValues[3])
        }
        LOGIN_HANDLE.find(line)?.let {
            return PlayerIdentity(handle = it.groupValues[1])
        }
        NICKNAME.find(line)?.let {
            return PlayerIdentity(handle = it.groupValues[1])
        }
        return null
    }

    /**
     * Best-effort item classification from the localised name. Purely derived
     * (the log doesn't state a category), provided as a convenience for filtering.
     * Order matters: ammo/tool keywords are checked before the broad weapon
     * keywords so "S71 Rifle Magazine" lands in Ammo, not Weapon.
     */
    fun categorize(name: String): String {
        val n = name.lowercase()
        return when {
            "mining laser" in n -> "MiningTool"
            "magazine" in n || "battery" in n || Regex("""\(\d+\s*cap\)""").containsMatchIn(n) -> "Ammo"
            listOf("helmet", "core", "arms", "legs", "armor", "flight suit", "undersuit", "torso", "backpack")
                .any { it in n } -> "Armor"
            listOf("pistol", "rifle", "shotgun", "smg", "cannon", "sniper", "crossbow", "lmg", "gun", "launcher")
                .any { it in n } -> "Weapon"
            else -> "Other"
        }
    }
}
