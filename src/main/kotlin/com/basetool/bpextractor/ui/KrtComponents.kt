package com.basetool.bpextractor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.basetool.bpextractor.Legal
import com.basetool.bpextractor.resources.Res
import com.basetool.bpextractor.resources.github
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import java.awt.Desktop
import java.net.URI

/** Opacity of a disabled button or control (`.btn:disabled`). */
const val DISABLED_ALPHA = 0.45f

/** The warning sign in its text presentation, so no font renders it as a coloured emoji. */
const val WARN = "⚠︎"

/**
 * The brand's only elevation (`--glow-primary`, `--glow-primary-lg`): a soft [color] glow of [radius]
 * hugging the rounded edge, fading out from [alpha]. It replaces drop shadows; it never darkens.
 */
fun Modifier.bloom(color: Color, radius: Dp, alpha: Float): Modifier = drawBehind {
    val r = radius.toPx()
    val corner = 4.dp.toPx()
    val steps = 12
    for (i in steps downTo 1) {
        val d = r * i / steps
        val a = alpha * (1f - i.toFloat() / (steps + 1)).let { it * it } / 7.5f
        drawRoundRect(
            color = color.copy(alpha = a),
            topLeft = Offset(-d, -d),
            size = Size(size.width + 2f * d, size.height + 2f * d),
            cornerRadius = CornerRadius(corner + d, corner + d),
        )
    }
}

/** Draws the two diagonal HUD corner brackets (top-left, bottom-right) of [length] in [color]. */
fun DrawScope.drawHudBrackets(color: Color, length: Dp) {
    val len = length.toPx()
    val w = 2.dp.toPx()
    val o = w / 2f
    val ww = size.width
    val hh = size.height
    drawLine(color, Offset(0f, o), Offset(len, o), w)
    drawLine(color, Offset(o, 0f), Offset(o, len), w)
    drawLine(color, Offset(ww, hh - o), Offset(ww - len, hh - o), w)
    drawLine(color, Offset(ww - o, hh), Offset(ww - o, hh - len), w)
}

/**
 * The signature container (`.hud-box`): panel fill, hairline frame with the 4 dp radius and two
 * diagonal orange corner brackets. Depth in this brand is hairlines, brackets and bloom, never soft
 * drop shadows.
 */
fun Modifier.hudBox(
    fill: Color = Krt.Panel,
    hairline: Color = Krt.Hairline,
    bracket: Color = Krt.Orange,
): Modifier = this
    .background(fill, KrtShape)
    .border(1.dp, hairline, KrtShape)
    .drawWithContent {
        drawContent()
        drawHudBrackets(bracket, 10.dp)
    }

/** The active drop target (`.drop-zone.is-over`): a faint orange wash inside a 2 dp dashed orange outline. */
fun Modifier.dropHighlight(): Modifier = drawWithContent {
    drawContent()
    drawRect(Krt.Orange.copy(alpha = 0.06f))
    val inset = 4.dp.toPx()
    val dash = 6.dp.toPx()
    drawRoundRect(
        color = Krt.Orange,
        topLeft = Offset(inset, inset),
        size = Size(size.width - 2f * inset, size.height - 2f * inset),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}

/**
 * Draws the 2 dp keyboard-focus outline (`:focus-visible`) as an overlay without shifting layout. A
 * positive [offset] draws it outside the control, a negative one insets it; [color] is white on the
 * orange CTA.
 */
fun Modifier.focusRing(focused: Boolean, offset: Dp = 2.dp, color: Color = Krt.Orange): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        val o = offset.toPx()
        val stroke = 2.dp.toPx()
        val radius = (4.dp.toPx() + o).coerceAtLeast(0f)
        drawRoundRect(
            color = color,
            topLeft = Offset(-o - stroke / 2f, -o - stroke / 2f),
            size = Size(size.width + 2f * o + stroke, size.height + 2f * o + stroke),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
    }

/** Page-title banner (`.greeting`): orange left-accent bar and a panel → transparent fade. */
@Composable
fun GreetingHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Brush.horizontalGradient(listOf(Krt.Panel, Color.Transparent))),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Krt.Orange))
        Column(Modifier.padding(start = 20.dp, top = 10.dp, bottom = 10.dp)) {
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

/** Small uppercase caption above a group in the muted text tint (`.page-head__eyebrow`, `.kv-key`). */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Krt.TextMuted) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

/**
 * Single-line text field on the control fill with the control border, the 4 dp radius and an orange
 * focus border and caret. With [isError] the border turns danger red and [supportingText] shows below
 * in the danger text tint with a "⚠" glyph; without it [supportingText] is a muted hint.
 */
@Composable
fun KrtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        shape = KrtShape,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                placeholder,
                color = Krt.TextMuted,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            )
        },
        supportingText = supportingText?.let {
            {
                if (isError) {
                    Text("$WARN $it", color = Krt.DangerText, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(it, color = Krt.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Krt.Control,
            unfocusedContainerColor = Krt.Control,
            disabledContainerColor = Krt.Control.copy(alpha = DISABLED_ALPHA),
            errorContainerColor = Krt.Control,
            focusedBorderColor = Krt.Orange,
            unfocusedBorderColor = Krt.ControlBorder,
            disabledBorderColor = Krt.ControlBorder.copy(alpha = DISABLED_ALPHA),
            errorBorderColor = Krt.Danger,
            cursorColor = Krt.Orange,
            errorCursorColor = Krt.Orange,
            focusedTextColor = Krt.Gray1,
            unfocusedTextColor = Krt.Gray1,
            disabledTextColor = Krt.TextMuted,
            errorTextColor = Krt.Gray1,
        ),
    )
}

/**
 * THE primary action (`.btn.btn--cta`): filled orange with dark bold UPPERCASE text, the 4 dp radius
 * and the restrained orange bloom. There is at most one per context; a disabled CTA fades to 45 %.
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
    val bg = if (hovered && enabled) Krt.OrangeHover else Krt.Orange
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(if (enabled) Modifier.bloom(Krt.Orange, if (hovered) 20.dp else 6.dp, if (hovered) 0.2f else 0.3f) else Modifier)
            .focusRing(focused, color = Krt.White)
            .background(bg, KrtShape)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Krt.Black, maxLines = 1, softWrap = false)
    }
}

/** The look of a non-CTA button on the action ladder. */
enum class ButtonTone {
    /** Emphasized secondary (`.btn-outline`): orange frame and text, no fill. */
    OUTLINE,

    /** Routine secondary (`.btn-ghost`): control fill and border, orange on hover. */
    GHOST,

    /** Destructive (`.btn-quiet-danger`): control fill and border, muted text, red on hover. */
    QUIET_DANGER,
}

/** Fill, border and text colour of a [ButtonTone] at rest or under the pointer. */
private fun toneColors(tone: ButtonTone, hot: Boolean): Triple<Color, Color, Color> = when (tone) {
    ButtonTone.OUTLINE ->
        if (hot) Triple(Krt.Orange.copy(alpha = 0.12f), Krt.OrangeHover, Krt.OrangeHover)
        else Triple(Color.Transparent, Krt.Orange, Krt.Orange)
    ButtonTone.GHOST ->
        if (hot) Triple(Krt.Orange.copy(alpha = 0.07f), Krt.Orange, Krt.OrangeHover)
        else Triple(Krt.Control, Krt.ControlBorder, Krt.Gray1)
    ButtonTone.QUIET_DANGER ->
        if (hot) Triple(Krt.Danger.copy(alpha = 0.12f), Krt.Danger, Krt.DangerText)
        else Triple(Krt.Control, Krt.ControlBorder, Krt.TextMuted)
}

/**
 * Routine secondary action (`.btn-ghost`): control fill, control border, Grau-1 text; hover tints it
 * orange. [tone] switches to the quiet-danger look for destructive actions; [compact] is the 32 dp
 * dense-row size (`.btn-xs`).
 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ButtonTone = ButtonTone.GHOST,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (bg, border, fg) = toneColors(tone, hovered && enabled)
    Box(
        modifier = modifier
            .heightIn(min = if (compact) 32.dp else 44.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .focusRing(focused)
            .background(bg, KrtShape)
            .border(1.dp, border, KrtShape)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 6.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Square icon-only action (`.btn.btn-ghost.btn-xs.btn-icon`, 32 × 32): control fill and border with a
 * single glyph; [description] names the action for assistive technology.
 */
@Composable
fun IconGhostButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.GHOST,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (bg, border, fg) = toneColors(tone, hovered)
    Box(
        modifier = modifier
            .size(32.dp)
            .focusRing(focused)
            .background(bg, KrtShape)
            .border(1.dp, border, KrtShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = fg)
    }
}

/**
 * Checkbox (`input[type=checkbox]`): control fill and border with a 2 dp radius; checked is orange
 * with a black tick.
 */
@Composable
fun KrtCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelStyle: TextStyle? = null,
    labelMaxLines: Int = Int.MAX_VALUE,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .focusRing(focused)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
    ) {
        CheckboxBox(checked, enabled)
        Spacer(Modifier.width(10.dp))
        DisableSelection {
            Text(
                label,
                style = labelStyle ?: MaterialTheme.typography.bodyMedium,
                color = if (enabled) Krt.Gray1 else Krt.TextMuted,
                maxLines = labelMaxLines,
            )
        }
    }
}

/** A drawn tick mark in [color] — crisp at any size and never an emoji glyph. */
@Composable
fun CheckMark(color: Color, size: Dp = 11.dp) {
    Canvas(Modifier.size(size)) {
        val s = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(color, Offset(0.10f * w, 0.52f * h), Offset(0.40f * w, 0.84f * h), s, StrokeCap.Round)
        drawLine(color, Offset(0.40f * w, 0.84f * h), Offset(0.92f * w, 0.16f * h), s, StrokeCap.Round)
    }
}

/** The 19 dp box of a [KrtCheckbox], also used alone where the label lives elsewhere. */
@Composable
fun CheckboxBox(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(19.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(if (checked) Krt.Orange else Krt.Control, KrtSmallShape)
            .border(1.dp, if (checked) Krt.Orange else Krt.ControlBorder, KrtSmallShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) CheckMark(Krt.Black)
    }
}

/** The round mark of a radio option (`input[type=radio]`): control fill and border; checked is orange with a black dot. */
@Composable
fun RadioMark(selected: Boolean, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .alpha(if (enabled || selected) 1f else DISABLED_ALPHA)
            .background(if (selected) Krt.Orange else Krt.Control, CircleShape)
            .border(1.dp, if (selected) Krt.Orange else Krt.ControlBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).background(Krt.Black, CircleShape))
    }
}

/**
 * On/off switch (`.krt-switch`): a 44 × 24 track on the control fill with a gray knob on the left; on is
 * an orange track with a black knob on the right, so the state reads from position and colour.
 */
@Composable
fun KrtSwitch(
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
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 24.dp)
                .focusRing(focused)
                .background(if (checked) Krt.Orange else Krt.Control, KrtShape)
                .border(1.dp, if (checked) Krt.Orange else Krt.ControlBorder, KrtShape),
        ) {
            Box(
                Modifier
                    .offset(x = if (checked) 24.dp else 3.dp, y = 3.dp)
                    .size(16.dp)
                    .background(if (checked) Krt.Black else Krt.Gray1, KrtSmallShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        DisableSelection {
            Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Krt.Gray1)
        }
    }
}

/**
 * Segmented switch (`.krt-segmented`) for a period, status, view or language choice: one framed block on
 * the control fill, segments split by the control border; the selected one gets the orange tint, an
 * orange frame, a 3 dp orange underline and white text — never a filled orange block.
 */
@Composable
fun KrtSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .background(Krt.Control, KrtShape)
            .border(1.dp, Krt.ControlBorder, KrtShape)
            .clip(KrtShape),
    ) {
        options.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Krt.ControlBorder))
            SegmentItem(label, index == selectedIndex, compact) { onSelect(index) }
        }
    }
}

/** One segment of [KrtSegmented]. */
@Composable
private fun SegmentItem(label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        selected -> Krt.ControlSelected
        hovered -> Krt.ControlHover
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .heightIn(min = if (compact) 30.dp else 42.dp)
            .background(bg)
            .drawWithContent {
                drawContent()
                if (selected) {
                    val w = 1.dp.toPx()
                    drawRect(Krt.Orange, style = Stroke(w), topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w))
                    val u = 3.dp.toPx()
                    drawRect(Krt.Orange, topLeft = Offset(0f, size.height - u), size = Size(size.width, u))
                }
            }
            .focusRing(focused, offset = (-3).dp)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .hoverable(interaction)
            .pointerHoverIcon(if (selected) PointerIcon.Default else PointerIcon.Hand)
            .padding(horizontal = if (compact) 10.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected || hovered) Krt.White else Krt.Gray1,
        )
    }
}

/** Count next to a title (`.count-badge`): badge fill, white bold tabular digits. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 26.dp)
            .background(Krt.CountBadge, KrtShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", style = KrtDataStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = Krt.White)
    }
}

/** Indeterminate loading ring (`.krt-spinner`): a faint orange track with an orange arc. */
@Composable
fun KrtSpinner(size: Dp = 20.dp) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        color = Krt.Orange,
        trackColor = Krt.Orange.copy(alpha = 0.25f),
        strokeWidth = 2.dp,
    )
}

/** A spinner beside a status line (`.krt-loading-indicator`), for work without a known size. */
@Composable
fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KrtSpinner()
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
    }
}

/**
 * Determinate progress bar (a gauge, so square): bar-surface track with a hairline, filled orange by
 * [done]/[total]; a zero or unknown [total] renders an empty track.
 */
@Composable
fun KrtProgressBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    KrtProgressBar(if (total > 0) done.toFloat() / total else 0f, modifier)
}

/** Fraction-driven variant for callers whose progress isn't a simple int ratio (e.g. bytes). */
@Composable
fun KrtProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Krt.Bar)
            .border(1.dp, Krt.Hairline),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Krt.Orange))
    }
}

/**
 * Transient notification toast (`.notification-toast`): panel surface, accent frame with the two
 * diagonal corner brackets and the outer accent bloom; [error] swaps orange for danger. The caller
 * positions and auto-dismisses it.
 */
@Composable
fun KrtToast(title: String, message: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val accent = if (error) Krt.Danger else Krt.Orange
    Box(
        modifier = modifier
            .widthIn(max = 400.dp)
            .bloom(accent, 20.dp, 0.2f)
            .background(Krt.Panel, KrtShape)
            .border(1.dp, accent, KrtShape)
            .drawWithContent {
                drawContent()
                drawHudBrackets(accent, 10.dp)
            }
            .padding(22.dp),
    ) {
        Column {
            Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = if (error) Krt.DangerText else Krt.Orange)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
        }
    }
}

/** Small HUD status dot (`.status-dot`) — a tiny solid square. */
@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color))
}

/** A status dot followed by a note — the idle, OK, warning and error hints under fields. */
@Composable
fun DotNote(dot: Color, text: String, color: Color = Krt.Gray1, maxLines: Int = Int.MAX_VALUE) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(dot)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines)
    }
}

/**
 * Always-visible app footer (`.krt-footer` with the Fan-Kit band): bar surface with a hairline on top,
 * the unaltered "Made by the Community" logo beside the required trademark notice, and the manual and
 * GitHub links. [logo] must be the white-ink variant so it reads on the dark surface.
 */
@Composable
fun CommunityDisclaimerFooter(logo: Painter, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Krt.Bar)
            .drawBehind { drawLine(Krt.Hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 16.dp, end = 28.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painter = logo, contentDescription = "Made by the Community", modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Legal.UNAFFILIATED, style = MaterialTheme.typography.bodySmall, color = Krt.TextMuted)
            Text(Legal.TRADEMARK_NOTICE, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
        }
        Spacer(Modifier.width(16.dp))
        FooterTextLink(strings.footerManual, WIKI_URL)
        Spacer(Modifier.width(16.dp))
        FooterGithubLink(REPO_URL)
    }
}

/** Public repo + wiki URLs for the footer quick links. */
private const val REPO_URL = "https://github.com/krt-profit/basetool-sc-extractor"
private const val WIKI_URL = "https://github.com/krt-profit/basetool-sc-extractor/wiki"

/** Footer text link: Grau-1 label, hand cursor and orange underline on hover, opens [url] in the browser. */
@Composable
private fun FooterTextLink(text: String, url: String) {
    val canBrowse = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(
            textDecoration = if (canBrowse && hovered) TextDecoration.Underline else TextDecoration.None,
        ),
        color = if (canBrowse && (hovered || focused)) Krt.Orange else Krt.Gray1,
        modifier = if (canBrowse) {
            Modifier
                .focusRing(focused)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
                }
        } else {
            Modifier
        },
    )
}

/** Footer GitHub mark: Grau-1 silhouette, hand cursor and orange tint on hover, opens [url] in the browser. */
@Composable
private fun FooterGithubLink(url: String) {
    val canBrowse = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Icon(
        painter = painterResource(Res.drawable.github),
        contentDescription = "GitHub",
        tint = if (canBrowse && (hovered || focused)) Krt.Orange else Krt.Gray1,
        modifier = Modifier
            .size(20.dp)
            .then(
                if (canBrowse) {
                    Modifier
                        .focusRing(focused)
                        .hoverable(interaction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                            scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
                        }
                } else {
                    Modifier
                },
            ),
    )
}

/** The HUD scrollbar (`::-webkit-scrollbar`): a slim Grau-2 thumb that lights up orange under the pointer. */
val KrtScrollbarStyle = ScrollbarStyle(
    minimalHeight = 32.dp,
    thickness = 8.dp,
    shape = KrtSmallShape,
    hoverDurationMillis = 200,
    unhoverColor = Krt.ControlBorder,
    hoverColor = Krt.OrangeHover,
)

/** A [KrtScrollbarStyle] scrollbar for a scrolling column. */
@Composable
fun KrtVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.padding(vertical = 2.dp, horizontal = 2.dp), style = KrtScrollbarStyle)
}

/** A [KrtScrollbarStyle] scrollbar for a lazy list. */
@Composable
fun KrtVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.padding(vertical = 2.dp, horizontal = 2.dp), style = KrtScrollbarStyle)
}

/** A [KrtScrollbarStyle] scrollbar for a lazy grid. */
@Composable
fun KrtVerticalScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.padding(vertical = 2.dp, horizontal = 2.dp), style = KrtScrollbarStyle)
}