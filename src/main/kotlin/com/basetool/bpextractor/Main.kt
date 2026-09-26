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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.resources.Res
import com.basetool.bpextractor.resources.basetool_extractor_icon
import com.basetool.bpextractor.resources.made_by_the_community_black
import com.basetool.bpextractor.ui.CommandStrip
import com.basetool.bpextractor.ui.CommunityDisclaimerFooter
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FieldLabel
import com.basetool.bpextractor.ui.FilePickerDialog
import com.basetool.bpextractor.ui.FootNote
import com.basetool.bpextractor.ui.GhostButton
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
import com.basetool.bpextractor.ui.SendController
import com.basetool.bpextractor.ui.SendKind
import com.basetool.bpextractor.ui.SendOverlay
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.UpdateUiState
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.refinery.AlertBox
import com.basetool.bpextractor.ui.refinery.KrtChip
import com.basetool.bpextractor.ui.i18n.Lang
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.Strings
import com.basetool.bpextractor.ui.i18n.StringsEn
import com.basetool.bpextractor.ui.i18n.stringsFor
import com.basetool.bpextractor.ui.refinery.RefineryUiState
import com.basetool.bpextractor.ui.rememberHoneycombPainter
import com.basetool.bpextractor.ui.tiled
import com.basetool.bpextractor.update.UpdateChecker
import com.basetool.bpextractor.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
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

    /** Byte progress across all files — drives the bar fill within one huge Game.log. */
    var bytesDone by mutableStateOf(0L)
    var bytesTotal by mutableStateOf(0L)

    /** Log files the last run skipped because they could not be read (locked/corrupt). */
    var skippedFiles by mutableStateOf<List<String>>(emptyList())

    /**
     * The localisation the last run detected for the picked install, surfaced only when a run found no
     * blueprints.
     */
    var localization by mutableStateOf(ScLocalization.Detected.NONE)

    /**
     * Live status line. Blank means "initial" — the screen then renders the localized initial
     * hint from the active string catalogue, so the pre-first-action text follows the DE/EN
     * toggle. Event-driven statuses are localized at event time.
     */
    var status by mutableStateOf("")
    var resultSummary by mutableStateOf("")
    var resultFile by mutableStateOf<File?>(null)

    /**
     * The last successful export, feeding the summary screen and the config screen's "last run" line.
     * Not cleared by "Erneut"; only a new run replaces it.
     */
    var resultExport by mutableStateOf<com.basetool.bpextractor.model.BlueprintExport?>(null)
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

        /**
         * Returns the folder of the last successful run, else the standard LIVE install path when it
         * exists. A remembered folder that no longer exists is dropped.
         */
        fun defaultChannelFolder(): String {
            val remembered = runCatching { AppConfigStore().load().lastChannelFolder }.getOrNull()
            if (!remembered.isNullOrBlank() && File(remembered).isDirectory) return remembered
            val standard = File("""C:\Program Files\Roberts Space Industries\StarCitizen\LIVE""")
            return if (standard.isDirectory) standard.absolutePath else ""
        }
    }
}

/**
 * Maps the state to the blueprint step: Konfiguration while idle or on error, Extraktion while
 * running, Zusammenfassung once a result exists. Shared by the [CommandStrip] stepper and the screen
 * body.
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
        val loose = dir.listFiles()?.count { it.isFile && it.extension.equals("log", ignoreCase = true) } ?: 0
        return if (loose > 0) {
            FolderHint(Krt.Success, strings.bpHintArchiveFolder(loose), Krt.Gray1)
        } else {
            FolderHint(Krt.Orange, strings.bpHintWrongFolder, Krt.Gray1)
        }
    }
    val found = listOfNotNull(
        "Game.log".takeIf { hasGameLog },
        "logbackups".takeIf { hasBackups },
    ).joinToString(" + ")
    return FolderHint(Krt.Success, strings.bpHintValidFolder(found), Krt.Gray1)
}

@Composable
private fun ExtractorScreen(state: AppState, appScope: CoroutineScope) {
    val honeycomb = rememberHoneycombPainter()
    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        when (blueprintStep(state)) {
            0 -> BpConfigStep(state, appScope)
            1 -> BpRunningStep(state)
            else -> BpSummaryStep(state)
        }

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

/**
 * Blueprint step 1, Konfiguration: the path form beside a context panel, with the start button in
 * the footer. [appScope] is the window-root scope, so the extraction outlives this composable.
 */
@Composable
private fun BpConfigStep(state: AppState, appScope: CoroutineScope) {
    val strings = LocalStrings.current
    StepScaffold(
        overline = strings.bpStepOverline(1),
        title = strings.bpSteps[0],
        subtitle = strings.bpConfigSubtitle,
        scrollBody = false,
        footer = {
            if (state.isError && state.status.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(Krt.Danger)
                    Text(state.status, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
                }
            } else {
                FootNote(strings.bpFootReadOnly)
            }
            Spacer(Modifier.weight(1f))
            CtaButton(
                strings.bpCta,
                enabled = !state.running,
                onClick = { runExtraction(appScope, state, strings) },
            )
        },
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1.25f).fillMaxHeight().hudBox().padding(16.dp)) {
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

                Spacer(Modifier.height(14.dp))
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

                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(Krt.Gray2)
                    Text(strings.bpFootAnchored, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                }
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().hudBox(bracket = Krt.Gray3).padding(16.dp),
            ) {
                Text(strings.bpCtxTitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Gray2)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    strings.bpCtxItems.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            StatusDot(Krt.Orange)
                            Text(item, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
                Spacer(Modifier.height(10.dp))
                Text(strings.bpCtxLastRun.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Gray2)
                Spacer(Modifier.height(6.dp))
                val export = state.resultExport
                if (export == null) {
                    Text(strings.bpCtxNoRun, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            export.players.joinToString(" · ") { it.handle }.ifBlank { strings.bpSummaryNoPlayer },
                            style = MaterialTheme.typography.bodySmall,
                            color = Krt.Gray2,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            strings.bpSumSuccessDetail(export.blueprintCount, export.logFilesScanned),
                            style = KrtDataStyle,
                            color = Krt.Gray1,
                        )
                    }
                }
            }
        }
    }
}

/** Blueprint step 2 — the transient extraction screen: progress centred in the body. */
@Composable
private fun BpRunningStep(state: AppState) {
    val strings = LocalStrings.current
    StepScaffold(
        overline = strings.bpStepOverline(2),
        title = strings.bpRunningTitle,
        scrollBody = false,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().hudBox().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Krt.Orange)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.bpStatusSearching, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    Spacer(Modifier.weight(1f))
                    if (state.progressTotal > 0) {
                        Text("${state.progressDone} / ${state.progressTotal}", style = KrtDataStyle, color = Krt.Gray1)
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (state.progressTotal == 0) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Krt.Orange, strokeWidth = 2.dp)
                } else {
                    val fraction = if (state.bytesTotal > 0) {
                        state.bytesDone.toFloat() / state.bytesTotal
                    } else {
                        state.progressDone.toFloat() / state.progressTotal
                    }
                    KrtProgressBar(fraction = fraction)
                }
                Spacer(Modifier.height(12.dp))
                Text(state.status, style = KrtDataStyle, color = Krt.Gray2, maxLines = 2)
            }
        }
    }
}

/**
 * Blueprint step 3 — Zusammenfassung (`REDESIGN_IMPLEMENTATION.md` §4.2): success alert on top,
 * then a two-column body — category bars left, the filling "most recently received" table right.
 * Jump-to-output and "Erneut" live in the head; there is no CTA on this screen.
 */
@Composable
private fun BpSummaryStep(state: AppState) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val canOpenFiles = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }
    val export = state.resultExport
    val sendController = remember { SendController() }
    val langTag = if (strings === StringsEn) "en" else "de"
    val saveBlueprintJson = {
        val export = state.resultExport
        if (export != null) {
            state.picker =
                PickerRequest(
                    mode = PickerMode.SAVE_FILE,
                    title = strings.bpPickerSaveTitle,
                    confirmLabel = strings.bpPickerSaveConfirm,
                    initialPath = state.outputFile,
                ) { path ->
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { BlueprintExtractor.writeJson(export, File(path)) }
                        }
                            .onSuccess { state.resultFile = File(path) }
                            .onFailure { t ->
                                state.toast =
                                    ToastInfo(
                                        strings.bpToastErrorTitle,
                                        t.message ?: t::class.simpleName ?: strings.unknownError,
                                        error = true,
                                    )
                            }
                    }
                }
        }
    }
    Box(Modifier.fillMaxSize()) {
    StepScaffold(
        overline = strings.bpStepOverline(3),
        title = strings.bpSummaryTitle,
        scrollBody = false,
        footer = {
            GhostButton(strings.bpCtaExport, onClick = saveBlueprintJson)
            Spacer(Modifier.weight(1f))
            CtaButton(
                strings.send.button,
                onClick = {
                    val json = state.resultExport?.let { BlueprintExtractor.toJson(it) }
                    if (json != null) sendController.request(scope, SendKind.BLUEPRINT, json, langTag)
                },
            )
        },
        headRight = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val file = state.resultFile
                if (canOpenFiles && file != null) {
                    file.absoluteFile.parentFile?.let { parent ->
                        GhostButton(strings.bpShowInFolder, onClick = { openWithDesktop(parent, scope, state, strings) })
                    }
                    GhostButton(strings.bpOpenJson, onClick = { openWithDesktop(file, scope, state, strings) })
                }
                GhostButton(
                    strings.bpAgain,
                    onClick = {
                        state.resultSummary = ""
                        state.status = ""
                        state.isError = false
                    },
                )
            }
        },
    ) {
        AlertBox(Krt.Success) {
            Text(strings.bpSumSuccessTitle, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
            if (export != null) {
                Spacer(Modifier.height(4.dp))
                val savedPrefix = state.resultFile?.let { "${it.absolutePath} · " } ?: ""
                Text(
                    savedPrefix + "schemaVersion ${export.schemaVersion} · " +
                        strings.bpSumSuccessDetail(export.blueprintCount, export.logFilesScanned),
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.Gray2,
                )
            }
        }
        if (state.isError && state.status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(Krt.Danger)
                Text(state.status, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
            }
        }
        if (state.skippedFiles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(Krt.Orange)
                Text(
                    strings.bpSkippedNote(state.skippedFiles.size, state.skippedFiles.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.Gray1,
                    maxLines = 2,
                )
            }
        }
        if (export != null && export.blueprintCount == 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(Krt.Gray2)
                Column {
                    Text(strings.bpSumZeroTitle, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
                    Text(strings.bpSumZeroBody, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                    state.localization.activeLanguage?.let { lang ->
                        Text(
                            strings.bpSumZeroLanguage(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = Krt.Gray2,
                        )
                    }
                }
            }
        } else if (export != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(Krt.Gray2)
                Text(
                    strings.bpSumRetentionNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.Gray2,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (export != null) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.width(290.dp).fillMaxHeight().hudBox(bracket = Krt.Gray3).padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            strings.bpSummaryByCategory.uppercase().removeSuffix(":"),
                            style = MaterialTheme.typography.labelMedium,
                            color = Krt.Gray2,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${export.blueprintCount}", style = KrtDataStyle, color = Krt.White)
                    }
                    val categories = remember(export) {
                        export.blueprints.groupingBy { it.category }.eachCount()
                            .toList().sortedByDescending { it.second }
                    }
                    val maxCount = (categories.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        categories.forEach { (category, count) ->
                            Column(Modifier.padding(vertical = 5.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        category,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Krt.Gray1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("$count", style = KrtDataStyle, color = Krt.White)
                                }
                                Spacer(Modifier.height(3.dp))
                                Box(Modifier.fillMaxWidth().height(6.dp).background(Krt.SurfaceInput)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(count.toFloat() / maxCount)
                                            .height(6.dp)
                                            .background(Krt.Orange),
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        strings.bpSummaryPlayers.uppercase().removeSuffix(":"),
                        style = MaterialTheme.typography.labelMedium,
                        color = Krt.Gray2,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (export.players.isEmpty()) {
                        Text(strings.bpSummaryNoPlayer, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                    } else {
                        export.players.forEach { p ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(p.handle, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1, modifier = Modifier.weight(1f))
                                Text("${p.blueprintCount}", style = KrtDataStyle, color = Krt.Gray1)
                            }
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight().hudBox(bracket = Krt.Gray3)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Krt.SurfaceInput)
                            .drawBehind { drawLine(Krt.Orange, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            strings.bpSummaryRecent.uppercase().removeSuffix(":"),
                            style = MaterialTheme.typography.labelMedium,
                            color = Krt.Gray1,
                        )
                    }
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        export.blueprints.asReversed().forEach { bp ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawBehind { drawLine(Krt.Gray3, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    bp.productName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Krt.White,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                KrtChip(bp.category)
                                Text(
                                    bp.receivedAt.take(16).replace('T', ' '),
                                    style = KrtDataStyle,
                                    color = Krt.Gray2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
        SendOverlay(sendController, scope, onSaveLocally = saveBlueprintJson)
    }
}

private fun runExtraction(
    scope: CoroutineScope,
    state: AppState,
    strings: Strings,
) {
    val folder = File(state.channelFolder.trim())
    state.toast = null

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
    state.outputError = null
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
    state.bytesDone = 0L
    state.bytesTotal = 0L
    state.skippedFiles = emptyList()
    state.status = strings.bpStatusSearching

    scope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                BlueprintExtractor.extract(folder) { done, total, bytesDone, bytesTotal, current ->
                    val label = if (current.isBlank()) strings.bpStatusEvaluating else current
                    scope.launch {
                        state.progressDone = done
                        state.progressTotal = total
                        state.bytesDone = bytesDone
                        state.bytesTotal = bytesTotal
                        state.status = strings.bpStatusProcessing(done, total, label)
                    }
                }
            }
            val export = result.export
            state.skippedFiles = result.skippedFiles
            state.localization = result.localization

            if (export.logFilesScanned == 0) {
                state.isError = true
                if (result.skippedFiles.isEmpty()) {
                    state.status = strings.bpStatusNoLogs
                    state.toast = ToastInfo(strings.bpToastNoLogsTitle, strings.bpToastNoLogsBody, error = true)
                } else {
                    state.status = strings.bpStatusAllSkipped(result.skippedFiles.size)
                    state.toast = ToastInfo(strings.bpToastErrorTitle, strings.bpStatusAllSkipped(result.skippedFiles.size), error = true)
                }
                state.running = false
                return@launch
            }

            state.isError = false
            withContext(Dispatchers.IO) { rememberChannelFolder(folder.absolutePath) }
            state.status = ""
            state.resultFile = null
            state.resultExport = export
            state.resultSummary = strings.bpSumSuccessTitle
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
 * Persist [path] as the folder to pre-fill next start. Reads the config fresh immediately before
 * writing so this never clobbers a field another writer (the send flow's consent) changed in the
 * meantime, and swallows failures — a config write must never break an otherwise good run.
 *
 * Lives in the per-user data dir under `%APPDATA%`, never in the install dir (CLAUDE.md guardrail 2).
 */
private fun rememberChannelFolder(path: String) {
    runCatching {
        val store = AppConfigStore()
        store.save(store.load().copy(lastChannelFolder = path))
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

/** Entry point — Basetool SC Extractor is a GUI-only Compose Desktop app. */
fun main() = guiMain()

private fun guiMain() = application {
    val state = remember { AppState() }
    val refinery = remember { RefineryUiState() }
    var lang by remember { mutableStateOf(Lang.DE) }
    var tab by remember { mutableStateOf(MainTab.START) }
    val windowState = rememberWindowState(width = 1180.dp, height = 820.dp)
    val appIcon = painterResource(Res.drawable.basetool_extractor_icon)
    val communityLogo = painterResource(Res.drawable.made_by_the_community_black)
    val appScope = rememberCoroutineScope()

    var update by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Hidden) }
    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) {
            UpdateChecker.cleanupLeftovers()
            UpdateChecker.checkForUpdate(BlueprintExtractor.TOOL_VERSION)
        }
        if (found != null) {
            update = UpdateUiState.Available(found)
        }
    }

    fun installUpdate(info: UpdateInfo) {
        update = UpdateUiState.Downloading(info, 0L, info.msiSizeBytes)
        appScope.launch {
            try {
                val msi = withContext(Dispatchers.IO) {
                    UpdateChecker.downloadMsi(info) { done, total ->
                        appScope.launch { update = UpdateUiState.Downloading(info, done, total) }
                    }
                }
                update = UpdateUiState.Installing(info)
                withContext(Dispatchers.IO) {
                    UpdateChecker.launchInstaller(msi, info.msiSha256, if (lang == Lang.EN) "en" else "de")
                }
                exitApplication()
            } catch (t: Throwable) {
                update = UpdateUiState.Failed(info, t.message ?: t::class.simpleName ?: "I/O")
            }
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Basetool SC Extractor",
        icon = appIcon,
        undecorated = true,
        resizable = true,
        state = windowState,
        onPreviewKeyEvent = { event ->
            val isPaste = event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.V
            if (isPaste && tab == MainTab.REFINERY && refinery.step == 1 && state.picker == null) {
                refinery.pasteFromClipboard(appScope)
            } else {
                false
            }
        },
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
                                    MainTab.REFINERY -> if (!refinery.running) refinery.step = target
                                    MainTab.START -> {}
                                }
                            },
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                MainTab.START -> StartScreen(
                                    update = update,
                                    onUpdateInstall = {
                                        when (val u = update) {
                                            is UpdateUiState.Available -> installUpdate(u.info)
                                            is UpdateUiState.Failed -> installUpdate(u.info)
                                            else -> {}
                                        }
                                    },
                                    onUpdateDismiss = { update = UpdateUiState.Hidden },
                                    onOpen = { tab = it },
                                )
                                MainTab.BLUEPRINTS -> ExtractorScreen(state, appScope)
                                MainTab.REFINERY -> RefineryScreen(refinery, appScope, onPicker = { state.picker = it })
                            }
                        }
                        CommunityDisclaimerFooter(communityLogo)
                    }
                    ResizeCorner(windowState)
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
