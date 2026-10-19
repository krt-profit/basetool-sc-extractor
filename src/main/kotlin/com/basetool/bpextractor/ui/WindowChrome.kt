package com.basetool.bpextractor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.MouseInfo
import java.awt.Point

/**
 * Custom KRT window chrome that replaces the white OS title bar: the app header on the bar surface with
 * the Basetool Extractor mark, the title in Lato Bold orange, the 2 dp orange bottom rule and hand-drawn
 * minimize / maximize / close controls. The title region is draggable.
 */
@Composable
fun FrameWindowScope.KrtTitleBar(
    state: WindowState,
    icon: Painter,
    title: String,
    onClose: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val win = window
    KrtTitleBarContent(
        icon = icon,
        title = title,
        maximized = state.placement == WindowPlacement.Maximized,
        onMinimize = { state.isMinimized = true },
        onToggleMaximize = {
            state.placement =
                if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
        },
        onClose = onClose,
        dragModifier = Modifier.pointerInput(Unit) {
            var startMouse = Point()
            var startWin = Point()
            detectDragGestures(
                onDragStart = {
                    startMouse = MouseInfo.getPointerInfo().location
                    startWin = win.location
                },
                onDrag = { change, _ ->
                    change.consume()
                    val now = MouseInfo.getPointerInfo().location
                    win.setLocation(startWin.x + (now.x - startMouse.x), startWin.y + (now.y - startMouse.y))
                },
            )
        },
        actions = actions,
    )
}

/**
 * The window-independent body of [KrtTitleBar]: mark, title, [actions] and the three window controls;
 * [dragModifier] makes the title region move the window.
 */
@Composable
fun KrtTitleBarContent(
    icon: Painter,
    title: String,
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    dragModifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).background(Krt.Bar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(dragModifier)
                    .padding(start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            actions()

            WindowControlButton(onClick = onMinimize) { c ->
                drawLine(c, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5.dp.toPx())
            }
            WindowControlButton(onClick = onToggleMaximize) { c ->
                val s = 1.5.dp.toPx()
                if (maximized) {
                    val w = size.width * 0.72f
                    val h = size.height * 0.72f
                    drawRect(c, topLeft = Offset(size.width - w, 0f), size = Size(w, h), style = Stroke(s))
                    drawRect(c, topLeft = Offset(0f, size.height - h), size = Size(w, h), style = Stroke(s))
                } else {
                    drawRect(c, style = Stroke(s))
                }
            }
            WindowControlButton(onClick = onClose, danger = true) { c ->
                val s = 1.5.dp.toPx()
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), s)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), s)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Krt.Orange))
    }
}

/** A 46×40 window-control hit area with a hand-drawn icon; hover lights it orange (or red for close). */
@Composable
private fun WindowControlButton(
    onClick: () -> Unit,
    danger: Boolean = false,
    icon: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        hovered && danger -> Krt.Danger
        hovered -> Krt.ControlHover
        else -> Color.Transparent
    }
    val fg = when {
        hovered && danger -> Krt.White
        hovered || focused -> Krt.Orange
        else -> Krt.Gray1
    }
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 40.dp)
            .focusRing(focused, offset = (-7).dp)
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(11.dp)) { icon(fg) }
    }
}

/**
 * Bottom-right resize grip for the undecorated window (which has no OS resize border).
 * Drag to resize; a few diagonal ticks hint at the affordance.
 */
@Composable
fun BoxScope.ResizeCorner(state: WindowState) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(18.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    with(density) {
                        val w = (state.size.width + drag.x.toDp()).coerceAtLeast(640.dp)
                        val h = (state.size.height + drag.y.toDp()).coerceAtLeast(520.dp)
                        state.size = DpSize(w, h)
                    }
                }
            }
            .drawBehind {
                val s = 1.5.dp.toPx()
                for (i in 1..3) {
                    val o = i * 4.dp.toPx()
                    drawLine(Krt.ControlBorder, Offset(size.width - o, size.height), Offset(size.width, size.height - o), s)
                }
            },
    )
}
