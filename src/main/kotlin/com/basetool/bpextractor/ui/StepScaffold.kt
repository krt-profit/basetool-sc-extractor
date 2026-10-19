package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The skeleton of every workflow screen: a [SectionHead], a body filling the remaining height and an
 * optional pinned footer. With [scrollBody] the body scrolls; without it the body fills the height, and
 * a window too small for [minBodyHeight] scrolls the body at that height instead of squashing it. The
 * screen's primary CTA always lives in the [footer], never in the scrolling [body].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepScaffold(
    overline: String,
    title: String,
    subtitle: String? = null,
    headRight: (@Composable () -> Unit)? = null,
    scrollBody: Boolean = true,
    minBodyHeight: Dp = 440.dp,
    footer: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SectionHead(overline, title, subtitle, headRight)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cramped = !scrollBody && maxHeight < minBodyHeight
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            scrollBody -> Modifier.fillMaxHeight().verticalScroll(scroll)
                            cramped -> Modifier.verticalScroll(scroll).height(minBodyHeight)
                            else -> Modifier.fillMaxHeight()
                        },
                    )
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                content = body,
            )
            if (scrollBody || cramped) {
                KrtVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        if (footer != null) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Bar)
                    .drawBehind { drawLine(Krt.Hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) { footer() }
        }
    }
}

/**
 * The slim per-step header inside workflows: an orange accent edge, an uppercase overline, the title
 * with an optional subtitle, and an optional [right] slot for badges or actions.
 */
@Composable
fun SectionHead(
    overline: String,
    title: String,
    subtitle: String? = null,
    right: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Brush.horizontalGradient(listOf(Krt.Panel, Color.Transparent)))
            .drawBehind { drawLine(Krt.Hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Krt.Orange))
        Column(Modifier.weight(1f).padding(start = 18.dp, top = 10.dp, bottom = 10.dp)) {
            Text(overline.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.TextMuted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Krt.Orange)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted, maxLines = 1)
                }
            }
        }
        if (right != null) {
            Box(Modifier.padding(end = 22.dp)) { right() }
        }
    }
}

/**
 * A muted footer note for the [StepScaffold] action bar (the prototype's `FootNote`): a small
 * status square in [dot] followed by a short hint in the muted text tint, e.g. the preflight throttle note or the
 * review "stays manual" reminder.
 */
@Composable
fun FootNote(text: String, dot: Color = Krt.ControlBorder) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dot)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
    }
}
