package com.basetool.bpextractor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.Legal

/**
 * The signature container: hairline border + two diagonal orange corner brackets
 * (top-left, bottom-right). Depth in this brand = hairlines + brackets + bloom,
 * never soft drop shadows. Mirrors `.hud-box` from the design system.
 */
fun Modifier.hudBox(
    fill: Color = Krt.Gray4.copy(alpha = 0.5f),
    hairline: Color = Krt.Gray3,
    bracket: Color = Krt.Orange,
): Modifier = this
    .background(fill)
    .border(1.dp, hairline)
    .drawWithContent {
        drawContent()
        val len = 12.dp.toPx()
        val w = 2.dp.toPx()
        val o = w / 2f
        val ww = size.width
        val hh = size.height
        drawLine(bracket, Offset(o, o), Offset(len, o), w)                 // TL top
        drawLine(bracket, Offset(o, o), Offset(o, len), w)                 // TL left
        drawLine(bracket, Offset(ww - o, hh - o), Offset(ww - len, hh - o), w) // BR bottom
        drawLine(bracket, Offset(ww - o, hh - o), Offset(ww - o, hh - len), w) // BR right
    }

/**
 * Keyboard-focus affordance in the brand idiom — a 2dp orange outline drawn as an
 * overlay (so it never shifts layout), mirroring the system's
 * `:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px }`.
 * A positive [offset] draws the ring *outside* the control (the caller must leave
 * that much breathing room around it); a negative [offset] insets it for controls
 * with no outer slack, e.g. the title-bar buttons.
 */
fun Modifier.focusRing(focused: Boolean, offset: Dp = 2.dp): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        val o = offset.toPx()
        drawRect(
            color = Krt.Orange,
            topLeft = Offset(-o, -o),
            size = Size(size.width + 2f * o, size.height + 2f * o),
            style = Stroke(width = 2.dp.toPx()),
        )
    }

/** Page-title banner: orange left-accent bar + dark→transparent fade (`.greeting`). */
@Composable
fun GreetingHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Brush.horizontalGradient(listOf(Krt.Gray4, Color.Transparent))),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Krt.Orange))
        Column(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Krt.Orange)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
        }
    }
}

/** Neutral, UPPERCASE block label above an input — never orange (accent = action). */
@Composable
fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Krt.Gray1,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Square, dark-fill text field: hairline border, orange focus border + caret. */
@Composable
fun KrtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        shape = RectangleShape,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(placeholder, color = Krt.Gray2, style = MaterialTheme.typography.bodyMedium) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Krt.SurfaceInput,
            unfocusedContainerColor = Krt.SurfaceInput,
            disabledContainerColor = Krt.SurfaceInput.copy(alpha = 0.4f),
            focusedBorderColor = Krt.Orange,
            unfocusedBorderColor = Krt.Gray3,
            disabledBorderColor = Krt.Gray3,
            cursorColor = Krt.Orange,
            focusedTextColor = Krt.Gray1,
            unfocusedTextColor = Krt.Gray1,
            disabledTextColor = Krt.Gray2,
        ),
    )
}

/**
 * THE primary action (filled orange + restrained orange bloom). Per the brand's
 * action hierarchy there is at most one of these per context. Black bold UPPERCASE
 * label, square corners, ≥44dp hit area.
 */
@Composable
fun CtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        !enabled -> Krt.Orange.copy(alpha = 0.40f)
        hovered -> Krt.OrangeHover
        else -> Krt.Orange
    }
    Box(
        modifier = modifier
            .padding(8.dp)
            .drawBehind {
                if (enabled) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Krt.Orange.copy(alpha = if (hovered) 0.32f else 0.18f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.maxDimension * 0.62f,
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .background(bg)
                .focusRing(focused)
                .hoverable(interaction, enabled = enabled)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Krt.Black)
        }
    }
}

/** Routine secondary action: neutral hairline that lights up orange on hover. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    // Hover and keyboard-focus both light the button orange (content + hairline);
    // focus additionally gets the offset ring below so it reads as :focus-visible.
    val active = (hovered || focused) && enabled
    val content = if (active) Krt.Orange else if (enabled) Krt.Gray1 else Krt.Gray2
    val border = if (active) Krt.Orange else Krt.Gray3
    val bg = if (hovered && enabled) Krt.Orange.copy(alpha = 0.07f) else Color.Transparent
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .focusRing(focused)
            .background(bg)
            .border(1.dp, border)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Square checkbox (the system rounds only pills + radios). Orange fill + black tick. */
@Composable
fun KrtCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .focusRing(focused)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(if (checked) Krt.Orange else Krt.SurfaceInput)
                .border(1.dp, if (checked) Krt.Orange else Krt.Gray3),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Canvas(Modifier.size(12.dp)) {
                    val s = 2.dp.toPx()
                    val w = size.width
                    val h = size.height
                    drawLine(Krt.Black, Offset(0.12f * w, 0.52f * h), Offset(0.42f * w, 0.82f * h), s, StrokeCap.Round)
                    drawLine(Krt.Black, Offset(0.42f * w, 0.82f * h), Offset(0.90f * w, 0.18f * h), s, StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        DisableSelection {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Krt.Gray1 else Krt.Gray2)
        }
    }
}

/** Small HUD status dot (`.status-pill::before`) — a tiny solid square. */
@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color))
}

/** Loads the brand honeycomb tile (orange hexagons at 0.1 opacity) as a painter. */
@Composable
fun rememberHoneycombPainter(): Painter {
    val density = LocalDensity.current
    return remember(density) { useResource("honeycomb-bg.svg") { loadSvgPainter(it, density) } }
}

/**
 * Tiles [painter] across the whole drawing area behind the content — the subtle HUD
 * texture. The honeycomb tile is seamless, so we just stamp it on a grid.
 */
fun Modifier.tiled(painter: Painter): Modifier = this.drawBehind {
    val tw = painter.intrinsicSize.width
    val th = painter.intrinsicSize.height
    if (tw <= 0f || th <= 0f || !tw.isFinite() || !th.isFinite()) return@drawBehind
    var y = 0f
    while (y < size.height) {
        var x = 0f
        while (x < size.width) {
            translate(x, y) { with(painter) { draw(Size(tw, th)) } }
            x += tw
        }
        y += th
    }
}

/**
 * Always-visible Star Citizen fan disclaimer footer. Renders the official
 * "Made by the Community" logo (unaltered, full opacity — never recolored, flipped,
 * distorted or shadowed) alongside the required trademark notice, as mandated by the
 * Star Citizen Fankit Guidelines (logo in a corner at ≥50% opacity + the trademark
 * notice in a legible size/color, visible regardless of scrolling). [logo] must be the
 * white-ink variant (`MadeByTheCommunity_Black.png`) so it reads on the dark HUD.
 */
@Composable
fun CommunityDisclaimerFooter(logo: Painter, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Krt.Gray4)
            .drawBehind { drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 16.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Unaltered, fully opaque, only proportionally scaled (Fankit "what not to do").
        Image(painter = logo, contentDescription = "Made by the Community", modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(Legal.UNAFFILIATED, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
            // Required notice: kept legible (Gray1 on Gray4) and ≥10pt (bodySmall = 13sp).
            Text(Legal.TRADEMARK_NOTICE, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
        }
    }
}
