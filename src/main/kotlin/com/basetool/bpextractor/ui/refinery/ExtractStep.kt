package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.BlueprintExtractor
import com.basetool.bpextractor.refinery.PipelineStage
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.KrtProgressBar
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings

/**
 * §5.3 Extraktion on the [StepScaffold]: overall progress + measured per-image ETA, one row per
 * image with the Locate → Normalize → Read stage track (strictly one image active at a time) and
 * the orange-accent console pane filling the height side by side, the model chip in the head,
 * the per-image un-quoted ⚠ state, and the pinned cancel / "Weiter: Review" footer.
 */
@Composable
fun ExtractStep(state: RefineryUiState) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    // Auto-start once when the step is entered with no result yet.
    LaunchedEffect(Unit) {
        if (!state.running && state.result == null && state.extractError == null) {
            state.runExtraction(scope, BlueprintExtractor.TOOL_VERSION)
        }
    }

    val total = state.images.size
    val done = state.outcomes.size
    val remaining = ((total - done).coerceAtLeast(0)) * state.etaSecondsPerImage

    StepScaffold(
        overline = strings.rfStepOverline(3),
        title = strings.rfExtractTitle,
        scrollBody = false,
        headRight = { KrtChip(state.selectedModel, color = Krt.Orange, border = Krt.Orange) },
        footer = {
            // Cancel/back left, Review CTA right (enables on completion).
            if (state.running) {
                GhostButton(strings.rfCancel, onClick = { state.cancelRequested = true })
            } else {
                GhostButton(strings.back, onClick = { state.goTo(1) })
            }
            Spacer(Modifier.weight(1f))
            CtaButton(
                strings.rfCtaToReview,
                enabled = !state.running && state.result != null,
                onClick = { state.goTo(3) },
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                strings.rfImageOf(done.coerceAtMost(total), total),
                style = MaterialTheme.typography.bodyMedium,
                color = Krt.Gray1,
            )
            if (state.running) {
                Text(
                    strings.rfEtaRemaining(remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.Gray2,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        KrtProgressBar(done = done, total = total)
        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left: per-image stage tracks (scrolls when an order has many captures).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .hudBox()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.images.forEachIndexed { index, image ->
                    ImageStageRow(state, index, image)
                }
            }
            // Right: console pane (orange accent).
            Column(modifier = Modifier.weight(1f).fillMaxHeight().hudBox(bracket = Krt.Orange).padding(12.dp)) {
                Text(
                    strings.rfConsoleTitle.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                )
                Spacer(Modifier.padding(top = 6.dp))
                val listState = rememberLazyListState()
                LaunchedEffect(state.console.size) {
                    if (state.console.isNotEmpty()) listState.animateScrollToItem(state.console.size - 1)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.console) { line ->
                        Text(line, style = KrtDataStyle, color = Krt.Gray1)
                    }
                }
            }
        }

        // Un-quoted warning (§5.3): any GET-QUOTE capture earns the amber alert.
        val unquotedCount = state.outcomes.values.count { it.quoted == false }
        if (unquotedCount > 0) {
            Spacer(Modifier.height(12.dp))
            AlertBox(Krt.Warning) {
                Text(strings.rfUnquotedWarning, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                if (!state.running && unquotedCount == state.outcomes.size && state.outcomes.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(strings.rfAllUnquotedNotice, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                }
            }
        }

        val error = state.extractError
        if (error != null && error != RefineryUiState.CANCELLED_MARKER) {
            Spacer(Modifier.height(12.dp))
            AlertBox(Krt.Danger) {
                Text(strings.rfExtractionFailed(error), style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
            }
        }
        if (error == RefineryUiState.CANCELLED_MARKER) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(Krt.Gray2)
                Text(strings.rfCancelled, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
            }
        }
    }
}

/** One per-image row: status dot, name, the three-stage track, the un-quoted ⚠ tag. */
@Composable
private fun ImageStageRow(state: RefineryUiState, index: Int, image: RefineryImage) {
    val strings = LocalStrings.current
    val outcome = state.outcomes[index]
    val reached = state.stageReached[index]
    val active = state.running && state.currentIndex == index && outcome == null
    val dot = when {
        outcome?.quoted == false -> Krt.Warning
        outcome != null && outcome.quoted == null -> Krt.Danger
        outcome != null -> Krt.Success
        active -> Krt.Orange
        else -> Krt.Gray2
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(dot)
            Text(image.file.name, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1, maxLines = 1)
            if (outcome?.quoted == false) {
                Text("⚠", style = MaterialTheme.typography.bodySmall, color = Krt.Warning)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp),
        ) {
            PipelineStage.entries.forEach { stage ->
                val passed = outcome != null || (reached != null && reached.ordinal > stage.ordinal)
                val current = active && reached == stage
                val color = when {
                    current -> Krt.Orange
                    passed -> Krt.Success
                    else -> Krt.Gray2
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusDot(color)
                    Text(
                        stage.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                }
                if (stage != PipelineStage.entries.last()) {
                    Box(Modifier.width(14.dp).height(1.dp).background(Krt.Gray3))
                }
            }
        }
    }
}
