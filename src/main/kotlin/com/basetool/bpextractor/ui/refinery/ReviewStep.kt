package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.FootNote
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtDataStyle
import com.basetool.bpextractor.ui.PickerMode
import com.basetool.bpextractor.ui.PickerRequest
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.math.roundToInt

/**
 * §5.4 Review & Bestätigung on the [StepScaffold] (desktop variant — master-data matching
 * happens later in the basetool frontend): head badges (`SETUP` + layout %), the warning banner,
 * the four order header cards, and the goods table filling the rest height with derived
 * confidence (percent + dot at the 0.90/0.75 thresholds, accessible tints, flagged rows with a
 * coloured 3dp edge). The "stays manual" note and the export CTA sit in the pinned footer.
 */
@Composable
fun ReviewStep(state: RefineryUiState, appScope: CoroutineScope, onPicker: (PickerRequest) -> Unit) {
    val strings = LocalStrings.current
    val result = state.result ?: return
    val order = result.extract.orders.first()
    val validated = result.validated

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
            val exportError = state.exportError
            if (exportError != null) {
                Text(exportError, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
            } else {
                FootNote(strings.rfManualNote)
            }
            CtaButton(
                strings.rfCtaExport,
                onClick = {
                    onPicker(
                        PickerRequest(
                            mode = PickerMode.SAVE_FILE,
                            title = strings.rfPickerExportTitle,
                            confirmLabel = strings.rfPickerExportConfirm,
                            initialPath = defaultExportPath(state),
                        ) { path -> state.export(appScope, File(path), strings) },
                    )
                },
            )
        },
    ) {
        // Warning banner: count + list of validation findings.
        if (validated.warnings.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(Krt.Success)
                Text(strings.rfNoFlags, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
            }
        } else {
            AlertBox(Krt.Warning) {
                Text(
                    strings.rfFlaggedWarnings(validated.warnings.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Krt.Gray1,
                )
                validated.warnings.forEach { warning ->
                    Text(
                        "• " + strings.rfWarningLabel(warning.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = Krt.Gray2,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Four order-header cards.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderCard(strings.rfHdrLocation, order.rawLocationName, Modifier.weight(1f))
            HeaderCard(strings.rfHdrMethod, order.rawMethodName, Modifier.weight(1f))
            HeaderCard(
                strings.rfHdrCost,
                order.expenses?.let { "%,.0f aUEC".format(it) },
                Modifier.weight(1f),
            )
            HeaderCard(
                strings.rfHdrDuration,
                order.durationMinutes?.let { "${it / 60}h ${it % 60}m" },
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))

        // Goods table: one sticky header row, the body scrolls and fills the rest height.
        Column(modifier = Modifier.weight(1f).fillMaxWidth().hudBox()) {
            GoodsHeaderRow()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                order.goods.forEach { good -> GoodsRow(good) }
            }
        }
    }
}

/** Default export target: `RefineryExtract.json` next to the screenshots (or Documents). */
private fun defaultExportPath(state: RefineryUiState): String {
    val base = state.folder.takeIf { it.isNotBlank() && File(it).isDirectory }
        ?: File(System.getProperty("user.home"), "Documents").absolutePath
    return File(base, "RefineryExtract.json").absolutePath
}

/** One order-header card: green ✔ when read, amber ⚠ + "fehlt" when the read came up empty. */
@Composable
private fun HeaderCard(title: String, value: String?, modifier: Modifier) {
    val strings = LocalStrings.current
    val ok = value != null
    val accent = if (ok) Krt.Success else Krt.Warning
    Row(modifier = modifier.hudBox().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Gray2)
            Spacer(Modifier.padding(top = 4.dp))
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
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Krt.Gray2,
        modifier = Modifier.weight(weight),
    )
}

/** One goods row; rows below the 0.90 threshold get the coloured 3px left border (§5.4). */
@Composable
private fun GoodsRow(good: RefineryExtractGood) {
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
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DataCell(text: String, weight: Float, color: Color) {
    Text(text, style = KrtDataStyle, color = color, modifier = Modifier.weight(weight), maxLines = 1)
}
