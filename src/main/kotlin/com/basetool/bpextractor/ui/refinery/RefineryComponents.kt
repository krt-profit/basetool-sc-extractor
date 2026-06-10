package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.hudBox

/**
 * A §5.1 `PanelCard`: HUD box whose header row carries an orange left bar, a Lato-Bold title and
 * a status dot (OK = green, warning = amber, missing/error = red, checking = grey).
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
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Krt.Gray4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Krt.Orange))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = Krt.Orange,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp).weight(1f),
            )
            StatusDot(dot)
            Spacer(Modifier.width(14.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** A neutral key/value row (endpoint, model, GPU/VRAM/RAM rows). */
@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Krt.Gray1) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            key.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Krt.Gray2,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/**
 * A status alert in the brand idiom (the design system's `.alert`): 4px coloured left border on
 * a dark fill — used for the §5.1 install hints, the §5.3 un-quoted warning and §5.5 success.
 */
@Composable
fun AlertBox(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 12.dp)) {
            content()
        }
    }
}

/** A small square chip (auto-selected model, resolution tags, crop tags, header badges). */
@Composable
fun KrtChip(text: String, color: Color = Krt.Gray1, border: Color = Krt.Gray3) {
    Box(
        modifier = Modifier
            .background(Krt.SurfaceInput)
            .border(1.dp, border)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * The §5.1 min/recommended tier bar: three segments (CPU · MIN · EMPF), the active tier filled
 * orange, passed tiers green-tinted, upcoming neutral.
 */
@Composable
fun TierBar(labels: List<String>, activeIndex: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val (bg, fg) = when {
                index == activeIndex -> Krt.Orange to Krt.Black
                index < activeIndex -> Krt.Success.copy(alpha = 0.25f) to Krt.Gray1
                else -> Krt.SurfaceInput to Krt.Gray2
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bg)
                    .border(1.dp, Krt.Gray3)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}

/** A round radio option in KRT colours (the system rounds only pills + radios). */
@Composable
fun KrtRadio(selected: Boolean, label: String, onSelect: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            onClick = onSelect,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Krt.SurfaceInput)
                .border(1.dp, if (selected) Krt.Orange else Krt.Gray3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Krt.Orange))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Krt.Gray1 else Krt.Gray2)
    }
}

/** Confidence presentation (§5.4): dot keeps the canonical hue, the text uses accessible tints. */
data class ConfidenceColors(val dot: Color, val text: Color)

/** Map a 0..1 confidence to the §5.4 thresholds: ≥0.90 success / 0.75–0.90 warning / <0.75 danger. */
fun confidenceColors(confidence: Double): ConfidenceColors = when {
    confidence >= 0.90 -> ConfidenceColors(Krt.Success, Color(0xFF6FCF7C))
    confidence >= 0.75 -> ConfidenceColors(Krt.Warning, Color(0xFFFFD23F))
    else -> ConfidenceColors(Krt.Danger, Color(0xFFFF6B74))
}
