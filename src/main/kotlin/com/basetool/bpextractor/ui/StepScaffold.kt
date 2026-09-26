package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.unit.dp

/**
 * The skeleton of every workflow screen: a [SectionHead], a body filling the remaining height
 * (scrolling with [scrollBody]) and an optional pinned footer. The screen's primary CTA always lives
 * in the [footer], never in the scrolling [body].
 */
@Composable
fun StepScaffold(
    overline: String,
    title: String,
    subtitle: String? = null,
    headRight: (@Composable () -> Unit)? = null,
    scrollBody: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SectionHead(overline, title, subtitle, headRight)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            content = body,
        )
        if (footer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4.copy(alpha = 0.55f))
                    .drawBehind { drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = footer,
            )
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
            .background(Brush.horizontalGradient(listOf(Krt.Gray4.copy(alpha = 0.9f), Color.Transparent)))
            .drawBehind { drawLine(Krt.Gray3, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Krt.Orange))
        Column(Modifier.weight(1f).padding(start = 19.dp, top = 10.dp, bottom = 9.dp)) {
            Text(overline.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Gray2)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Krt.Orange)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2, maxLines = 1)
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
 * status square in [dot] followed by a short grey hint, e.g. the preflight throttle note or the
 * review "stays manual" reminder.
 */
@Composable
fun FootNote(text: String, dot: Color = Krt.Gray2) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dot)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
    }
}
