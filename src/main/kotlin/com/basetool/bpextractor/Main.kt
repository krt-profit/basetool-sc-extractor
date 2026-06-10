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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.basetool.bpextractor.ui.CommandStrip
import com.basetool.bpextractor.ui.CommunityDisclaimerFooter
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FieldLabel
import com.basetool.bpextractor.ui.FilePickerDialog
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.GreetingHeader
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.KrtProgressBar
import com.basetool.bpextractor.ui.KrtTextField
import com.basetool.bpextractor.ui.KrtTheme
import com.basetool.bpextractor.ui.KrtTitleBar
import com.basetool.bpextractor.ui.KrtToast
import com.basetool.bpextractor.ui.LanguageToggle
import com.basetool.bpextractor.ui.MainTab
import com.basetool.bpextractor.ui.PickerMode
import com.basetool.bpextractor.ui.PickerRequest
import com.basetool.bpextractor.ui.RefineryScreen
import com.basetool.bpextractor.ui.ResizeCorner
import com.basetool.bpextractor.ui.StartScreen
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.Lang
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.Strings
import com.basetool.bpextractor.ui.i18n.stringsFor
import com.basetool.bpextractor.ui.refinery.RefineryUiState
import com.basetool.bpextractor.ui.rememberHoneycombPainter
import com.basetool.bpextractor.ui.tiled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/** A transient completion notification (success or failure), shown as a toast. */
private data class ToastInfo(val title: String, val message: String, val error: Boolean)

/** UI state for the blueprint workflow. */
private class AppState {
    var channelFolder by mutableStateOf(defaultChannelFolder())
    var outputFile by mutableStateOf(defaultOutputPath())
    var running by mutableStateOf(false)
    var progressDone by mutableStateOf(0)
    var progressTotal by mutableStateOf(0)

    /**
     * Live status line. Blank means "initial" — the screen then renders the localized initial
     * hint from the active string catalogue, so the pre-first-action text follows the DE/EN
     * toggle. Event-driven statuses are localized at event time.
     */
    var status by mutableStateOf("")
    var resultSummary by mutableStateOf("")
    var resultFile by mutableStateOf<File?>(null)
    var isError by mutableStateOf(false)
    var channelError by mutableStateOf<String?>(null)
    var outputError by mutableStateOf<String?>(null)
    var toast by mutableStateOf<ToastInfo?>(null)

    /** When non-null, the KRT file/folder picker overlay is shown for this request. */
    var picker by mutableStateOf<PickerRequest?>(null)

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

/**
 * The state-driven blueprint step (design spec §4): Konfiguration while idle, the transient
 * Extraktion while running, Zusammenfassung once a result exists. Errors fall back to
 * Konfiguration with the status line carrying the diagnosis. Shared by the [CommandStrip]
 * inline stepper and the screen body so the two can never disagree.
 */
private fun blueprintStep(state: AppState): Int = when {
    state.running -> 1
    state.resultSummary.isNotBlank() -> 2
    else -> 0
}

/** A read-only validity hint for the channel-folder field, rendered under it in KRT style. */
private data class FolderHint(val dot: Color, val text: String, val textColor: Color)

/**
 * Compute a live hint for the typed channel folder. Pure local `File` stats (cheap, read-only) —
 * it never writes or browses, so it can't fail destructively; a bad path just yields a warning
 * hint, and the extraction re-validates the path itself before running.
 */
private fun channelFolderHint(path: String, strings: Strings): FolderHint {
    val p = path.trim()
    if (p.isEmpty()) {
        return FolderHint(Krt.Gray2, strings.bpHintReadsLogs, Krt.Gray2)
    }
    val dir = File(p)
    if (!dir.isDirectory) {
        return FolderHint(Krt.Danger, strings.bpHintFolderMissing, Krt.Danger)
    }
    val hasGameLog = File(dir, "Game.log").isFile
    val hasBackups = File(dir, "logbackups").isDirectory
    if (!hasGameLog && !hasBackups) {
        return FolderHint(Krt.Orange, strings.bpHintWrongFolder, Krt.Gray1)
    }
    val found = listOfNotNull(
        "Game.log".takeIf { hasGameLog },
        "logbackups".takeIf { hasBackups },
    ).joinToString(" + ")
    return FolderHint(Krt.Success, strings.bpHintValidFolder(found), Krt.Gray1)
}

@Composable
private fun ExtractorScreen(state: AppState) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val honeycomb = rememberHoneycombPainter()
    // Whether we can offer "open folder / open file" actions on this platform.
    val canOpenFiles = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }

    val bpStep = blueprintStep(state)

    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            GreetingHeader(
                title = strings.bpGreetingTitle,
                subtitle = strings.bpGreetingSubtitle,
            )

            if (bpStep == 0) {
            // --- Channel folder ---
            Column {
                FieldLabel(strings.bpLabelChannelFolder)
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KrtTextField(
                        value = state.channelFolder,
                        onValueChange = { state.channelFolder = it; state.channelError = null },
                        placeholder = strings.bpPlaceholderChannel,
                        enabled = !state.running,
                        isError = state.channelError != null,
                        supportingText = state.channelError,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        strings.browse,
                        enabled = !state.running,
                        modifier = Modifier.height(56.dp),
                        onClick = {
                            state.picker = PickerRequest(
                                mode = PickerMode.FOLDER,
                                title = strings.bpPickerChannelTitle,
                                confirmLabel = strings.bpPickerChannelConfirm,
                                initialPath = state.channelFolder,
                            ) { state.channelFolder = it; state.channelError = null }
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Live validity hint — suppressed while an on-click validation error is
                // shown for this field, so the red border + "⚠ …" line isn't duplicated.
                if (state.channelError == null) {
                    val channelHint = remember(state.channelFolder, strings) { channelFolderHint(state.channelFolder, strings) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(channelHint.dot)
                        Text(
                            channelHint.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = channelHint.textColor,
                        )
                    }
                    // When LIVE is picked and a sibling HOTFIX channel with logs sits beside it,
                    // tell the user it's swept in too (its blueprints would otherwise be missed).
                    val hasHotfixSibling = remember(state.channelFolder) {
                        BlueprintExtractor.siblingHotfixFolder(File(state.channelFolder.trim())) != null
                    }
                    if (hasHotfixSibling) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(Krt.Orange)
                            Text(
                                strings.bpHotfixNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = Krt.Gray1,
                            )
                        }
                    }
                }
            }

            // --- Output file ---
            Column {
                FieldLabel(strings.bpLabelOutputJson)
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KrtTextField(
                        value = state.outputFile,
                        onValueChange = { state.outputFile = it; state.outputError = null },
                        placeholder = strings.bpPlaceholderOutput,
                        enabled = !state.running,
                        isError = state.outputError != null,
                        supportingText = state.outputError,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        strings.browse,
                        enabled = !state.running,
                        modifier = Modifier.height(56.dp),
                        onClick = {
                            state.picker = PickerRequest(
                                mode = PickerMode.SAVE_FILE,
                                title = strings.bpPickerSaveTitle,
                                confirmLabel = strings.bpPickerSaveConfirm,
                                initialPath = state.outputFile,
                            ) { state.outputFile = it; state.outputError = null }
                        },
                    )
                }
            }

            // --- Primary action ---
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CtaButton(
                    strings.bpCta,
                    // Stays enabled (variant A): a click validates and marks the
                    // offending field rather than leaving the button greyed out.
                    enabled = !state.running,
                    onClick = { runExtraction(scope, state, strings) },
                )
            }
            } // end bpStep == 0 (Konfiguration)

            if (bpStep == 1) {
                // --- Extraktion (transient): streaming progress ---
                // Indeterminate fallback only for the brief "finding files" phase, before
                // the file count is known; once it is, the determinate bar takes over.
                if (state.progressTotal == 0) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Krt.Orange, strokeWidth = 2.dp)
                } else {
                    KrtProgressBar(done = state.progressDone, total = state.progressTotal)
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
                    state.status.ifBlank { strings.bpStatusInitial },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.isError) Krt.Danger else Krt.Gray1,
                )
            }

            // --- Result panel (HUD box) ---
            if (state.resultSummary.isNotBlank()) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).hudBox()) {
                    // Header: title + (after a successful write) jump-to-output actions.
                    // Kept outside the scroll area below so the actions stay visible.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            strings.bpResultTitle.uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Krt.Orange,
                        )
                        val file = state.resultFile
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (canOpenFiles && file != null) {
                                file.absoluteFile.parentFile?.let { parent ->
                                    GhostButton(strings.bpShowInFolder, onClick = { openWithDesktop(parent, scope, state, strings) })
                                }
                                GhostButton(strings.bpOpenJson, onClick = { openWithDesktop(file, scope, state, strings) })
                            }
                            // "Erneut": back to Konfiguration with the fields kept (design §4.3).
                            GhostButton(
                                strings.bpAgain,
                                onClick = {
                                    state.resultSummary = ""
                                    state.status = ""
                                    state.isError = false
                                },
                            )
                        }
                    }
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

        // Transient completion toast (auto-dismisses), overlaid bottom-right and
        // clear of the footer. The status line + result panel stay the persistent
        // record; this is just a glanceable confirmation, not a second source.
        state.toast?.let { t ->
            LaunchedEffect(t) {
                delay(4500)
                state.toast = null
            }
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                KrtToast(title = t.title, message = t.message, error = t.error)
            }
        }
    }
}

private fun runExtraction(
    scope: CoroutineScope,
    state: AppState,
    strings: Strings,
) {
    val folder = File(state.channelFolder.trim())
    val output = File(state.outputFile.trim())
    state.toast = null

    // Validate on click and mark the offending field(s) — the CTA itself stays
    // enabled, so the user is never left guessing why nothing happened.
    var valid = true
    when {
        state.channelFolder.isBlank() -> {
            state.channelError = strings.bpErrSelectChannel
            valid = false
        }
        !folder.isDirectory -> {
            state.channelError = strings.bpErrFolderNotFound(folder.absolutePath)
            valid = false
        }
        else -> state.channelError = null
    }
    if (state.outputFile.isBlank()) {
        state.outputError = strings.bpErrSelectOutput
        valid = false
    } else {
        state.outputError = null
    }
    if (!valid) {
        state.isError = true
        state.resultSummary = ""
        state.status = strings.bpStatusFixFields
        return
    }

    state.running = true
    state.isError = false
    state.resultSummary = ""
    state.progressDone = 0
    state.progressTotal = 0
    state.status = strings.bpStatusSearching

    scope.launch {
        try {
            val export = withContext(Dispatchers.IO) {
                BlueprintExtractor.extract(folder) { done, total, current ->
                    val label = if (current.isBlank()) strings.bpStatusEvaluating else current
                    scope.launch {
                        state.progressDone = done
                        state.progressTotal = total
                        state.status = strings.bpStatusProcessing(done, total, label)
                    }
                }
            }

            if (export.logFilesScanned == 0) {
                state.isError = true
                state.status = strings.bpStatusNoLogs
                state.toast = ToastInfo(strings.bpToastNoLogsTitle, strings.bpToastNoLogsBody, error = true)
                state.running = false
                return@launch
            }

            withContext(Dispatchers.IO) { BlueprintExtractor.writeJson(export, output) }

            state.isError = false
            state.status = strings.bpStatusDone(export.blueprintCount, export.logFilesScanned, output.absolutePath)
            state.resultFile = output
            state.resultSummary = buildSummary(export, strings)
            state.toast = ToastInfo(strings.bpToastDoneTitle, strings.bpToastDoneBody(export.blueprintCount), error = false)
        } catch (t: Throwable) {
            state.isError = true
            state.status = strings.bpStatusError(t.message ?: t::class.simpleName ?: strings.unknownError)
            state.toast = ToastInfo(strings.bpToastErrorTitle, t.message ?: t::class.simpleName ?: strings.unknownError, error = true)
        } finally {
            state.running = false
        }
    }
}

/**
 * Open [target] (the output file or its containing folder) in the OS default
 * handler. Runs off the UI thread; any failure is reported in the status line
 * rather than thrown. Callers only show the buttons when [Desktop] OPEN is
 * supported, but the try/catch still guards the headless/unsupported edge.
 */
private fun openWithDesktop(target: File, scope: CoroutineScope, state: AppState, strings: Strings) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { Desktop.getDesktop().open(target) }
        } catch (t: Throwable) {
            state.isError = true
            state.status = strings.cannotOpen(target.name, t.message ?: t::class.simpleName ?: strings.unknownError)
        }
    }
}

private fun buildSummary(export: com.basetool.bpextractor.model.BlueprintExport, strings: Strings): String = buildString {
    appendLine(strings.bpSummaryPlayers)
    if (export.players.isEmpty()) {
        appendLine("  ${strings.bpSummaryNoPlayer}")
    } else {
        export.players.forEach { p ->
            appendLine("  • ${p.handle} — ${p.blueprintCount} Blueprint(s)")
        }
    }
    appendLine()
    appendLine(strings.bpSummaryByCategory)
    export.blueprints.groupingBy { it.category }.eachCount()
        .toList().sortedByDescending { it.second }
        .forEach { (cat, n) -> appendLine("  • $cat: $n") }
    appendLine()
    appendLine(strings.bpSummaryRecent)
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
        System.err.println("Usage: basetool-sc-extractor <channelFolder> <outputJson>")
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
    BlueprintExtractor.siblingHotfixFolder(folder)?.let {
        println("HOTFIX channel found next to LIVE - also scanning ${it.absolutePath}")
    }
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
    val refinery = remember { RefineryUiState() }
    var lang by remember { mutableStateOf(Lang.DE) }
    var tab by remember { mutableStateOf(MainTab.START) }
    // Locked decision (REDESIGN_IMPLEMENTATION.md): default window 1180×820, resizable
    // down to 640×520 (the ResizeCorner enforces the floor).
    val windowState = rememberWindowState(width = 1180.dp, height = 820.dp)
    val appIcon = remember { useResource("icons/krt-icon.png") { BitmapPainter(loadImageBitmap(it)) } }
    val communityLogo = remember { useResource("MadeByTheCommunity_Black.png") { BitmapPainter(loadImageBitmap(it)) } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Basetool SC Extractor",
        icon = appIcon,
        undecorated = true,
        resizable = true,
        state = windowState,
    ) {
        val frame = this
        val strings = remember(lang) { stringsFor(lang) }
        KrtTheme {
            CompositionLocalProvider(LocalStrings provides strings) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().background(Krt.Black).border(1.dp, Krt.Gray3)) {
                        frame.KrtTitleBar(
                            windowState,
                            appIcon,
                            "Basetool SC Extractor",
                            ::exitApplication,
                            actions = { LanguageToggle(lang) { lang = it } },
                        )
                        // One navigation band: tabs + the active workflow's inline stepper
                        // (replaces the former TabBar + per-screen StepperBar stack).
                        val bpStep = blueprintStep(state)
                        CommandStrip(
                            tab = tab,
                            onTab = { tab = it },
                            stepLabels = when (tab) {
                                MainTab.BLUEPRINTS -> strings.bpSteps
                                MainTab.REFINERY -> strings.rfSteps
                                MainTab.START -> null
                            },
                            stepIndex = when (tab) {
                                MainTab.BLUEPRINTS -> bpStep
                                MainTab.REFINERY -> refinery.step
                                MainTab.START -> 0
                            },
                            maxReached = when (tab) {
                                // Blueprint steps are state-driven: only "back to Setup"
                                // (resetting the result) is a meaningful stepper jump.
                                MainTab.BLUEPRINTS -> if (state.running) -1 else 0
                                MainTab.REFINERY -> if (refinery.running) -1 else refinery.maxReached
                                MainTab.START -> 0
                            },
                            onStep = { target ->
                                when (tab) {
                                    MainTab.BLUEPRINTS -> if (target == 0 && !state.running) {
                                        state.resultSummary = ""
                                        state.status = ""
                                        state.isError = false
                                    }
                                    // Never jump backwards INTO a running extraction mid-run.
                                    MainTab.REFINERY -> if (!refinery.running) refinery.step = target
                                    MainTab.START -> {}
                                }
                            },
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                MainTab.START -> StartScreen(onOpen = { tab = it })
                                MainTab.BLUEPRINTS -> ExtractorScreen(state)
                                MainTab.REFINERY -> RefineryScreen(refinery, onPicker = { state.picker = it })
                            }
                        }
                        CommunityDisclaimerFooter(communityLogo)
                    }
                    ResizeCorner(windowState)
                    // KRT file/folder picker overlay (replaces the legacy OS dialogs). Full-window modal.
                    state.picker?.let { req ->
                        FilePickerDialog(
                            mode = req.mode,
                            title = req.title,
                            confirmLabel = req.confirmLabel,
                            initialPath = req.initialPath,
                            extension = req.extension,
                            onConfirm = { path -> req.onConfirm(path); state.picker = null },
                            onDismiss = { state.picker = null },
                        )
                    }
                }
            }
        }
    }
}
