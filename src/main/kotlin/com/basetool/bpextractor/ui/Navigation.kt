package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

/**
 * The Top-Tabs bar under the title bar: Start · Blueprints · Refinery. The active tab carries an
 * orange underline + orange label (design spec §3); inactive tabs are neutral and light up on
 * hover. Tab labels come from the active string catalogue.
 */
@Composable
fun TabBar(active: MainTab, onSelect: (MainTab) -> Unit) {
    val strings = LocalStrings.current
    Column {
        Row(modifier = Modifier.fillMaxWidth().background(Krt.Gray4)) {
            TabItem(strings.tabStart, active == MainTab.START) { onSelect(MainTab.START) }
            TabItem(strings.tabBlueprints, active == MainTab.BLUEPRINTS) { onSelect(MainTab.BLUEPRINTS) }
            TabItem(strings.tabRefinery, active == MainTab.REFINERY) { onSelect(MainTab.REFINERY) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
    }
}

/** One tab: Audiowide UPPERCASE label, 2dp orange underline when active, hover tint otherwise. */
@Composable
private fun TabItem(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fg = when {
        active -> Krt.Orange
        hovered -> Krt.OrangeHover
        else -> Krt.Gray1
    }
    Column(
        modifier = Modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) Krt.Orange else Color.Transparent),
        )
    }
}

/**
 * The per-workflow step stepper (design spec §3): numbered squares, done = green check, active =
 * orange, upcoming = grey. Steps up to the highest reached one are clickable for going back;
 * forward navigation happens only through each screen's CTA.
 */
@Composable
fun StepperBar(
    steps: List<String>,
    current: Int,
    maxReached: Int = current,
    onSelect: ((Int) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        steps.forEachIndexed { index, label ->
            val done = index < current
            val active = index == current
            val color = when {
                active -> Krt.Orange
                done -> Krt.Success
                else -> Krt.Gray2
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.let { m ->
                    val select = onSelect
                    if (select != null && index <= maxReached && !active) {
                        m.clickable { select(index) }
                    } else {
                        m
                    }
                },
            ) {
                Box(
                    modifier = Modifier.size(22.dp).background(if (active) Krt.Orange else Krt.Gray4),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (done) "✓" else "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            active -> Krt.Black
                            done -> Krt.Success
                            else -> Krt.Gray2
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
            if (index < steps.lastIndex) {
                Box(Modifier.weight(1f).height(1.dp).background(Krt.Gray3))
            }
        }
    }
}
