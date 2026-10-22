package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.refinery.HardwareTier
import com.basetool.bpextractor.refinery.Preflight
import com.basetool.bpextractor.ui.ButtonTone
import com.basetool.bpextractor.ui.Caption
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FootNote
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtCheckbox
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.KrtProgressBar
import com.basetool.bpextractor.ui.KrtTextField
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The Vorprüfung & Setup step: a scrolling body with the Ollama runtime and hardware cards and the SC
 * warning banner, and the CTA in the pinned footer so it is never pushed off-screen.
 */
@Composable
fun PreflightStep(state: RefineryUiState, appScope: CoroutineScope) {
    val strings = LocalStrings.current
    var helpOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state.runPreflight(appScope) }

    Box(Modifier.fillMaxSize()) {
        StepScaffold(
            overline = strings.rfStepOverline(1),
            title = strings.rfPreflightTitle,
            subtitle = strings.rfPreflightSubtitle,
            scrollBody = true,
            headRight = { GhostButton(strings.help, onClick = { helpOpen = true }) },
            footer = {
                FootNote(strings.rfThrottleNote)
                Spacer(Modifier.weight(1f))
                CtaButton(
                    strings.rfCtaToImages,
                    enabled = state.preflightReady,
                    onClick = { state.goTo(1) },
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OllamaCard(state, appScope, Modifier.weight(1f))
                HardwareCard(state, appScope, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            ScBanner(state)
        }

        if (helpOpen) {
            HelpOverlay(onClose = { helpOpen = false })
        }
    }
}

@Composable
private fun OllamaCard(state: RefineryUiState, appScope: CoroutineScope, modifier: Modifier) {
    val strings = LocalStrings.current
    val status = state.ollamaStatus
    val dot = when (status) {
        OllamaStatus.Checking -> Krt.ControlBorder
        OllamaStatus.Ready -> Krt.Success
        is OllamaStatus.ModelMissing, is OllamaStatus.Pulling -> Krt.Warning
        is OllamaStatus.PullFailed, is OllamaStatus.Unreachable -> Krt.Danger
    }
    PanelCard(strings.rfOllamaCardTitle, dot, modifier) {
        Caption(strings.rfEndpointLabel)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KrtTextField(
                value = state.endpoint,
                onValueChange = { state.endpoint = it },
                placeholder = "http://localhost:11434",
                enabled = status !is OllamaStatus.Pulling,
                modifier = Modifier.weight(1f),
            )
        }
        KeyValueRow(strings.rfModelLabel, state.selectedModel)

        when (status) {
            OllamaStatus.Checking -> {
                Text(strings.rfChecking, style = MaterialTheme.typography.bodyMedium, color = Krt.TextMuted)
            }
            OllamaStatus.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(Krt.Success)
                    Text(strings.rfOllamaReady, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }
            }
            is OllamaStatus.ModelMissing -> {
                AlertBox(Krt.Warning) {
                    Text(
                        strings.rfOllamaModelMissing(status.model),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ollama pull ${status.model}",
                        style = KrtDataStyle,
                        color = Krt.White,
                    )
                }
                GhostButton(strings.rfPullCta, onClick = { state.pullModel(appScope) }, tone = ButtonTone.OUTLINE)
            }
            is OllamaStatus.Pulling -> PullProgress(status.completed, status.total, status.status)
            is OllamaStatus.PullFailed -> {
                AlertBox(Krt.Danger) {
                    Text(strings.rfPullFailed(status.message), style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }
                GhostButton(strings.rfRetry, onClick = { state.recheckOllama(appScope) })
            }
            is OllamaStatus.Unreachable -> {
                AlertBox(Krt.Danger) {
                    Text(strings.rfOllamaUnreachable, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.rfInstallHint1, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
                    Text(strings.rfInstallHint2, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
                }
                GhostButton(strings.rfRetry, onClick = { state.recheckOllama(appScope) })
            }
        }
        ModelHousekeeping(state, appScope)
    }
}

/**
 * The second-check row and the model housekeeping under the Ollama status: the verify partner's
 * optional download, updates of models whose registry tag moved, and the removal of installed extractor
 * models this PC's tier never uses, each removal behind an inline confirmation.
 */
@Composable
private fun ModelHousekeeping(state: RefineryUiState, appScope: CoroutineScope) {
    val strings = LocalStrings.current
    val task = state.modelTask
    val busy = task is ModelTask.Pulling || task is ModelTask.Removing
    var confirming by remember { mutableStateOf<String?>(null) }

    if (state.decision?.tier == HardwareTier.RECOMMENDED && state.ollamaStatus == OllamaStatus.Ready) {
        KeyValueRow(strings.rfVerifyLabel, state.verifyModel ?: strings.rfVerifyOff)
    }

    val offer = state.verifyOffer
    if (offer != null) {
        if (task is ModelTask.Pulling && task.model == offer) {
            PullProgress(task.completed, task.total, task.status, model = offer)
        } else {
            AlertBox(Krt.Info) {
                Text(
                    strings.rfVerifyOffer(offer, modelSize(Preflight.MODEL_VERIFY_DOWNLOAD_BYTES)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Krt.Gray1,
                )
                if (task is ModelTask.Failed && !task.removal && task.model == offer) {
                    Spacer(Modifier.height(4.dp))
                    Text(strings.rfPullFailed(task.message), style = MaterialTheme.typography.bodySmall, color = Krt.DangerText)
                }
            }
            GhostButton(
                strings.rfVerifyPullCta,
                onClick = { state.pullVerifyModel(appScope) },
                enabled = !busy,
                tone = ButtonTone.OUTLINE,
            )
        }
    }

    state.updatableModels.forEach { update ->
        if (task is ModelTask.Pulling && task.model == update.model) {
            PullProgress(task.completed, task.total, task.status, model = update.model)
        } else {
            AlertBox(Krt.Info) {
                Text(
                    strings.rfModelUpdate(update.model, update.sizeBytes?.let(::modelSize) ?: "?"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Krt.Gray1,
                )
                if (task is ModelTask.Failed && !task.removal && task.model == update.model) {
                    Spacer(Modifier.height(4.dp))
                    Text(strings.rfPullFailed(task.message), style = MaterialTheme.typography.bodySmall, color = Krt.DangerText)
                }
            }
            GhostButton(
                strings.rfUpdateCta,
                onClick = { state.updateModel(appScope, update.model) },
                enabled = !busy,
                tone = ButtonTone.OUTLINE,
            )
        }
    }

    state.removableModels.forEach { model ->
        val size = model.sizeBytes?.let(::modelSize) ?: "?"
        AlertBox(Krt.ControlBorder) {
            Text(strings.rfModelUnneeded(model.name, size), style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
            when {
                task is ModelTask.Removing && task.model == model.name -> {
                    Spacer(Modifier.height(4.dp))
                    Text(strings.rfRemoving(model.name), style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
                }
                task is ModelTask.Failed && task.removal && task.model == model.name -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.rfRemoveFailed(model.name, task.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = Krt.DangerText,
                    )
                }
                confirming == model.name -> {
                    Spacer(Modifier.height(4.dp))
                    Text(strings.rfRemoveConfirm(model.name), style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
                }
            }
        }
        if (confirming == model.name) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    strings.rfRemoveConfirmCta,
                    onClick = {
                        confirming = null
                        state.removeModel(appScope, model.name)
                    },
                    enabled = !busy,
                    tone = ButtonTone.QUIET_DANGER,
                )
                GhostButton(strings.rfCancel, onClick = { confirming = null })
            }
        } else {
            GhostButton(
                strings.rfRemoveCta,
                onClick = { confirming = model.name },
                enabled = !busy,
                tone = ButtonTone.QUIET_DANGER,
            )
        }
    }
}

/** A model's size the way Ollama lists it, in decimal gigabytes. */
private fun modelSize(bytes: Long): String = String.format("%.1f GB", bytes / 1e9)

/**
 * Download progress of a pull: bytes and a bar when the registry reports them, else its status line;
 * [model] names the model when it is not the run's own.
 */
@Composable
private fun PullProgress(completed: Long?, total: Long?, status: String, model: String? = null) {
    val strings = LocalStrings.current
    val amount = if (completed != null && total != null && total > 0) "${completed / MIB} / ${total / MIB} MB" else status
    val label = if (model != null) "$model · $amount" else amount
    Text(strings.rfPullProgress(label), style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
    if (completed != null && total != null && total > 0) {
        KrtProgressBar(done = (completed / MIB).toInt(), total = (total / MIB).toInt())
    }
}

@Composable
private fun HardwareCard(state: RefineryUiState, appScope: CoroutineScope, modifier: Modifier) {
    val strings = LocalStrings.current
    val hardware = state.hardware
    val decision = state.decision
    val dot = when (decision?.tier) {
        null -> Krt.ControlBorder
        HardwareTier.RECOMMENDED -> Krt.Success
        HardwareTier.MINIMUM -> Krt.Warning
        HardwareTier.CPU -> Krt.Warning
    }
    PanelCard(strings.rfHardwareCardTitle, dot, modifier) {
        KeyValueRow(strings.rfGpuRow, gpuLabel(hardware?.gpu, strings.rfUnknown))
        KeyValueRow(strings.rfVramRow, bytesToGb(hardware?.gpu?.vramBytes) ?: strings.rfUnknown)
        KeyValueRow(strings.rfRamRow, bytesToGb(hardware?.totalRamBytes) ?: strings.rfUnknown)

        KrtChip(strings.rfAutoModelChip(state.selectedModel), ChipTone.PRIMARY)

        TierBar(
            labels = listOf(strings.rfTierCpu, strings.rfTierMin, strings.rfTierRecommended),
            activeIndex = when (decision?.tier) {
                HardwareTier.RECOMMENDED -> 2
                HardwareTier.MINIMUM -> 1
                HardwareTier.CPU -> 0
                null -> -1
            },
        )

        when (decision?.tier) {
            HardwareTier.RECOMMENDED -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(Krt.Success)
                    Text(strings.rfTierAboveRecommended, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }
            }
            HardwareTier.MINIMUM, HardwareTier.CPU -> {
                AlertBox(Krt.Warning) {
                    Text(
                        if (decision.tier == HardwareTier.MINIMUM) strings.rfTierMinimumInfo else strings.rfTierBelowMinimum,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                    Spacer(Modifier.height(8.dp))
                    KrtRadio(
                        selected = state.fallback == FallbackChoice.LOW_VRAM,
                        label = strings.rfFallbackLowVram(Preflight.MODEL_MINIMUM),
                        onSelect = { state.fallback = FallbackChoice.LOW_VRAM },
                    )
                    Spacer(Modifier.height(6.dp))
                    KrtRadio(
                        selected = state.fallback == FallbackChoice.CPU,
                        label = strings.rfFallbackCpu(
                            Preflight.etaSecondsPerImage(Preflight.MODEL_MINIMUM, gpuMode = false),
                        ),
                        onSelect = { state.fallback = FallbackChoice.CPU },
                    )
                }
            }
            null -> {
                Text(strings.rfChecking, style = MaterialTheme.typography.bodyMedium, color = Krt.TextMuted)
            }
        }

        Text(
            strings.rfEtaPerImage(state.etaSecondsPerImage),
            style = MaterialTheme.typography.bodySmall,
            color = Krt.TextMuted,
        )
        GhostButton(strings.rfRetry, onClick = { state.runPreflight(appScope, force = true) })
    }
}

/** The SC-running soft warning (non-blocking + acknowledge) or the calm green all-clear. */
@Composable
private fun ScBanner(state: RefineryUiState) {
    val strings = LocalStrings.current
    val hardware = state.hardware ?: return
    if (hardware.scRunning) {
        AlertBox(Krt.Warning) {
            Text(strings.rfScRunningWarning, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
            Spacer(Modifier.height(8.dp))
            KrtCheckbox(
                checked = state.scAcknowledged,
                onCheckedChange = { state.scAcknowledged = it },
                label = strings.rfScAcknowledge,
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(Krt.Success)
            Text(strings.rfScNotRunning, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
        }
    }
}

private const val MIB = 1024L * 1024
