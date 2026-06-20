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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.refinery.PanelValues
import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FootNote
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings
import java.io.File
import kotlin.math.roundToInt

/**
 * §5.4 Review & Bestätigung on the [StepScaffold] (desktop variant — master-data matching
 * happens later in the basetool frontend): head badges (`SETUP` + layout %), the warning banner,
 * the four order header cards, and the goods table filling the rest height with derived
 * confidence (percent + dot at the 0.90/0.75 thresholds, accessible tints, flagged rows with a
 * coloured 3dp edge). Header values and goods rows are user-correctable in place (✎): a
 * corrected row exports at confidence 1.0 with an ↺ to restore the machine read — the export
 * writes [RefineryUiState.reviewedOrder]. The "stays manual" note and the export CTA sit in the
 * pinned footer.
 */
@Composable
fun ReviewStep(state: RefineryUiState) {
    val strings = LocalStrings.current
    val result = state.result ?: return
    val order = state.reviewedOrder ?: return
    val machineGoods = result.extract.orders.first().goods
    val validated = result.validated
    var editingRow by remember { mutableStateOf<Int?>(null) }
    // Header cards own their inline-edit state; they report it up here so "Continue to export" can
    // warn about an editor that is still open — its typing only reaches the export once ✓ is hit.
    val openHeaderEdits = remember { mutableStateListOf<String>() }
    var confirmLeave by remember { mutableStateOf(false) }
    val hasOpenEdit = editingRow != null || openHeaderEdits.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        StepScaffold(
            overline = strings.rfStepOverline(4),
            title = strings.rfReviewTitle,
            subtitle = strings.rfReviewSubtitle,
            scrollBody = false,
            headRight = {
                // Head badges: panel type + layout confidence.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KrtChip(order.panelType, color = Krt.Orange, border = Krt.Orange)
                    KrtChip(strings.rfBadgeLayout((order.layoutConfidence * 100).roundToInt()))
                }
            },
            footer = {
                GhostButton(strings.back, onClick = { state.goTo(2) })
                Spacer(Modifier.weight(1f))
                FootNote(strings.rfManualNote)
                // Review only finalises the data; choosing send-vs-save-JSON happens on the export step.
                // An open inline editor still holds uncommitted typing — warn before discarding it.
                CtaButton(
                    strings.rfCtaToExport,
                    onClick = { if (hasOpenEdit) confirmLeave = true else state.goTo(4) },
                )
            },
        ) {
            // Warning banner: count + list of validation findings. Findings the user's corrections
            // resolved (re-checked deterministically) show as settled instead of still-open.
            val resolved = state.resolvedWarnings
            val open = validated.warnings.size - resolved.size
            if (validated.warnings.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(Krt.Success)
                    Text(strings.rfNoFlags, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }
            } else {
                AlertBox(if (open == 0) Krt.Success else Krt.Warning) {
                    Text(
                        if (open == 0) strings.rfAllWarningsResolved else strings.rfFlaggedWarnings(open),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                    validated.warnings.forEach { warning ->
                        val isResolved = warning in resolved
                        Text(
                            "• " + strings.rfWarningLabel(warning.name) +
                                if (isResolved) " — ${strings.rfWarningResolved}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isResolved) Krt.Success else Krt.Gray2,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Four order-header cards, each value correctable in place.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderCard(
                    title = strings.rfHdrLocation,
                    value = order.rawLocationName,
                    editPrefill = order.rawLocationName ?: "",
                    onCommit = { text ->
                        state.editHeader { it.copy(rawLocationName = text.trim().ifBlank { null }) }
                        true
                    },
                    onEditingChange = { editing -> openHeaderEdits.track(strings.rfHdrLocation, editing) },
                    modifier = Modifier.weight(1f),
                )
                HeaderCard(
                    title = strings.rfHdrMethod,
                    value = order.rawMethodName,
                    editPrefill = order.rawMethodName ?: "",
                    onCommit = { text ->
                        state.editHeader { it.copy(rawMethodName = text.trim().ifBlank { null }) }
                        true
                    },
                    onEditingChange = { editing -> openHeaderEdits.track(strings.rfHdrMethod, editing) },
                    modifier = Modifier.weight(1f),
                )
                HeaderCard(
                    title = strings.rfHdrCost,
                    value = order.expenses?.let { "%,.0f aUEC".format(it) },
                    editPrefill = order.expenses?.let { plainNumber(it) } ?: "",
                    onCommit = { text ->
                        val trimmed = text.trim()
                        when {
                            trimmed.isBlank() -> {
                                state.editHeader { it.copy(expenses = null) }
                                true
                            }
                            else -> PanelValues.toCost(trimmed.replace(",", ""))?.let { cost ->
                                state.editHeader { it.copy(expenses = cost) }
                                true
                            } ?: false
                        }
                    },
                    onEditingChange = { editing -> openHeaderEdits.track(strings.rfHdrCost, editing) },
                    modifier = Modifier.weight(1f),
                )
                HeaderCard(
                    title = strings.rfHdrDuration,
                    value = order.durationMinutes?.let { "${it / 60}h ${it % 60}m" },
                    editPrefill = order.durationMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "",
                    onCommit = { text ->
                        val trimmed = text.trim()
                        when {
                            trimmed.isBlank() -> {
                                state.editHeader { it.copy(durationMinutes = null) }
                                true
                            }
                            else -> PanelValues.toDurationMinutes(trimmed)?.let { minutes ->
                                state.editHeader { it.copy(durationMinutes = minutes) }
                                true
                            } ?: false
                        }
                    },
                    onEditingChange = { editing -> openHeaderEdits.track(strings.rfHdrDuration, editing) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))

            // Goods table: one sticky header row, the body scrolls and fills the rest height.
            Column(modifier = Modifier.weight(1f).fillMaxWidth().hudBox()) {
                GoodsHeaderRow()
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    order.goods.forEach { good ->
                        if (editingRow == good.rowIndex) {
                            GoodsEditRow(
                                displayed = good,
                                onCommit = { edited ->
                                    val machine = machineGoods.first { it.rowIndex == good.rowIndex }
                                    state.editGood(machine, edited)
                                    editingRow = null
                                },
                                onCancel = { editingRow = null },
                            )
                        } else {
                            GoodsRow(
                                good = good,
                                edited = state.editedGoods.containsKey(good.rowIndex),
                                onEdit = { editingRow = good.rowIndex },
                                onRevert = { state.revertGood(good.rowIndex) },
                            )
                        }
                    }
                }
            }
        }

        // Guard the forward navigation: an open inline editor would silently lose its typing.
        if (confirmLeave) {
            UnsavedChangesOverlay(
                onBack = { confirmLeave = false },
                onDiscard = {
                    confirmLeave = false
                    state.goTo(4)
                },
            )
        }
    }
}

/** Track one header card's open/closed inline-edit state in the set-like list (dedup on open). */
private fun SnapshotStateList<String>.track(key: String, editing: Boolean) {
    if (editing) {
        if (key !in this) add(key)
    } else {
        remove(key)
    }
}

/** Default export target: `RefineryExtract.json` next to the screenshots (or Documents). */
internal fun defaultExportPath(state: RefineryUiState): String {
    val base = state.folder.takeIf { it.isNotBlank() && File(it).isDirectory }
        ?: File(System.getProperty("user.home"), "Documents").absolutePath
    return File(base, "RefineryExtract.json").absolutePath
}

/** A double rendered the way a user would type it back ("48928", not "48928.0"). */
private fun plainNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** Width of the trailing per-row actions area (✎ / ↺ resp. ✓ / ✕). */
private val ActionsWidth = 52.dp

/**
 * One order-header card: green ✔ when read, amber ⚠ + "fehlt" when the read came up empty.
 * The ✎ switches the value into an inline edit field; [onCommit] returns false when the typed
 * text does not parse (the field then shows the error state and stays open).
 */
@Composable
private fun HeaderCard(
    title: String,
    value: String?,
    editPrefill: String,
    onCommit: (String) -> Boolean,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalStrings.current
    var editing by remember { mutableStateOf(false) }
    // Surface the open/closed editor state so the step can warn before discarding uncommitted text.
    LaunchedEffect(editing) { onEditingChange(editing) }
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val ok = value != null
    val accent = if (ok) Krt.Success else Krt.Warning
    Row(modifier = modifier.hudBox().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(10.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Krt.Gray2,
                    modifier = Modifier.weight(1f),
                )
                if (!editing) {
                    GlyphButton("✎", Krt.Gray2, description = strings.rfEditRow) {
                        text = editPrefill
                        invalid = false
                        editing = true
                    }
                }
            }
            Spacer(Modifier.padding(top = 4.dp))
            if (editing) {
                val submit = { if (onCommit(text)) editing = false else invalid = true }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InlineEditField(
                        value = text,
                        onChange = {
                            text = it
                            invalid = false
                        },
                        isError = invalid,
                        modifier = Modifier.weight(1f),
                        onSubmit = submit,
                        onCancel = { editing = false },
                    )
                    ApplyButton(label = null, enabled = true, description = strings.rfEditApply, onClick = submit)
                    GlyphButton("✕", Krt.Gray2, description = strings.rfEditCancel) { editing = false }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (ok) "✓" else "⚠", style = MaterialTheme.typography.bodyMedium, color = accent)
                    Text(
                        value ?: strings.rfMissingValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ok) Krt.Gray1 else Krt.Gray2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoodsHeaderRow() {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().background(Krt.SurfaceInput).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell(strings.rfColMaterial, 2.4f)
        HeaderCell(strings.rfColQuality, 1f)
        HeaderCell(strings.rfColInput, 1f)
        HeaderCell(strings.rfColYield, 1f)
        HeaderCell(strings.rfColRefine, 0.8f)
        HeaderCell(strings.rfColConfidence, 1.2f)
        Spacer(Modifier.width(ActionsWidth))
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Krt.Gray2,
        modifier = Modifier.weight(weight),
    )
}

/** One goods row; rows below the 0.90 threshold get the coloured 3px left border (§5.4). */
@Composable
private fun GoodsRow(
    good: RefineryExtractGood,
    edited: Boolean,
    onEdit: () -> Unit,
    onRevert: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = confidenceColors(good.confidence)
    val flagged = good.confidence < 0.90
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(3.dp).fillMaxHeight().background(if (flagged) colors.dot else Color.Transparent),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 9.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DataCell(good.rawMaterialName, 2.4f, Krt.Gray1)
            DataCell(good.quality?.toString() ?: "—", 1f, Krt.Gray1)
            DataCell(good.inputQuantity?.toString() ?: "—", 1f, Krt.Gray1)
            DataCell(good.outputQuantity?.let { "+$it" } ?: "—", 1f, if (good.outputQuantity != null) Krt.Success else Krt.Gray2)
            DataCell(if (good.refine) "ON" else "OFF", 0.8f, if (good.refine) Krt.Gray1 else Krt.Gray2)
            Row(modifier = Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(colors.dot)
                Text(
                    "${(good.confidence * 100).roundToInt()} %",
                    style = KrtDataStyle,
                    color = colors.text,
                )
                if (edited) {
                    Text(
                        "✎ " + strings.rfEditedTag.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Krt.Gray2,
                        maxLines = 1,
                    )
                }
            }
            Row(
                modifier = Modifier.width(ActionsWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (edited) {
                    GlyphButton("↺", Krt.Gray2, description = strings.rfEditRevert, onClick = onRevert)
                }
                GlyphButton("✎", Krt.Gray2, description = strings.rfEditRow, onClick = onEdit)
            }
        }
    }
}

/**
 * The in-place editor of one goods row: orange left edge, free text for the material, numeric
 * fields for quality/input/yield (blank = unreadable/absent → null), a clickable ON/OFF toggle,
 * ✓ commits (Enter) and ✕ discards (Escape). ✓ stays disabled while a number does not parse —
 * the offending field shows the danger border.
 */
@Composable
private fun GoodsEditRow(
    displayed: RefineryExtractGood,
    onCommit: (RefineryExtractGood) -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(displayed.rawMaterialName) }
    var quality by remember { mutableStateOf(displayed.quality?.toString() ?: "") }
    var input by remember { mutableStateOf(displayed.inputQuantity?.toString() ?: "") }
    var output by remember { mutableStateOf(displayed.outputQuantity?.toString() ?: "") }
    var refine by remember { mutableStateOf(displayed.refine) }

    val nameOk = name.isNotBlank()
    val qualityOk = quality.isBlank() || quality.trim().toIntOrNull()?.takeIf { it >= 0 } != null
    val inputOk = input.isBlank() || input.trim().toLongOrNull()?.takeIf { it >= 0 } != null
    val outputOk = output.isBlank() || output.trim().toLongOrNull()?.takeIf { it >= 0 } != null
    val valid = nameOk && qualityOk && inputOk && outputOk
    val submit = {
        if (valid) {
            onCommit(
                displayed.copy(
                    rawMaterialName = name.trim(),
                    quality = quality.trim().toIntOrNull(),
                    inputQuantity = input.trim().toLongOrNull(),
                    outputQuantity = output.trim().toLongOrNull(),
                    refine = refine,
                ),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Krt.SurfaceInput.copy(alpha = 0.35f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Krt.Orange))
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 9.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditCell(name, { name = it }, 2.4f, !nameOk, submit, onCancel)
            EditCell(quality, { quality = it }, 1f, !qualityOk, submit, onCancel)
            EditCell(input, { input = it }, 1f, !inputOk, submit, onCancel)
            EditCell(output, { output = it }, 1f, !outputOk, submit, onCancel)
            Box(Modifier.weight(0.8f)) {
                Text(
                    if (refine) "ON" else "OFF",
                    style = KrtDataStyle,
                    color = if (refine) Krt.Orange else Krt.Gray2,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { refine = !refine }
                        .background(Krt.SurfaceInput)
                        .border(1.dp, if (refine) Krt.Orange else Krt.Gray3)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            // Apply lands in the confidence column (otherwise empty mid-edit) as a filled button —
            // far louder than the old bare ✓ glyph, so a correction doesn't get left un-applied.
            Box(Modifier.weight(1.2f), contentAlignment = Alignment.CenterStart) {
                ApplyButton(label = strings.rfEditApply, enabled = valid, description = strings.rfEditApply, onClick = submit)
            }
            Row(
                modifier = Modifier.width(ActionsWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                GlyphButton("✕", Krt.Gray2, description = strings.rfEditCancel) { onCancel() }
            }
        }
    }
}

/** A compact inline table edit field in the data style; Enter commits, Escape cancels. */
@Composable
private fun RowScope.EditCell(
    value: String,
    onChange: (String) -> Unit,
    weight: Float,
    isError: Boolean,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.weight(weight).padding(end = 8.dp)) {
        InlineEditField(
            value = value,
            onChange = onChange,
            isError = isError,
            modifier = Modifier.fillMaxWidth(),
            onSubmit = onSubmit,
            onCancel = onCancel,
        )
    }
}

/** The shared single-line edit primitive: dark fill, hairline/danger border, orange caret. */
@Composable
private fun InlineEditField(
    value: String,
    onChange: (String) -> Unit,
    isError: Boolean,
    modifier: Modifier,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = KrtDataStyle.copy(color = if (isError) Krt.Danger else Krt.Gray1),
        cursorBrush = SolidColor(Krt.Orange),
        modifier = modifier
            .background(Krt.SurfaceInput)
            .border(1.dp, if (isError) Krt.Danger else Krt.Gray3)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.Enter -> {
                        onSubmit()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                        onCancel()
                        true
                    }
                    else -> false
                }
            },
    )
}

/** A bare glyph action (✎ ✓ ✕ ↺) — quiet by default, the glyph colour carries the meaning. */
@Composable
private fun GlyphButton(
    glyph: String,
    color: Color,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
) {
    Text(
        glyph,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) color else Krt.Gray3,
        modifier = Modifier
            .semantics { description?.let { contentDescription = it } }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * The prominent "apply this correction" action (the commit of a ✎ edit): a filled Success-green
 * button, optionally labelled. Deliberately louder than the quiet glyph actions around it —
 * committing is the step that actually changes the export, and the old bare ✓ glyph was too easy to
 * skip (a typed-but-un-applied correction silently fell back to the machine read).
 */
@Composable
private fun ApplyButton(
    label: String?,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val fg = if (enabled) Krt.Black else Krt.Gray2
    Row(
        modifier = Modifier
            .semantics { contentDescription = description }
            .background(if (enabled) Krt.Success else Krt.Gray3)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("✓", style = MaterialTheme.typography.bodyMedium, color = fg)
        if (label != null) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * §5.4 guard before leaving for the export step with an inline editor still open: a KRT scrim modal
 * (never a native dialog), amber Warning accent. Either go back and apply (the safe primary CTA), or
 * discard the open editor's not-yet-committed typing and continue. Already-committed corrections are
 * untouched — only the one open editor's uncommitted text is at stake.
 */
@Composable
private fun UnsavedChangesOverlay(onBack: () -> Unit, onDiscard: () -> Unit) {
    val strings = LocalStrings.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Krt.Black.copy(alpha = 0.8f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val swallow = remember { MutableInteractionSource() }
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .drawBehind {
                    val grow = 28.dp.toPx()
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Krt.Warning.copy(alpha = 0.18f), Color.Transparent),
                            center = center,
                            radius = size.maxDimension / 2f + grow,
                        ),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + 2f * grow, size.height + 2f * grow),
                    )
                }
                .background(Krt.Black.copy(alpha = 0.97f))
                .border(1.dp, Krt.Warning)
                .clickable(interactionSource = swallow, indication = null, onClick = {}),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4)
                    .drawBehind {
                        drawLine(Krt.Warning, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⚠  " + strings.rfUnsavedTitle.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Warning,
                )
            }
            Text(
                strings.rfUnsavedBody,
                style = MaterialTheme.typography.bodyMedium,
                color = Krt.Gray1,
                modifier = Modifier.padding(18.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4.copy(alpha = 0.55f))
                    .drawBehind { drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(strings.rfUnsavedDiscard, onClick = onDiscard)
                Spacer(Modifier.weight(1f))
                CtaButton(strings.rfUnsavedBack, onClick = onBack)
            }
        }
    }
}

@Composable
private fun RowScope.DataCell(text: String, weight: Float, color: Color) {
    Text(text, style = KrtDataStyle, color = color, modifier = Modifier.weight(weight), maxLines = 1)
}
