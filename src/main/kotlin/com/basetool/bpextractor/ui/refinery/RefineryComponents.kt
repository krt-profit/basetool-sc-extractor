package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtShape
import com.basetool.bpextractor.ui.RadioMark
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.focusRing
import com.basetool.bpextractor.ui.hudBox

/**
 * A §5.1 `PanelCard`: HUD box whose head (`.panel-header`) sits on the bar surface with an orange left
 * bar, a light uppercase title and a status dot (OK = green, warning = amber, missing/error = red,
 * checking = grey).
 */
@Composable
fun PanelCard(
    title: String,
    dot: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.hudBox()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(KrtShape)
                .background(Krt.Bar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Krt.Orange))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = Krt.White,
                modifier = Modifier.padding(start = 12.dp, top = 11.dp, bottom = 11.dp).weight(1f),
            )
            StatusDot(dot)
            Spacer(Modifier.width(14.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Hairline))
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** A key/value row (`.kv-list`): muted uppercase key, the value as bright data. */
@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Krt.White) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Krt.TextMuted,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/**
 * A status alert (`.alert`): a tint of [color] with a frame and a 4 dp left border in [color], text in
 * Grau 1 — used for the §5.1 install hints, the §5.3 un-quoted warning and §5.5 success.
 */
@Composable
fun AlertBox(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(KrtShape)
            .background(color.copy(alpha = if (color == Krt.Warning) 0.15f else 0.20f))
            .border(1.dp, color, KrtShape),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 12.dp)) {
            content()
        }
    }
}

/** The tone of a [KrtChip] (`.chip--*`): the frame and the text take the hue over a faint tint. */
enum class ChipTone(val border: Color, val text: Color, val fill: Color) {
    NEUTRAL(Krt.Hairline, Krt.Gray1, Krt.Bar),
    PRIMARY(Krt.Orange, Krt.OrangeHover, Krt.Orange.copy(alpha = 0.10f)),
    SUCCESS(Krt.Success, Krt.SuccessText, Krt.Success.copy(alpha = 0.12f)),
    DANGER(Krt.Danger, Krt.DangerText, Krt.Danger.copy(alpha = 0.15f)),
    WARNING(Krt.Warning, Krt.Warning, Krt.Warning.copy(alpha = 0.12f)),
    INFO(Krt.Info, Krt.InfoText, Krt.Info.copy(alpha = 0.15f)),
    MUTED(Krt.Hairline, Krt.TextMuted, Color.Transparent),
}

/** A small inline data label (`.chip`): auto-selected model, resolution tags, crop tags, header badges. */
@Composable
fun KrtChip(text: String, tone: ChipTone = ChipTone.NEUTRAL) {
    Box(
        modifier = Modifier
            .background(tone.fill, KrtShape)
            .border(1.dp, tone.border, KrtShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = tone.text)
    }
}

/**
 * The §5.1 min/recommended tier bar: three segments (CPU · MIN · EMPF) in the segmented look — the
 * active tier tinted orange with frame and underline, passed tiers green-tinted, upcoming neutral.
 */
@Composable
fun TierBar(labels: List<String>, activeIndex: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Krt.Control, KrtShape)
            .border(1.dp, Krt.ControlBorder, KrtShape)
            .clip(KrtShape),
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Krt.ControlBorder))
            val active = index == activeIndex
            val (bg, fg) = when {
                active -> Krt.ControlSelected to Krt.White
                index < activeIndex -> Krt.Success.copy(alpha = 0.18f) to Krt.Gray1
                else -> Color.Transparent to Krt.TextMuted
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bg)
                    .drawWithContent {
                        drawContent()
                        if (active) {
                            val w = 1.dp.toPx()
                            drawRect(Krt.Orange, topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w), style = Stroke(w))
                            val u = 3.dp.toPx()
                            drawRect(Krt.Orange, topLeft = Offset(0f, size.height - u), size = Size(size.width, u))
                        }
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}

/** A radio option (`input[type=radio]` with its label). */
@Composable
fun KrtRadio(selected: Boolean, label: String, onSelect: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusRing(focused)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = null,
                onClick = onSelect,
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
    ) {
        RadioMark(selected, enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Krt.Gray1 else Krt.TextMuted)
    }
}

/** Confidence presentation (§5.4): dot keeps the canonical hue, the text uses accessible tints. */
data class ConfidenceColors(val dot: Color, val text: Color)

/** Map a 0..1 confidence to the §5.4 thresholds: ≥0.90 success / 0.75–0.90 warning / <0.75 danger. */
fun confidenceColors(confidence: Double): ConfidenceColors = when {
    confidence >= 0.90 -> ConfidenceColors(Krt.Success, Krt.SuccessText)
    confidence >= 0.75 -> ConfidenceColors(Krt.Warning, Krt.Warning)
    else -> ConfidenceColors(Krt.Danger, Krt.DangerText)
}
