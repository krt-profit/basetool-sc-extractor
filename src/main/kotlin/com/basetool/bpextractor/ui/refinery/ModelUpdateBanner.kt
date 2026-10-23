package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.ButtonTone
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The start screen's offer of the refinery models whose registry tag moved, with the same update action
 * as the preflight; renders nothing while [RefineryUiState.updatableModels] is empty.
 */
@Composable
fun ModelUpdateBanner(
    state: RefineryUiState,
    appScope: CoroutineScope,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val updates = state.updatableModels
    if (updates.isEmpty()) return
    val strings = LocalStrings.current
    val task = state.modelTask
    val busy = task is ModelTask.Pulling || task is ModelTask.Removing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hudBox(hairline = Krt.Info, bracket = Krt.Info)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(Krt.Info)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.mupTitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.InfoText)
            updates.forEach { update -> UpdateRow(state, appScope, update, task, busy) }
        }
        GhostButton(strings.updLater, onClick = onDismiss)
    }
}

/** One outdated model: its line and update action, or the pull's progress while it downloads. */
@Composable
private fun UpdateRow(state: RefineryUiState, appScope: CoroutineScope, update: ModelUpdate, task: ModelTask?, busy: Boolean) {
    val strings = LocalStrings.current
    if (task is ModelTask.Pulling && task.model == update.model) {
        PullProgress(task.completed, task.total, task.status, model = update.model)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                strings.mupBody(update.model, update.sizeBytes?.let(::modelSize) ?: "?"),
                style = MaterialTheme.typography.bodySmall,
                color = Krt.Gray1,
            )
            if (task is ModelTask.Failed && !task.removal && task.model == update.model) {
                Spacer(Modifier.height(3.dp))
                Text(
                    strings.rfPullFailed(task.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.DangerText,
                    maxLines = 2,
                )
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
