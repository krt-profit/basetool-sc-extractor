package com.basetool.bpextractor

import com.basetool.bpextractor.model.BlueprintEvent
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Pure, side-effect-free parsing of Star Citizen `Game.log` files for received blueprints.
 *
 * The signal line looks like:
 * ```
 * <2026-03-26T16:49:31.050Z> [Notice] <SHUDEvent_OnNotification> Added notification
 *   "Received Blueprint: Yubarev "Mirage" Pistol: " [19] to queue. New queue size: 2,
 *   MissionId: [00000000-0000-0000-0000-000000000000], ObjectiveId: [] [...]
 * ```
 * The item name may contain double quotes, parentheses, slashes and hyphens, and a trailing space is
 * trimmed. The label before the name is localised ([BUILT_IN_FORMATS]); the receiving player comes
 * from the login lines of the same file.
 */
object BlueprintParser {

    /** Leading `<ISO-8601>` timestamp at the very start of every log line. */
    private val TIMESTAMP = Regex("""^<([^>]+)>""")

    /**
     * The built-in notification format strings (`%s` is the item name), used when the player's
     * installation provides no readable `global.ini` for [ScLocalization.detect].
     *
     * A closed whitelist: an entry is added only on authoritative evidence from the localisation source
     * or a real log of that client language.
     */
    val BUILT_IN_FORMATS: List<String> = listOf(
        "Received Blueprint: %s",
        "Bauplan erhalten: %s",
        "Bauplan überchoo: %s",
    )

    /** The item-name placeholder inside a localisation value. */
    private const val NAME_PLACEHOLDER = "%s"

    /**
     * Compiles one matcher per format, anchored on `Added notification` so the follow-up queue lines
     * are not counted. In every pattern group 1 is the item name and group 2 the notification id; a
     * format without `%s` is dropped.
     */
    fun compile(formats: List<String>): List<Regex> =
        formats.filter { NAME_PLACEHOLDER in it }.map { format ->
            val prefix = format.substringBefore(NAME_PLACEHOLDER)
            val suffix = format.substringAfter(NAME_PLACEHOLDER)
            Regex(
                """Added notification "(?i:""" + Regex.escape(prefix) + """)(.+?)(?i:""" +
                    Regex.escape(suffix) + """): " \[(\d+)]""",
            )
        }

    /** Compiled [BUILT_IN_FORMATS] — the default when a caller has nothing better. */
    val BUILT_IN_PATTERNS: List<Regex> = compile(BUILT_IN_FORMATS)

    /**
     * Literal prefilter for the patterns from [compile]: a line without it is never matched against a
     * regex. Must stay a literal, case-sensitive prefix of every pattern.
     */
    private const val BLUEPRINT_MARKER = "Added notification \""

    /**
     * Optional sibling fields on the same blueprint line. Only ever run on a line one of the
     * [compile] patterns already matched, so case-insensitivity here is free.
     */
    private val QUEUE_SIZE = Regex("""New queue size: (\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Build number embedded in the SC backup-log file name: `Game Build(11518367) ...` — and in
     * the header line described by [BUILD_HEADER_MARKER], which carries the same shape.
     */
    private val BUILD_FROM_NAME = Regex("""Build\((\d+)\)""", RegexOption.IGNORE_CASE)

    /**
     * Marker of the first-line header that names the log's build, e.g.
     * `BackupNameAttachment=" Build(11518367) 26 Mar 26 (17 24 58)"`; the only build source for the live
     * `Game.log`, whose file name carries none.
     */
    private const val BUILD_HEADER_MARKER = "BackupNameAttachment="

    /** Matches the login line `User Login Success - Handle[<handle>]` and captures the player handle. */
    private val LOGIN_HANDLE = Regex("""User Login Success - Handle\[([^\]]+)]""", RegexOption.IGNORE_CASE)

    /**
     * Matches the `STATE_CURRENT` character-status line, the most reliable identity line; only the
     * handle (`name`) is kept, the geid and account id are never stored or exported.
     */
    private val CHAR_STATUS = Regex(
        """geid (\d+) - accountId (\d+) - name (\S+) - state STATE_CURRENT""",
        RegexOption.IGNORE_CASE,
    )

    /** `... nickname="greluc" playerGEID=202153876894 ...` (network handshake fallback). */
    private val NICKNAME = Regex("""nickname="([^"]+)"""", RegexOption.IGNORE_CASE)

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
     * Parses a single `Game.log` file line by line without loading it whole; unreadable bytes are
     * replaced, never fatal. [blueprintPatterns] come from [compile], and [onBytesRead] receives the
     * cumulative raw bytes consumed so far.
     */
    fun parseFile(
        file: File,
        blueprintPatterns: List<Regex> = BUILT_IN_PATTERNS,
        onBytesRead: ((bytesRead: Long) -> Unit)? = null,
    ): FileResult {
        var gameBuild = BUILD_FROM_NAME.find(file.name)?.groupValues?.get(1)
        var buildHeaderPending = gameBuild == null
        val blueprints = mutableListOf<BlueprintEvent>()
        var player: PlayerIdentity? = null

        val input = file.inputStream().let { raw ->
            if (onBytesRead == null) raw else CountingInputStream(raw, onBytesRead)
        }
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (buildHeaderPending) {
                    buildHeaderPending = false
                    if (line.contains(BUILD_HEADER_MARKER, ignoreCase = true)) {
                        gameBuild = BUILD_FROM_NAME.find(line)?.groupValues?.get(1)
                    }
                }

                if (player == null) {
                    player = extractPlayer(line)
                }

                if (BLUEPRINT_MARKER !in line) continue
                val bp = blueprintPatterns.firstNotNullOfOrNull { it.find(line) } ?: continue
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
        if ("geid " in line) {
            CHAR_STATUS.find(line)?.let {
                return PlayerIdentity(handle = it.groupValues[3])
            }
        }
        if ("User Login Success" in line) {
            LOGIN_HANDLE.find(line)?.let {
                return PlayerIdentity(handle = it.groupValues[1])
            }
        }
        if ("nickname=\"" in line) {
            NICKNAME.find(line)?.let {
                return PlayerIdentity(handle = it.groupValues[1])
            }
        }
        return null
    }

    /** `(30 cap)`-style capacity suffix — the ammo marker that isn't a keyword. */
    private val CAP_SUFFIX = Regex("""\(\d+\s*cap\)""")

    /**
     * Word-boundary alternation over [words], so a keyword inside another word never
     * matches ("gun" must not hit "Gungnir", "core" must not hit "Scored").
     */
    private fun wordsRegex(words: List<String>): Regex =
        Regex("""\b(?:${words.joinToString("|") { Regex.escape(it) }})\b""")

    private val MINING_WORDS = wordsRegex(listOf("mining laser"))
    private val AMMO_WORDS = wordsRegex(listOf("magazine", "battery"))
    private val ARMOR_WORDS = wordsRegex(
        listOf("helmet", "core", "arms", "legs", "armor", "flight suit", "undersuit", "torso", "backpack"),
    )
    private val WEAPON_WORDS = wordsRegex(
        listOf("pistol", "rifle", "shotgun", "smg", "cannon", "sniper", "crossbow", "lmg", "gun", "launcher"),
    )

    /**
     * Derives a best-effort category from the localised item name by word-boundary keyword matching.
     * Ammo and tool keywords are checked before weapon keywords, so "S71 Rifle Magazine" is Ammo.
     */
    fun categorize(name: String): String {
        val n = name.lowercase()
        return when {
            MINING_WORDS.containsMatchIn(n) -> "MiningTool"
            AMMO_WORDS.containsMatchIn(n) || CAP_SUFFIX.containsMatchIn(n) -> "Ammo"
            ARMOR_WORDS.containsMatchIn(n) -> "Armor"
            WEAPON_WORDS.containsMatchIn(n) -> "Weapon"
            else -> "Other"
        }
    }

    /**
     * Counts raw bytes as they stream past and reports the running total — feeds
     * within-file progress without buffering or copying anything.
     */
    private class CountingInputStream(
        delegate: InputStream,
        private val onCount: (Long) -> Unit,
    ) : FilterInputStream(delegate) {
        private var total = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                total++
                onCount(total)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                onCount(total)
            }
            return n
        }
    }
}
