package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.Lang
import com.basetool.bpextractor.ui.i18n.LocalStrings

/** The three top-level destinations of the Top-Tabs launcher (design spec §3). */
enum class MainTab { START, BLUEPRINTS, REFINERY }

/**
 * The title-bar DE/EN language toggle (design spec §1, locked decision "German default, full EN
 * parity"). Two compact labels separated by a hairline pipe; the active language is orange.
 */
@Composable
fun LanguageToggle(lang: Lang, onSelect: (Lang) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        LangItem("DE", lang == Lang.DE) { onSelect(Lang.DE) }
        Text("|", style = MaterialTheme.typography.labelLarge, color = Krt.Gray3)
        LangItem("EN", lang == Lang.EN) { onSelect(Lang.EN) }
    }
}

/** One language label of the toggle: orange when active, neutral with orange hover otherwise. */
@Composable
private fun LangItem(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            active -> Krt.Orange
            hovered -> Krt.OrangeHover
            else -> Krt.Gray2
        },
        modifier = Modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** Visual state of one inline-stepper pill: completed (green ✔), the current step, or upcoming. */
private enum class StepState { DONE, ACTIVE, TODO }

/**
 * The single navigation band of the redesign (`REDESIGN_IMPLEMENTATION.md` §2): the three
 * top-level tabs on the left and — only while a workflow tab is active — the workflow's inline
 * stepper on the right. Replaces the former separate `TabBar` + `StepperBar` stack. Completed
 * steps render as green ✔ squares, the active one orange, upcoming ones grey; steps up to
 * [maxReached] are clickable for going back (forward navigation stays CTA-only). The stepper row
 * scrolls horizontally so it stays usable at the 640dp minimum window width.
 *
 * [stepLabels] is null on the START tab (no workflow → no stepper).
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
            modifier = Modifier.fillMaxWidth().height(46.dp).background(Krt.Gray4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CmdTab(strings.tabStart, tab == MainTab.START) { onTab(MainTab.START) }
            CmdTab(strings.tabBlueprints, tab == MainTab.BLUEPRINTS) { onTab(MainTab.BLUEPRINTS) }
            CmdTab(strings.tabRefinery, tab == MainTab.REFINERY) { onTab(MainTab.REFINERY) }
            Spacer(Modifier.weight(1f))
            if (stepLabels != null) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(end = 12.dp),
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
                            Box(Modifier.width(12.dp).height(1.dp).background(Krt.Gray3))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
    }
}

/**
 * One tab of the [CommandStrip]: Lato-Bold UPPERCASE label filling the 46dp band height, a 2dp
 * orange underline plus a faint orange wash when active, orange hover tint otherwise.
 */
@Composable
private fun CmdTab(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fg = when {
        active -> Krt.Orange
        hovered -> Krt.OrangeHover
        else -> Krt.Gray1
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            // Pin the tab to its intrinsic (label) width — without this the underline's
            // fillMaxWidth makes the first tab swallow the whole strip.
            .width(IntrinsicSize.Max)
            .background(if (active) Krt.Orange.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 17.dp), contentAlignment = Alignment.Center) {
            Text(label.uppercase(), style = MaterialTheme.typography.headlineSmall, color = fg)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) Krt.Orange else Color.Transparent),
        )
    }
}

/**
 * One inline-stepper entry of the [CommandStrip]: a 19dp square carrying the step number (or a ✔
 * once done) plus a short UPPERCASE label. DONE = success-filled square with black check, ACTIVE
 * = orange, TODO = grey; [enabled] steps (back-navigation up to the furthest reached) react to
 * clicks and hover.
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
    val accent = when (state) {
        StepState.ACTIVE -> Krt.Orange
        StepState.DONE -> Krt.Success
        StepState.TODO -> Krt.Gray2
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(19.dp)
                .background(if (state == StepState.DONE) Krt.Success else Krt.Gray4),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state == StepState.DONE) "✓" else "$number",
                style = MaterialTheme.typography.labelMedium,
                color = when (state) {
                    StepState.DONE -> Krt.Black
                    StepState.ACTIVE -> Krt.Orange
                    StepState.TODO -> Krt.Gray2
                },
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                hovered && enabled -> Krt.OrangeHover
                state == StepState.TODO -> Krt.Gray2
                else -> Krt.White
            },
        )
    }
}
