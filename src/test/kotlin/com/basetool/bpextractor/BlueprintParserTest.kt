package com.basetool.bpextractor

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlueprintParserTest {

    private fun sampleFile(): File {
        val url = requireNotNull(javaClass.classLoader.getResource("sample.log")) {
            "sample.log test resource missing"
        }
        return File(url.toURI())
    }

    @Test
    fun `parses exactly one event per blueprint, ignoring the duplicate follow-up lines`() {
        val result = BlueprintParser.parseFile(sampleFile())
        // 5 distinct blueprints; the Yubarev "Mirage" Pistol appears on 4 lines
        // (Added + bare echo + Next + StartFade + Remove) but must count once.
        assertEquals(5, result.blueprints.size)
    }

    @Test
    fun `extracts item name with embedded quotes`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
        assertTrue(bp.any { it.productName == "Yubarev \"Mirage\" Pistol" })
    }

    @Test
    fun `trims trailing space in item name`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
        assertTrue(bp.any { it.productName == "Antium Legs Moss Camo" })
        assertFalse(bp.any { it.productName.endsWith(" ") })
    }

    @Test
    fun `handles slashes and parentheses in names`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
        assertTrue(bp.any { it.productName == "Sth/2/C Cirrus" })
        assertTrue(bp.any { it.productName == "Yubarev Pistol Battery (10 cap)" })
    }

    @Test
    fun `captures timestamp, notification id and queue size`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
            .first { it.productName == "Yubarev \"Mirage\" Pistol" }
        assertEquals("2026-03-26T16:49:31.050Z", bp.receivedAt)
        assertEquals(19, bp.notificationId)
        assertEquals(2, bp.queueSize)
    }

    @Test
    fun `resolves player handle from the character-status line`() {
        val result = BlueprintParser.parseFile(sampleFile())
        val player = assertNotNull(result.player)
        assertEquals("greluc", player.handle)
        assertTrue(result.blueprints.all { it.player == "greluc" })
    }

    @Test
    fun `categorizes items by name`() {
        assertEquals("Weapon", BlueprintParser.categorize("Yubarev \"Mirage\" Pistol"))
        assertEquals("Ammo", BlueprintParser.categorize("S71 Rifle Magazine (30 cap)"))
        assertEquals("MiningTool", BlueprintParser.categorize("Lancet MH2 Mining Laser"))
        assertEquals("Armor", BlueprintParser.categorize("Palatino Core Daystar"))
        assertEquals("Other", BlueprintParser.categorize("Norfield"))
    }

    @Test
    fun `extracts build number from file name`() {
        // parseFile reads the real file name; build only resolves for SC-named files.
        val tmp = File.createTempFile("Game Build(11518367) 26 Mar 26 (17 24 58)", ".log")
        tmp.deleteOnExit()
        sampleFile().copyTo(tmp, overwrite = true)
        val bp = BlueprintParser.parseFile(tmp).blueprints.first()
        assertEquals("11518367", bp.gameBuild)
    }
}
