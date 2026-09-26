package com.basetool.bpextractor

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScInstallLocatorTest {

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("sc-install").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun channel(root: File, name: String, withLogs: Boolean = true): File =
        File(root, "StarCitizen/$name").apply {
            mkdirs()
            if (withLogs) File(this, "Game.log").writeText("x")
        }

    private fun launcherLog(root: File, vararg folders: File): File =
        File(root, "log.log").apply {
            writeText(
                folders.joinToString("\n") { f ->
                    val escaped = f.absolutePath.replace("\\", "\\\\")
                    """{"t":"2026-09-25T09:30:00.000Z","[main][info] ":"[Launcher::launch] Launching Star Citizen ${f.name} from ($escaped)"}"""
                } + "\n" + """{"t":"2026-09-25T09:31:00.000Z","[main][info] ":"unrelated account line"}""",
            )
        }

    @Test
    fun `a launch line yields its folder with the JSON escaping undone`() {
        val line = """{"[main][info] ":"[Launcher::launch] Launching Star Citizen LIVE from """ +
            """(D:\\Games\\Roberts Space Industries\\StarCitizen\\LIVE)"}"""
        assertEquals(
            File("""D:\Games\Roberts Space Industries\StarCitizen\LIVE"""),
            ScInstallLocator.parseLaunchLine(line),
        )
        assertNull(ScInstallLocator.parseLaunchLine("""{"[main][info] ":"Signed in"}"""))
    }

    @Test
    fun `the running game's executable names its channel folder`() {
        assertEquals(
            File("""E:\SC\StarCitizen\HOTFIX"""),
            ScInstallLocator.channelOfExecutable("""E:\SC\StarCitizen\HOTFIX\Bin64\StarCitizen.exe"""),
        )
        assertNull(ScInstallLocator.channelOfExecutable("""C:\Windows\explorer.exe"""))
        assertNull(ScInstallLocator.channelOfExecutable("""E:\SC\StarCitizen.exe"""))
    }

    @Test
    fun `a remembered folder that still exists wins`() = withRoot { root ->
        val archive = File(root, "archive").apply { mkdirs() }
        channel(root, "LIVE")
        assertEquals(archive, ScInstallLocator.locate(archive.path, emptyList(), { emptyList() }, emptyList()))
    }

    @Test
    fun `a remembered LIVE renamed to HOTFIX is followed to its sibling`() = withRoot { root ->
        val hotfix = channel(root, "HOTFIX")
        val goneLive = File(root, "StarCitizen/LIVE")
        assertEquals(hotfix, ScInstallLocator.locate(goneLive.path, emptyList(), { emptyList() }, emptyList()))
    }

    @Test
    fun `the launcher's last LIVE or HOTFIX launch is used when nothing is remembered`() = withRoot { root ->
        val other = File(root, "other").apply { mkdirs() }
        val live = channel(root, "LIVE")
        val oldLive = channel(other, "LIVE")
        val log = launcherLog(root, oldLive, live)
        assertEquals(live, ScInstallLocator.locate(null, listOf(log), { emptyList() }, emptyList()))
    }

    @Test
    fun `a PTU launch points at the LIVE channel of the same install`() = withRoot { root ->
        val live = channel(root, "LIVE")
        val ptu = channel(root, "PTU")
        val log = launcherLog(root, ptu)
        assertEquals(live, ScInstallLocator.locate(null, listOf(log), { emptyList() }, emptyList()))
    }

    @Test
    fun `a running game is used when the launcher log names nothing`() = withRoot { root ->
        val live = channel(root, "LIVE")
        val exe = File(live, "Bin64/StarCitizen.exe").path
        assertEquals(live, ScInstallLocator.locate(null, emptyList(), { listOf(exe) }, emptyList()))
    }

    @Test
    fun `the default install roots of every drive are tried last`() = withRoot { root ->
        val drive = File(root, "drive").apply { mkdirs() }
        val live = File(drive, "Program Files/Roberts Space Industries/StarCitizen/LIVE").apply { mkdirs() }
        File(live, "Game.log").writeText("x")
        assertEquals(live, ScInstallLocator.locate(null, emptyList(), { emptyList() }, listOf(drive)))
    }

    @Test
    fun `a folder holding logs wins over one that merely exists`() = withRoot { root ->
        val emptyLive = channel(root, "LIVE", withLogs = false)
        val drive = File(root, "drive").apply { mkdirs() }
        val installed = File(drive, "Program Files/Roberts Space Industries/StarCitizen/LIVE").apply { mkdirs() }
        File(installed, "Game.log").writeText("x")
        val log = launcherLog(root, emptyLive)
        assertEquals(installed, ScInstallLocator.locate(null, listOf(log), { emptyList() }, listOf(drive)))
    }

    @Test
    fun `an existing empty channel folder is still offered when nothing holds logs`() = withRoot { root ->
        val drive = File(root, "drive").apply { mkdirs() }
        val live = File(drive, "Program Files/Roberts Space Industries/StarCitizen/LIVE").apply { mkdirs() }
        assertEquals(live, ScInstallLocator.locate(null, emptyList(), { emptyList() }, listOf(drive)))
    }

    @Test
    fun `nothing found yields null and a failing source is skipped`() = withRoot { root ->
        assertNull(ScInstallLocator.locate(null, listOf(File(root, "missing.log")), { error("no processes") }, emptyList()))
    }

    @Test
    fun `a picked install root or channel subfolder suggests the channel`() = withRoot { root ->
        val live = channel(root, "LIVE")
        val backups = File(live, "logbackups").apply { mkdirs() }
        val bin = File(live, "Bin64").apply { mkdirs() }
        assertEquals(live, ScInstallLocator.suggestChannel(File(root, "StarCitizen")))
        assertEquals(live, ScInstallLocator.suggestChannel(bin))
        assertNull(ScInstallLocator.suggestChannel(live))
        assertNull(ScInstallLocator.suggestChannel(backups.parentFile))
        assertNull(ScInstallLocator.suggestChannel(File(root, "nowhere")))
    }
}
