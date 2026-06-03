package com.basetool.bpextractor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.basetool.bpextractor.ui.CommunityDisclaimerFooter
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FieldLabel
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.GreetingHeader
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.KrtTextField
import com.basetool.bpextractor.ui.KrtTheme
import com.basetool.bpextractor.ui.KrtTitleBar
import com.basetool.bpextractor.ui.ResizeCorner
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.rememberHoneycombPainter
import com.basetool.bpextractor.ui.tiled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** UI state for the single-screen extractor. */
private class AppState {
    var channelFolder by mutableStateOf(defaultChannelFolder())
    var outputFile by mutableStateOf(defaultOutputPath())
    var running by mutableStateOf(false)
    var status by mutableStateOf("Wähle den Star-Citizen-Channel-Ordner (z. B. …\\StarCitizen\\LIVE) und einen Ziel-Pfad für die JSON.")
    var resultSummary by mutableStateOf("")
    var isError by mutableStateOf(false)

    private companion object {
        fun defaultOutputPath(): String {
            val docs = File(System.getProperty("user.home"), "Documents")
            val base = if (docs.isDirectory) docs else File(System.getProperty("user.home"))
            return File(base, "blueprints.json").absolutePath
        }

        /** Pre-fill the standard LIVE install path if it exists on this machine. */
        fun defaultChannelFolder(): String {
            val standard = File("""C:\Program Files\Roberts Space Industries\StarCitizen\LIVE""")
            return if (standard.isDirectory) standard.absolutePath else ""
        }
    }
}

private fun pickFolder(initial: String): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Star-Citizen-Channel-Ordner wählen (z. B. …\\LIVE)"
        initial.takeIf { it.isNotBlank() }?.let {
            val f = File(it)
            currentDirectory = if (f.isDirectory) f else f.parentFile
        }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else null
}

private fun pickSaveFile(initial: String): String? {
    val initialFile = File(initial.ifBlank { "blueprints.json" })
    val dialog = FileDialog(null as Frame?, "JSON-Ausgabedatei wählen", FileDialog.SAVE).apply {
        directory = initialFile.parent
        file = initialFile.name
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val withExt = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
    return File(dir, withExt).absolutePath
}

@Composable
private fun ExtractorScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val honeycomb = rememberHoneycombPainter()

    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            GreetingHeader(
                title = "Basetool Blueprint Extractor",
                subtitle = "Liest die erhaltenen Blueprints aus Star-Citizen-Game.log-Dateien aus und schreibt sie als JSON.",
            )

            // --- Channel folder ---
            Column {
                FieldLabel("Star-Citizen-Channel-Ordner")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KrtTextField(
                        value = state.channelFolder,
                        onValueChange = { state.channelFolder = it },
                        placeholder = "z. B. C:\\Program Files\\Roberts Space Industries\\StarCitizen\\LIVE",
                        enabled = !state.running,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        "Durchsuchen…",
                        enabled = !state.running,
                        modifier = Modifier.height(56.dp),
                        onClick = { pickFolder(state.channelFolder)?.let { state.channelFolder = it } },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Liest die Game.log in diesem Ordner und alle Logs im Unterordner „logbackups\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.Gray2,
                )
            }

            // --- Output file ---
            Column {
                FieldLabel("Ausgabe-JSON (Ziel)")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KrtTextField(
                        value = state.outputFile,
                        onValueChange = { state.outputFile = it },
                        placeholder = "z. B. …\\Dokumente\\blueprints.json",
                        enabled = !state.running,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        "Durchsuchen…",
                        enabled = !state.running,
                        modifier = Modifier.height(56.dp),
                        onClick = { pickSaveFile(state.outputFile)?.let { state.outputFile = it } },
                    )
                }
            }

            // --- Primary action ---
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CtaButton(
                    "Blueprints extrahieren",
                    enabled = !state.running && state.channelFolder.isNotBlank() && state.outputFile.isNotBlank(),
                    onClick = { runExtraction(scope, state) },
                )
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Krt.Orange, strokeWidth = 2.dp)
                }
            }

            // --- Status line ---
            val dotColor = when {
                state.isError -> Krt.Danger
                state.running -> Krt.Orange
                state.resultSummary.isNotBlank() -> Krt.Success
                else -> Krt.Gray2
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(dotColor)
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.isError) Krt.Danger else Krt.Gray1,
                )
            }

            // --- Result panel (HUD box) ---
            if (state.resultSummary.isNotBlank()) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).hudBox()) {
                    Text(
                        "Ergebnis".uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Krt.Orange,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        Text(state.resultSummary, style = KrtDataStyle, color = Krt.Gray1)
                    }
                }
            }
        }
    }
}

private fun runExtraction(
    scope: CoroutineScope,
    state: AppState,
) {
    val folder = File(state.channelFolder.trim())
    val output = File(state.outputFile.trim())

    if (!folder.isDirectory) {
        state.isError = true
        state.status = "Der Channel-Ordner existiert nicht: ${folder.absolutePath}"
        return
    }

    state.running = true
    state.isError = false
    state.resultSummary = ""
    state.status = "Suche Log-Dateien…"

    scope.launch {
        try {
            val export = withContext(Dispatchers.IO) {
                BlueprintExtractor.extract(folder) { done, total, current ->
                    val label = if (current.isBlank()) "Werte aus…" else current
                    scope.launch { state.status = "Verarbeite Datei $done/$total: $label" }
                }
            }

            if (export.logFilesScanned == 0) {
                state.isError = true
                state.status = "Keine Game.log und kein „logbackups\"-Ordner im Channel-Ordner gefunden."
                state.running = false
                return@launch
            }

            withContext(Dispatchers.IO) { BlueprintExtractor.writeJson(export, output) }

            state.isError = false
            state.status = "Fertig: ${export.blueprintCount} Blueprint(s) aus ${export.logFilesScanned} Datei(en) geschrieben nach ${output.absolutePath}"
            state.resultSummary = buildSummary(export)
        } catch (t: Throwable) {
            state.isError = true
            state.status = "Fehler: ${t.message ?: t::class.simpleName}"
        } finally {
            state.running = false
        }
    }
}

private fun buildSummary(export: com.basetool.bpextractor.model.BlueprintExport): String = buildString {
    appendLine("Spieler:")
    if (export.players.isEmpty()) {
        appendLine("  (keiner erkannt)")
    } else {
        export.players.forEach { p ->
            appendLine("  • ${p.handle} — ${p.blueprintCount} Blueprint(s)")
        }
    }
    appendLine()
    appendLine("Blueprints nach Kategorie:")
    export.blueprints.groupingBy { it.category }.eachCount()
        .toList().sortedByDescending { it.second }
        .forEach { (cat, n) -> appendLine("  • $cat: $n") }
    appendLine()
    appendLine("Letzte erhaltene Blueprints:")
    export.blueprints.takeLast(15).reversed().forEach {
        appendLine("  • ${it.receivedAt}  ${it.productName}  [${it.category}]")
    }
}

/**
 * Entry point. With no arguments it opens the GUI; with arguments it runs a
 * headless extraction so the tool can be scripted (mirrors the Python original's
 * dual live/import design).
 *
 * CLI usage: `<logFolder> <outputJson> [--recursive]`
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        runCli(args)
        return
    }
    guiMain()
}

private fun runCli(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    if (positional.size < 2) {
        System.err.println("Usage: basetool-bp-extractor <channelFolder> <outputJson>")
        System.err.println("  channelFolder: a Star Citizen channel dir, e.g. ...\\StarCitizen\\LIVE")
        System.err.println("  (reads its Game.log + every *.log in its logbackups subfolder)")
        kotlin.system.exitProcess(2)
    }
    val folder = File(positional[0])
    val output = File(positional[1])
    if (!folder.isDirectory) {
        System.err.println("Channel folder does not exist: ${folder.absolutePath}")
        kotlin.system.exitProcess(1)
    }
    println("${BlueprintExtractor.TOOL_NAME} v${BlueprintExtractor.TOOL_VERSION}")
    println("Scanning channel: ${folder.absolutePath}")
    val export = BlueprintExtractor.extract(folder) { done, total, current ->
        if (current.isNotBlank()) println("  [$done/$total] $current")
    }
    BlueprintExtractor.writeJson(export, output)
    println("Done. ${export.blueprintCount} blueprint(s) from ${export.logFilesScanned} file(s).")
    export.players.forEach { println("  Player ${it.handle}: ${it.blueprintCount} blueprint(s)") }
    println("Output: ${output.absolutePath}")
    println()
    println(Legal.UNAFFILIATED)
    println(Legal.TRADEMARK_NOTICE)
}

private fun guiMain() = application {
    val state = remember { AppState() }
    val windowState = rememberWindowState(width = 860.dp, height = 720.dp)
    val appIcon = remember { useResource("icons/krt-icon.png") { BitmapPainter(loadImageBitmap(it)) } }
    val communityLogo = remember { useResource("MadeByTheCommunity_Black.png") { BitmapPainter(loadImageBitmap(it)) } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Basetool Blueprint Extractor",
        icon = appIcon,
        undecorated = true,
        resizable = true,
        state = windowState,
    ) {
        val frame = this
        KrtTheme {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(Krt.Black).border(1.dp, Krt.Gray3)) {
                    frame.KrtTitleBar(windowState, appIcon, "Basetool Blueprint Extractor", ::exitApplication)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ExtractorScreen(state)
                    }
                    CommunityDisclaimerFooter(communityLogo)
                }
                ResizeCorner(windowState)
            }
        }
    }
}
