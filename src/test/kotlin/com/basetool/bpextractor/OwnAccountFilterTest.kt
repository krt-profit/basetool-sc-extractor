package com.basetool.bpextractor

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The own-account filter over the anonymised corpus fixture (`game-log-corpus-v1`, the twin of the
 * basetool's `game-log-corpus-v1.json`) and over a corpus that mixes a second account in.
 */
class OwnAccountFilterTest {

    private fun corpus(): File = File(requireNotNull(javaClass.getResource("/game-log-corpus-v1/LIVE")).toURI())

    private fun blueprintLine(name: String, id: Int, ts: String) =
        "<$ts> [Notice] <SHUDEvent_OnNotification> Added notification \"Received Blueprint: $name: \" " +
            "[$id] to queue. New queue size: 1, MissionId: [00000000-0000-0000-0000-000000000000], " +
            "ObjectiveId: [] [Team_CoreGameplayFeatures][Missions][Comms]"

    private fun loginLine(handle: String, ts: String) =
        "<$ts> [Notice] <Legacy login response> [CIG-net] User Login Success - Handle[$handle] - Time[0] " +
            "[Team_GameServices][Login]"

    /** Copies the corpus into a fresh channel folder the test may add files to. */
    private fun corpusCopy(): File {
        val channel = Files.createTempDirectory("sc-corpus").toFile()
        corpus().copyRecursively(channel)
        return channel
    }

    @Test
    fun `the anonymised corpus yields its 31 events from 12 log files of one account`() {
        val result = BlueprintExtractor.extract(corpus())

        assertEquals(12, result.export.logFilesScanned)
        assertEquals(31, result.export.blueprintCount)
        assertEquals(31, result.export.blueprints.map { it.productName }.distinct().size)
        val account = result.accounts.single()
        assertEquals("PLAYER_A", account.handle)
        assertEquals(12, account.logFiles)
        assertEquals(31, account.blueprintCount)
        assertTrue(result.export.blueprints.any { it.productName == "Oracle Helmet" })
        assertTrue(result.export.blueprints.any { it.productName.startsWith("[E-S3] CF-337 Panther") })
    }

    @Test
    fun `a second account in the same folder is counted but not exported by default`() {
        val channel = corpusCopy()
        try {
            val backups = File(channel, "logbackups")
            File(backups, "Game Build(12660092) 03 Jan 26 (10 00 00).log").writeText(
                listOf(
                    loginLine("PLAYER_B", "2026-01-03T10:00:00.000Z"),
                    blueprintLine("Arclight Pistol", 1, "2026-01-03T10:05:00.000Z"),
                    blueprintLine("Arrowhead Sniper Rifle", 2, "2026-01-03T10:06:00.000Z"),
                ).joinToString("\n"),
            )
            File(backups, "Game Build(12660092) 03 Jan 26 (11 00 00).log").writeText(
                listOf(
                    loginLine("PLAYER_B", "2026-01-03T11:00:00.000Z"),
                    blueprintLine("Oracle Helmet", 3, "2026-01-03T11:05:00.000Z"),
                ).joinToString("\n"),
            )

            val result = BlueprintExtractor.extract(channel)

            assertEquals(34, result.export.blueprintCount)
            assertEquals(listOf("PLAYER_A", "PLAYER_B"), result.accounts.map { it.handle })
            val own = requireNotNull(result.defaultAccount)
            assertEquals("PLAYER_A", own.handle)

            val ownExport = BlueprintExtractor.exportFor(result.export, own)
            assertEquals(31, ownExport.blueprintCount)
            assertTrue(ownExport.blueprints.all { it.player == "PLAYER_A" })
            assertEquals(listOf("PLAYER_A"), ownExport.players.map { it.handle })

            val other = BlueprintExtractor.exportFor(result.export, result.accounts[1])
            assertEquals(3, other.blueprintCount)
            assertEquals(listOf("PLAYER_B"), other.players.map { it.handle })
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `the account with the most log files wins over the one with the most blueprints`() {
        val channel = Files.createTempDirectory("sc-accounts").toFile()
        try {
            val backups = File(channel, "logbackups").apply { mkdirs() }
            repeat(3) { i ->
                File(backups, "Game Build(1) own $i.log").writeText(loginLine("OwnPilot", "2026-01-0${i + 1}T10:00:00.000Z"))
            }
            File(backups, "Game Build(1) other.log").writeText(
                (listOf(loginLine("AltPilot", "2026-01-05T10:00:00.000Z")) +
                    (1..5).map { blueprintLine("Item $it", it, "2026-01-05T10:0$it:00.000Z") }).joinToString("\n"),
            )

            val result = BlueprintExtractor.extract(channel)

            assertEquals("OwnPilot", result.defaultAccount?.handle)
            assertEquals(0, BlueprintExtractor.exportFor(result.export, result.defaultAccount!!).blueprintCount)
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `files naming no account form their own group after the known accounts`() {
        val channel = Files.createTempDirectory("sc-unknown").toFile()
        try {
            val backups = File(channel, "logbackups").apply { mkdirs() }
            File(backups, "Game Build(1) a.log").writeText(blueprintLine("Loose Item", 1, "2026-01-01T10:00:00.000Z"))
            File(backups, "Game Build(1) b.log").writeText(blueprintLine("Other Loose Item", 2, "2026-01-01T11:00:00.000Z"))
            File(backups, "Game Build(1) c.log").writeText(
                listOf(loginLine("OwnPilot", "2026-01-02T10:00:00.000Z"), blueprintLine("Own Item", 3, "2026-01-02T10:01:00.000Z"))
                    .joinToString("\n"),
            )

            val result = BlueprintExtractor.extract(channel)

            assertEquals(listOf("OwnPilot", null), result.accounts.map { it.handle })
            val unknown = result.accounts.last()
            assertNull(unknown.handle)
            assertEquals(2, unknown.logFiles)
            val export = BlueprintExtractor.exportFor(result.export, unknown)
            assertEquals(listOf("Loose Item", "Other Loose Item"), export.blueprints.map { it.productName })
            assertTrue(export.players.isEmpty())
        } finally {
            channel.deleteRecursively()
        }
    }
}
