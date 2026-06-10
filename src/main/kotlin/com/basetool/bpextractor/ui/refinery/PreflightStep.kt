package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FootNote
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtCheckbox
import com.basetool.bpextractor.ui.KrtProgressBar
import com.basetool.bpextractor.ui.KrtTextField
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope

/**
 * §5.1 Vorprüfung & Setup on the [StepScaffold] (`REDESIGN_IMPLEMENTATION.md` §4.3 — the most
 * important fix of the redesign): the body SCROLLS and the CTA lives in the pinned footer, so
 * the worst case (minimum tier with two fallback radios + model-missing alert + SC-running
 * banner) can never push "Weiter: Bilder laden" off-screen. The head carries the help button
 * opening the KRT help page (§4.3a); the cards are the Ollama runtime and hardware panels, with
 * the SC soft-warning banner below.
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
        OllamaStatus.Checking -> Krt.Gray2
        OllamaStatus.Ready -> Krt.Success
        is OllamaStatus.ModelMissing, is OllamaStatus.Pulling -> Krt.Warning
        is OllamaStatus.PullFailed, is OllamaStatus.Unreachable -> Krt.Danger
    }
    PanelCard(strings.rfOllamaCardTitle, dot, modifier) {
        // Endpoint is configurable (master plan: endpoint + model name configurable).
        Text(strings.rfEndpointLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Gray2)
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
                Text(strings.rfChecking, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray2)
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
                        style = MaterialTheme.typography.bodySmall,
                        color = Krt.Gray2,
                    )
                }
                CtaButton(strings.rfPullCta, onClick = { state.pullModel(appScope) })
            }
            is OllamaStatus.Pulling -> {
                val completed = status.completed
                val total = status.total
                val label = if (completed != null && total != null && total > 0) {
                    "${completed / MIB} / ${total / MIB} MB"
                } else {
                    status.status
                }
                Text(strings.rfPullProgress(label), style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                if (completed != null && total != null && total > 0) {
                    KrtProgressBar(done = (completed / MIB).toInt(), total = (total / MIB).toInt())
                }
            }
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
                    Text(strings.rfInstallHint1, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                    Text(strings.rfInstallHint2, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                }
                GhostButton(strings.rfRetry, onClick = { state.recheckOllama(appScope) })
            }
        }
    }
}

@Composable
private fun HardwareCard(state: RefineryUiState, appScope: CoroutineScope, modifier: Modifier) {
    val strings = LocalStrings.current
    val hardware = state.hardware
    val decision = state.decision
    val dot = when (decision?.tier) {
        null -> Krt.Gray2
        HardwareTier.RECOMMENDED -> Krt.Success
        HardwareTier.MINIMUM -> Krt.Warning
        HardwareTier.CPU -> Krt.Warning
    }
    PanelCard(strings.rfHardwareCardTitle, dot, modifier) {
        KeyValueRow(strings.rfGpuRow, gpuLabel(hardware?.gpu, strings.rfUnknown))
        KeyValueRow(strings.rfVramRow, bytesToGb(hardware?.gpu?.vramBytes) ?: strings.rfUnknown)
        KeyValueRow(strings.rfRamRow, bytesToGb(hardware?.totalRamBytes) ?: strings.rfUnknown)

        KrtChip(strings.rfAutoModelChip(state.selectedModel), color = Krt.Orange, border = Krt.Orange)

        TierBar(
            labels = listOf(strings.rfTierCpu, strings.rfTierMin, strings.rfTierRecommended),
            activeIndex = when (decision?.tier) {
                HardwareTier.RECOMMENDED -> 2
                HardwareTier.MINIMUM -> 1
                else -> 0
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
                Text(strings.rfChecking, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray2)
            }
        }

        Text(
            strings.rfEtaPerImage(state.etaSecondsPerImage),
            style = MaterialTheme.typography.bodySmall,
            color = Krt.Gray2,
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
