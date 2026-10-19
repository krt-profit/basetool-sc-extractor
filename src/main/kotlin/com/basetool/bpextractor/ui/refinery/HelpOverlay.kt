package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.CheckMark
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FootNote
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.KrtModal
import com.basetool.bpextractor.ui.KrtShape
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/** The three terminal commands shown in the install section — language-invariant literals. */
private val INSTALL_CODE_LINES = listOf("ollama.com/download", "ollama serve", "ollama pull qwen3-vl:8b-instruct")

/** The browser target behind the first install code line. */
private const val OLLAMA_DOWNLOAD_URL = "https://ollama.com/download"

/**
 * The preflight help page (`REDESIGN_IMPLEMENTATION.md` §4.3a): a wide KRT modal explaining how the
 * local image recognition works, the hardware tiers, the Ollama installation steps with code lines, and
 * capture tips. Closable via the ✕, the scrim, Esc and the "Got it" CTA; the body scrolls, the head and
 * footer stay pinned.
 */
@Composable
fun HelpOverlay(onClose: () -> Unit) {
    val strings = LocalStrings.current
    KrtModal(
        title = strings.helpTitle,
        subtitle = strings.helpSubtitle,
        onDismiss = onClose,
        maxWidth = 760.dp,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                HelpSection("01", strings.helpSec1Title) {
                    Text(strings.helpSec1Body, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }

                HelpSection("02", strings.helpSec2Title) {
                    Text(strings.helpSec2Body, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.fillMaxWidth().clip(KrtShape).background(Krt.Canvas).border(1.dp, Krt.Hairline, KrtShape)) {
                        strings.helpTierRows.forEachIndexed { index, row ->
                            val dot = when (index) {
                                0 -> Krt.Success
                                1 -> Krt.Warning
                                else -> Krt.ControlBorder
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (index > 0) {
                                            Modifier.drawBehind {
                                                drawLine(Krt.Hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
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
                                Text(row[3], style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted, modifier = Modifier.weight(1.3f))
                            }
                        }
                    }
                }

                HelpSection("03", strings.helpSec3Title) {
                    HelpStep(1, strings.helpStep1Title, strings.helpStep1Body, INSTALL_CODE_LINES[0], url = OLLAMA_DOWNLOAD_URL)
                    HelpStep(2, strings.helpStep2Title, strings.helpStep2Body, INSTALL_CODE_LINES[1])
                    HelpStep(3, strings.helpStep3Title, strings.helpStep3Body, INSTALL_CODE_LINES[2])
                    AlertBox(Krt.Info) {
                        Text(
                            strings.helpInfoTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Krt.Gray1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(strings.helpInfoBody, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
                    }
                }

                HelpSection("04", strings.helpSec4Title) {
                    strings.helpTips.forEach { tip ->
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.padding(top = 5.dp)) { CheckMark(Krt.SuccessText, 12.dp) }
                            Column {
                                Text(
                                    tip[0],
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Krt.White,
                                )
                                Text(tip[1], style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
                            }
                        }
                    }
                }
            }
        },
        footer = {
            FootNote(strings.helpMore)
            Spacer(Modifier.weight(1f))
            CtaButton(strings.helpGotIt, onClick = onClose)
        },
    )
}

/** One numbered help section: "NN  TITLE" over a hairline, then the section content. */
@Composable
private fun HelpSection(number: String, title: String, content: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(Krt.Hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = Krt.TextMuted)
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = Krt.White)
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** One numbered install step: a 22dp orange-bordered number square, title, body and a code line. */
@Composable
private fun HelpStep(number: Int, title: String, body: String, code: String, url: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(24.dp).background(Krt.Canvas, KrtShape).border(1.dp, Krt.Orange, KrtShape),
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
            Text(body, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
            Spacer(Modifier.height(5.dp))
            CodeLine(code, url)
        }
    }
}

/**
 * A terminal-style code line: dark input fill, 3dp orange left edge, "$" prompt + the command.
 * With a [url] (and BROWSE support) the line becomes a link: hand cursor, orange underline on
 * hover, click opens the URL in the default browser.
 */
@Composable
private fun CodeLine(code: String, url: String? = null) {
    val canBrowse = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }
    val linked = url != null && canBrowse
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KrtShape)
            .background(Krt.Canvas)
            .border(1.dp, Krt.Hairline, KrtShape)
            .drawBehind { drawRect(Krt.Orange, size = Size(3.dp.toPx(), size.height)) }
            .then(
                if (url != null && canBrowse) {
                    Modifier
                        .hoverable(interaction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interaction, indication = null) {
                            scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$", style = KrtDataStyle, color = Krt.TextMuted)
        Text(
            code,
            style = if (linked) KrtDataStyle.copy(textDecoration = TextDecoration.Underline) else KrtDataStyle,
            color = if (linked && hovered) Krt.Orange else Krt.White,
        )
    }
}
