package com.basetool.bpextractor

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueprintExtractorTest {

    private fun tempChannel(): File = Files.createTempDirectory("sc-channel").toFile()

    @Test
    fun `picks Game_log and every log in logbackups`() {
        val channel = tempChannel()
        try {
            File(channel, "Game.log").writeText("x")
            val backups = File(channel, "logbackups").apply { mkdirs() }
            File(backups, "Game Build(1) 05 Dec 25 (15 44 14).log").writeText("x")
            File(backups, "Game Build(2) 06 Dec 25 (08 55 41).log").writeText("x")
            File(backups, "notes.txt").writeText("x") // ignored: not a .log

            val files = BlueprintExtractor.findLogFiles(channel)

            assertEquals(3, files.size)
            assertTrue(files.any { it.name == "Game.log" })
            assertTrue(files.any { it.name.startsWith("Game Build(1)") })
            assertTrue(files.any { it.name.startsWith("Game Build(2)") })
            assertFalse(files.any { it.name == "notes.txt" })
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `works with only logbackups (no current Game_log)`() {
        val channel = tempChannel()
        try {
            val backups = File(channel, "logbackups").apply { mkdirs() }
            File(backups, "Game Build(1) a.log").writeText("x")
            assertEquals(1, BlueprintExtractor.findLogFiles(channel).size)
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `empty when neither Game_log nor logbackups present`() {
        val channel = tempChannel()
        try {
            File(channel, "build_manifest.id").writeText("x") // unrelated file
            assertTrue(BlueprintExtractor.findLogFiles(channel).isEmpty())
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `does not scan nested folders other than logbackups`() {
        val channel = tempChannel()
        try {
            val other = File(channel, "USER/Client/0/Logs").apply { mkdirs() }
            File(other, "stray.log").writeText("x")
            assertTrue(BlueprintExtractor.findLogFiles(channel).isEmpty())
        } finally {
            channel.deleteRecursively()
        }
    }
}
