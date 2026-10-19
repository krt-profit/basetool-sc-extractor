package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.SendStrings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * The KRT modal (`.krt-modal`): a dimmed scrim that dismisses on click or Esc, and a panel card with an
 * orange top edge, the HUD corner brackets and the orange bloom, holding a head with the title and a
 * close button, a scrollable body and a footer of actions. [danger] swaps the accent for red, for a
 * modal whose action destroys something.
 *
 * @param title the title, shown upper-case
 * @param onDismiss called when the scrim, the close button or Esc dismisses the modal
 * @param danger whether the modal confirms a destructive action
 * @param subtitle an optional muted line under the title
 * @param maxWidth the card's widest extent
 * @param showFooter whether the footer row is drawn (off while a state offers no action)
 * @param body the card's content
 * @param footer the card's actions
 */
@Composable
fun KrtModal(
    title: String,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    subtitle: String? = null,
    maxWidth: Dp = 560.dp,
    showFooter: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
) {
    val strings = LocalStrings.current
    val accent = if (danger) Krt.Danger else Krt.Orange
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(Krt.Black.copy(alpha = 0.8f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focus)
                .focusable()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val swallow = remember { MutableInteractionSource() }
        Column(
            modifier =
                Modifier.widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .bloom(accent, 20.dp, 0.2f)
                    .background(Krt.Panel, KrtShape)
                    .border(1.dp, Krt.Hairline, KrtShape)
                    .drawWithContent {
                        drawContent()
                        drawRect(accent, size = Size(size.width, 3.dp.toPx()))
                        drawHudBrackets(accent, 13.dp)
                    }
                    .clickable(interactionSource = swallow, indication = null, onClick = {}),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .drawBehind {
                            drawLine(Krt.Hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                        }
                        .padding(start = 18.dp, end = 12.dp, top = 15.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (danger) Krt.DangerText else Krt.White,
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
                    }
                }
                IconGhostButton("✕", strings.close, onClick = onDismiss)
            }
            val bodyScroll = rememberScrollState()
            Box(Modifier.weight(1f, fill = false)) {
                Column(
                    modifier = Modifier.verticalScroll(bodyScroll).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = body,
                )
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    KrtVerticalScrollbar(bodyScroll, Modifier.fillMaxHeight())
                }
            }
            if (showFooter) Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .drawBehind { drawLine(Krt.Hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                content = footer,
            )
        }
    }
}

/**
 * The destructive action of a danger modal (`.btn-danger`): filled red with white bold UPPERCASE text.
 *
 * @param text the label
 * @param onClick called on press
 * @param enabled whether the action can be pressed
 */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier =
            Modifier.heightIn(min = 44.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .focusRing(focused)
                .background(if (hovered && enabled) Krt.DangerHover else Krt.Danger, KrtShape)
                .hoverable(interaction, enabled = enabled)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Krt.White, maxLines = 1, softWrap = false)
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
        Caption(strings.authCodeLabel, color = Krt.Gray1)
        SelectionContainer {
            Text(
                userCode,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = Krt.White,
                modifier =
                    Modifier.background(Krt.Bar, KrtShape)
                        .border(1.dp, Krt.Hairline, KrtShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        GhostButton(
            if (copied) strings.authCodeCopied else strings.authCopyCode,
            onClick = { copied = copyToClipboard(userCode) },
        )
    }
    Text(strings.waiting, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
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
