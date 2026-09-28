package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.ui.i18n.SendStrings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * The KRT scrim modal: a dimmed backdrop that dismisses on click, and an orange-framed card with a title
 * bar, a scrollable body and a footer of actions.
 *
 * @param title the title, shown upper-case
 * @param onDismiss called when the backdrop is clicked
 * @param body the card's content
 * @param footer the card's actions
 */
@Composable
fun KrtModal(
    title: String,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(Krt.Black.copy(alpha = 0.8f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
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
                                    colors = listOf(Krt.Orange.copy(alpha = 0.18f), Color.Transparent),
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
                            drawLine(Krt.Orange, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Krt.Orange)
            }
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = body,
            )
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Krt.Gray4.copy(alpha = 0.55f))
                        .drawBehind { drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }
}

/**
 * The body of the browser-approval step, shared by the send and the sync: why the browser is needed, the
 * instruction, the code — selectable and with a copy button — and the waiting line.
 *
 * @param strings the send strings of the active language
 * @param userCode the code to confirm
 * @param reason why the browser is needed instead of the stored login
 */
@Composable
fun AuthenticatingBody(strings: SendStrings, userCode: String, reason: LoginReason) {
    val reasonText =
        when (reason) {
            LoginReason.KEY_UPGRADE -> strings.authKeyUpgrade
            LoginReason.SCOPE_UPGRADE -> strings.authScopeUpgrade
            LoginReason.NONE -> null
        }
    if (reasonText != null) Text(reasonText, style = MaterialTheme.typography.bodyMedium, color = Krt.White)
    Text(strings.authBody, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
    var copied by remember(userCode) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(strings.authCodeLabel, style = MaterialTheme.typography.headlineSmall, color = Krt.Gray1)
        SelectionContainer {
            Text(userCode, style = MaterialTheme.typography.headlineSmall, color = Krt.White)
        }
        Spacer(Modifier.weight(1f))
        GhostButton(
            if (copied) strings.authCodeCopied else strings.authCopyCode,
            onClick = { copied = copyToClipboard(userCode) },
        )
    }
    Text(strings.waiting, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
}

/**
 * The installation-label field of a consent step.
 *
 * @param strings the send strings of the active language
 * @param label the label as typed
 * @param invalid whether the last confirmation refused it
 * @param onChange called with every edit
 */
@Composable
fun InstallationLabelField(strings: SendStrings, label: String, invalid: Boolean, onChange: (String) -> Unit) {
    FieldLabel(strings.labelTitle)
    KrtTextField(
        value = label,
        onValueChange = onChange,
        placeholder = strings.defaultLabel,
        isError = invalid,
        supportingText = if (invalid) strings.labelInvalid else strings.labelHint,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Puts [text] on the system clipboard.
 *
 * @param text the text to copy
 * @return whether the clipboard took it
 */
fun copyToClipboard(text: String): Boolean =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess
