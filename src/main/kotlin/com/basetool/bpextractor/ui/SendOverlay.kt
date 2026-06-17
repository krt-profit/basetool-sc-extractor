package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The "An Basetool senden" overlay (epic krt-iri/basetool#639): a KRT-styled scrim modal — never a
 * native dialog — that walks the user through consent → browser approval (device grant) → sending →
 * result, driven by [SendController.state]. Hidden when the state is [SendState.Idle].
 *
 * @param controller the send state machine + actions
 * @param appScope the UI scope the flow runs on
 */
@Composable
fun SendOverlay(controller: SendController, appScope: CoroutineScope) {
    val strings = LocalStrings.current
    val state = controller.state
    if (state is SendState.Idle) return

    val title =
        when (state) {
            is SendState.Authenticating -> strings.sendAuthTitle
            is SendState.Done -> strings.sendResultTitle
            else -> strings.sendConsentTitle
        }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(Krt.Black.copy(alpha = 0.8f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { controller.dismiss() },
                )
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val swallow = remember { MutableInteractionSource() }
        Column(
            modifier =
                Modifier.widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .drawBehind {
                        val grow = 28.dp.toPx()
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(Krt.Orange.copy(alpha = 0.18f), Color.Transparent),
                                    center = center,
                                    radius = size.maxDimension / 2f + grow,
                                ),
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + 2f * grow, size.height + 2f * grow),
                        )
                    }
                    .background(Krt.Black.copy(alpha = 0.97f))
                    .border(1.dp, Krt.Orange)
                    .clickable(interactionSource = swallow, indication = null, onClick = {}),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Krt.Gray4)
                        .drawBehind {
                            drawLine(
                                Krt.Orange,
                                Offset(0f, size.height),
                                Offset(size.width, size.height),
                                2.dp.toPx(),
                            )
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                )
            }

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is SendState.Consent ->
                        Text(
                            strings.sendConsentBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Authenticating -> {
                        Text(
                            strings.sendAuthBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                        Text(
                            strings.sendAuthCode(state.userCode),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Krt.White,
                        )
                        Text(
                            strings.sendWaiting,
                            style = MaterialTheme.typography.bodySmall,
                            color = Krt.Gray2,
                        )
                    }
                    is SendState.Sending ->
                        Text(
                            strings.sendInProgress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Done ->
                        Text(
                            strings.sendResultBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Error ->
                        Text(
                            strings.sendError(state.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Idle -> {}
                }
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Krt.Gray4.copy(alpha = 0.55f))
                        .drawBehind {
                            drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is SendState.Consent -> {
                        GhostButton(strings.cancel, onClick = { controller.dismiss() })
                        Spacer(Modifier.weight(1f))
                        CtaButton(
                            strings.sendConsentConfirm,
                            onClick = { controller.confirmConsent(appScope) },
                        )
                    }
                    is SendState.Authenticating -> {
                        GhostButton(
                            strings.sendAuthOpenBrowser,
                            onClick = { controller.reopenBrowser() },
                        )
                        Spacer(Modifier.weight(1f))
                        GhostButton(strings.cancel, onClick = { controller.dismiss() })
                    }
                    is SendState.Sending -> Spacer(Modifier.height(1.dp))
                    is SendState.Done -> {
                        GhostButton(strings.close, onClick = { controller.dismiss() })
                        Spacer(Modifier.weight(1f))
                        CtaButton(strings.sendOpenInBasetool, onClick = { controller.openResult() })
                    }
                    is SendState.Error -> {
                        Spacer(Modifier.weight(1f))
                        GhostButton(strings.close, onClick = { controller.dismiss() })
                    }
                    is SendState.Idle -> {}
                }
            }
        }
    }
}
