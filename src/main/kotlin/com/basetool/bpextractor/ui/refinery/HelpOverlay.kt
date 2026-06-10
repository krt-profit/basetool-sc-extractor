package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FootNote
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.i18n.LocalStrings

/** The three terminal commands shown in the install section — language-invariant literals. */
private val INSTALL_CODE_LINES = listOf("ollama.com/download", "ollama serve", "ollama pull qwen3-vl:8b-instruct")

/**
 * The preflight help page (`REDESIGN_IMPLEMENTATION.md` §4.3a): a KRT modal (no native dialog)
 * over a dark scrim, explaining how the local image recognition works, the hardware tiers, the
 * Ollama installation steps with code lines, and capture tips. Closable via the X, the scrim,
 * ESC and the "Got it" CTA; the body scrolls, the header and footer stay pinned.
 */
@Composable
fun HelpOverlay(onClose: () -> Unit) {
    val strings = LocalStrings.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Krt.Black.copy(alpha = 0.8f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose, // click on the scrim = close
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val swallow = remember { MutableInteractionSource() }
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .drawBehind {
                    // The brand's only "shadow": a restrained orange bloom around the panel.
                    val grow = 28.dp.toPx()
                    drawRect(
                        brush = Brush.radialGradient(
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
                .drawWithContent {
                    drawContent()
                    val len = 12.dp.toPx()
                    val w = 2.dp.toPx()
                    val o = w / 2f
                    drawLine(Krt.Orange, Offset(o, o), Offset(len, o), w)
                    drawLine(Krt.Orange, Offset(o, o), Offset(o, len), w)
                    drawLine(Krt.Orange, Offset(size.width - o, size.height - o), Offset(size.width - len, size.height - o), w)
                    drawLine(Krt.Orange, Offset(size.width - o, size.height - o), Offset(size.width - o, size.height - len), w)
                }
                .clickable(interactionSource = swallow, indication = null, onClick = {}), // swallow panel clicks
        ) {
            // Header: orange bottom edge, title + subtitle + close X.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4)
                    .drawBehind { drawLine(Krt.Orange, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(strings.helpTitle.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Krt.Orange)
                    Spacer(Modifier.height(2.dp))
                    Text(strings.helpSubtitle, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                }
                HelpCloseButton(onClose)
            }

            // Scrollable body with the four numbered sections.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                HelpSection("01", strings.helpSec1Title) {
                    Text(strings.helpSec1Body, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }

                HelpSection("02", strings.helpSec2Title) {
                    Text(strings.helpSec2Body, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.fillMaxWidth().border(1.dp, Krt.Gray3)) {
                        strings.helpTierRows.forEachIndexed { index, row ->
                            val dot = when (index) {
                                0 -> Krt.Success
                                1 -> Krt.Warning
                                else -> Krt.Gray2
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (index > 0) {
                                            Modifier.drawBehind {
                                                drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.weight(1.1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    StatusDot(dot)
                                    Text(
                                        row[0],
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = Krt.White,
                                    )
                                }
                                Text(row[1], style = KrtDataStyle, color = Krt.Gray1, modifier = Modifier.weight(1f))
                                Text(row[2], style = KrtDataStyle, color = Krt.Gray1, modifier = Modifier.weight(1.1f))
                                Text(row[3], style = MaterialTheme.typography.bodySmall, color = Krt.Gray2, modifier = Modifier.weight(1.3f))
                            }
                        }
                    }
                }

                HelpSection("03", strings.helpSec3Title) {
                    HelpStep(1, strings.helpStep1Title, strings.helpStep1Body, INSTALL_CODE_LINES[0])
                    HelpStep(2, strings.helpStep2Title, strings.helpStep2Body, INSTALL_CODE_LINES[1])
                    HelpStep(3, strings.helpStep3Title, strings.helpStep3Body, INSTALL_CODE_LINES[2])
                    AlertBox(Krt.Info) {
                        Text(
                            strings.helpInfoTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Krt.Gray1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(strings.helpInfoBody, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                    }
                }

                HelpSection("04", strings.helpSec4Title) {
                    strings.helpTips.forEach { tip ->
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("✓", style = MaterialTheme.typography.bodyMedium, color = Krt.Orange)
                            Column {
                                Text(
                                    tip[0],
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Krt.White,
                                )
                                Text(tip[1], style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
                            }
                        }
                    }
                }
            }

            // Pinned footer: hint left, the one orange CTA right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4.copy(alpha = 0.55f))
                    .drawBehind { drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FootNote(strings.helpMore)
                Spacer(Modifier.weight(1f))
                CtaButton(strings.helpGotIt, onClick = onClose)
            }
        }
    }
}

/** One numbered help section: "NN  TITLE" over a hairline, then the section content. */
@Composable
private fun HelpSection(number: String, title: String, content: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(Krt.Gray3, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = Krt.Gray2)
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = Krt.White)
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** One numbered install step: a 22dp orange-bordered number square, title, body and a code line. */
@Composable
private fun HelpStep(number: Int, title: String, body: String, code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(22.dp).border(1.dp, Krt.Orange),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = Krt.Orange)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Krt.White,
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
            Spacer(Modifier.height(5.dp))
            CodeLine(code)
        }
    }
}

/** A terminal-style code line: dark input fill, 3dp orange left edge, "$" prompt + the command. */
@Composable
private fun CodeLine(code: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Krt.SurfaceInput)
            .border(1.dp, Krt.Gray3)
            .drawBehind { drawRect(Krt.Orange, size = Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$", style = KrtDataStyle, color = Krt.Gray2)
        Text(code, style = KrtDataStyle, color = Krt.White)
    }
}

/** The modal's close X: hairline square that lights orange on hover. */
@Composable
private fun HelpCloseButton(onClick: () -> Unit) {
    val strings = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(34.dp)
            .border(1.dp, if (hovered) Krt.Orange else Krt.Gray3)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "✕",
            style = MaterialTheme.typography.labelLarge,
            color = if (hovered) Krt.Orange else Krt.Gray1,
        )
    }
}
