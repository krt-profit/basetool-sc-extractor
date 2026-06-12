package com.basetool.bpextractor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.BlueprintExtractor
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.update.UpdateInfo

/**
 * Lifecycle of the start-screen update offer. [Hidden] covers "not checked yet", "up to date" and
 * every silent check failure alike — the banner only ever appears with something actionable.
 * There is deliberately no persisted "skip this version": the app is stateless on disk (CLAUDE.md
 * guardrail 2), so [Hidden] after a dismiss lasts for the session.
 */
sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val bytesDone: Long, val bytesTotal: Long) : UpdateUiState
    data class Installing(val info: UpdateInfo) : UpdateUiState
    data class Failed(val info: UpdateInfo, val message: String) : UpdateUiState
}

/** Whole megabytes for the banner — integer math, so no locale-dependent decimal separator. */
private fun megabytes(bytes: Long): String = ((bytes + 512 * 1024) / (1024 * 1024)).toString()

/**
 * The update offer on the start screen (the launcher is the one place every session passes
 * through). Install is the screen's single filled CTA; dismissing ("Später") hides the offer for
 * this session. While downloading, the bar follows bytes; a failure keeps the banner with a retry
 * so a flaky connection never strands the user.
 */
@Composable
fun UpdateBanner(
    state: UpdateUiState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is UpdateUiState.Hidden) return
    val strings = LocalStrings.current
    val accent = if (state is UpdateUiState.Failed) Krt.Danger else Krt.Orange
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hudBox(fill = Krt.Gray4.copy(alpha = 0.5f), hairline = accent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        when (state) {
            is UpdateUiState.Hidden -> {}

            is UpdateUiState.Available -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(Krt.Orange)
                Column(Modifier.weight(1f)) {
                    Text(strings.updTitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Orange)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        strings.updBody(state.info.version, BlueprintExtractor.TOOL_VERSION),
                        style = MaterialTheme.typography.bodySmall,
                        color = Krt.Gray1,
                    )
                }
                if (state.info.msiSizeBytes > 0) {
                    Text(strings.updSize(megabytes(state.info.msiSizeBytes)), style = KrtDataStyle, color = Krt.Gray2)
                }
                GhostButton(strings.updLater, onClick = onDismiss)
                CtaButton(strings.updInstall, onClick = onInstall)
            }

            is UpdateUiState.Downloading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(Krt.Orange)
                Column(Modifier.weight(1f)) {
                    Text(strings.updDownloading.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Orange)
                    Spacer(Modifier.height(6.dp))
                    if (state.bytesTotal > 0) {
                        KrtProgressBar(fraction = (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f))
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Krt.Orange, strokeWidth = 2.dp)
                    }
                }
                val done = megabytes(state.bytesDone)
                val label = if (state.bytesTotal > 0) "$done / ${megabytes(state.bytesTotal)} MB" else "$done MB"
                Text(label, style = KrtDataStyle, color = Krt.Gray1)
            }

            is UpdateUiState.Installing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Krt.Orange, strokeWidth = 2.dp)
                Text(strings.updInstalling, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
            }

            is UpdateUiState.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(Krt.Danger)
                Column(Modifier.weight(1f)) {
                    Text(strings.updTitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Danger)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        strings.updFailed(state.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = Krt.Gray1,
                        maxLines = 2,
                    )
                }
                GhostButton(strings.updLater, onClick = onDismiss)
                GhostButton(strings.updRetry, onClick = onInstall)
            }
        }
    }
}
