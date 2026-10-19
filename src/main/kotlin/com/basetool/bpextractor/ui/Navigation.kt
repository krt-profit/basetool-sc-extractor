package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.Lang
import com.basetool.bpextractor.ui.i18n.LocalStrings

/** The three top-level destinations of the Top-Tabs launcher (design spec §3). */
enum class MainTab { START, BLUEPRINTS, REFINERY }

/**
 * The title-bar DE/EN language switch (design spec §1, "German default, full EN parity"): a compact
 * segmented control, the active language marked by tint, frame and underline.
 */
@Composable
fun LanguageToggle(lang: Lang, onSelect: (Lang) -> Unit) {
    KrtSegmented(
        options = listOf("DE", "EN"),
        selectedIndex = if (lang == Lang.DE) 0 else 1,
        onSelect = { onSelect(if (it == 0) Lang.DE else Lang.EN) },
        compact = true,
        modifier = Modifier.padding(horizontal = 10.dp),
    )
}

/** Visual state of one inline-stepper entry: completed (green tick), the current step, or upcoming. */
private enum class StepState { DONE, ACTIVE, TODO }

/**
 * The single navigation band (`.tab-nav` on the bar surface): the three top-level tabs and, while a
 * workflow tab is active, its inline stepper. Steps up to [maxReached] are clickable for going back;
 * forward navigation is CTA-only. [stepLabels] is `null` on the START tab.
 */
@Composable
fun CommandStrip(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    stepLabels: List<String>?,
    stepIndex: Int = 0,
    maxReached: Int = 0,
    onStep: (Int) -> Unit = {},
) {
    val strings = LocalStrings.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp).background(Krt.Bar).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CmdTab(strings.tabStart, tab == MainTab.START) { onTab(MainTab.START) }
            CmdTab(strings.tabBlueprints, tab == MainTab.BLUEPRINTS) { onTab(MainTab.BLUEPRINTS) }
            CmdTab(strings.tabRefinery, tab == MainTab.REFINERY) { onTab(MainTab.REFINERY) }
            Spacer(Modifier.weight(1f))
            if (stepLabels != null) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stepLabels.forEachIndexed { i, label ->
                        val state = when {
                            i < stepIndex -> StepState.DONE
                            i == stepIndex -> StepState.ACTIVE
                            else -> StepState.TODO
                        }
                        StepPill(
                            number = i + 1,
                            label = label,
                            state = state,
                            enabled = i <= maxReached && state != StepState.ACTIVE,
                            onClick = { onStep(i) },
                        )
                        if (i < stepLabels.lastIndex) {
                            Box(
                                Modifier
                                    .width(14.dp)
                                    .height(2.dp)
                                    .background(if (i < stepIndex) Krt.Success else Krt.Hairline),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Hairline))
    }
}

/**
 * One tab of the [CommandStrip] (`.tab-nav .tab`): bold UPPERCASE label filling the band height; the
 * active tab is white with a 3 dp orange underline, inactive tabs are muted and turn Grau 1 on hover.
 */
@Composable
private fun CmdTab(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val fg = when {
        active -> Krt.White
        hovered || focused -> Krt.Gray1
        else -> Krt.TextMuted
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(IntrinsicSize.Max)
            .focusRing(focused, offset = (-2).dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = fg)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(if (active) Krt.Orange else Color.Transparent),
        )
    }
}

/**
 * One inline-stepper entry of the [CommandStrip] (`.ablauf .step`): a numbered box — done is a green
 * tick on a green tint, the current step an orange frame with bloom, upcoming a neutral frame — and a
 * short uppercase label; [enabled] steps react to clicks and hover.
 */
@Composable
private fun StepPill(
    number: Int,
    label: String,
    state: StepState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (frame, fill, numberColor) = when (state) {
        StepState.DONE -> Triple(Krt.Success, Krt.Success.copy(alpha = 0.10f), Krt.SuccessText)
        StepState.ACTIVE -> Triple(Krt.Orange, Krt.Canvas, Krt.Orange)
        StepState.TODO -> Triple(Krt.ControlBorder, Krt.Canvas, Krt.Gray1)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusRing(focused, offset = (-2).dp)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 7.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .then(if (state == StepState.ACTIVE) Modifier.bloom(Krt.Orange, 5.dp, 0.3f) else Modifier)
                .background(fill, KrtShape)
                .border(1.dp, frame, KrtShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.DONE) {
                CheckMark(numberColor)
            } else {
                Text("$number", style = MaterialTheme.typography.labelMedium, color = numberColor)
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                hovered && enabled -> Krt.OrangeHover
                state == StepState.ACTIVE -> Krt.Orange
                state == StepState.DONE -> Krt.Gray1
                else -> Krt.TextMuted
            },
        )
    }
}
