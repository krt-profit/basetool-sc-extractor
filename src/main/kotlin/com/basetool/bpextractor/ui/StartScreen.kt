package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.BlueprintExtractor
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.refinery.KrtChip

/**
 * The launcher (design spec §3, "Start" tab): the greeting banner (the one place it remains after
 * the redesign), a "choose a workflow" overline and one card per workflow. The cards grow to fill
 * the window height (`REDESIGN_IMPLEMENTATION.md` §4.1) — each card earns that height with a
 * centred bullet block (input / output / runs-locally) between its description and its pinned
 * open action, so there is no padded void. The fan-kit footer is global and lives outside.
 */
@Composable
fun StartScreen(
    update: UpdateUiState,
    onUpdateInstall: () -> Unit,
    onUpdateDismiss: () -> Unit,
    onOpen: (MainTab) -> Unit,
) {
    val strings = LocalStrings.current
    val honeycomb = rememberHoneycombPainter()
    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            GreetingHeader(title = strings.startTitle, subtitle = strings.startSubtitle)
            Spacer(Modifier.height(14.dp))
            // The update offer (hidden unless a newer GitHub release was found at startup).
            if (update !is UpdateUiState.Hidden) {
                UpdateBanner(state = update, onInstall = onUpdateInstall, onDismiss = onUpdateDismiss)
                Spacer(Modifier.height(14.dp))
            }
            Text(
                strings.startChooseWorkflow.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Krt.Gray2,
            )
            Spacer(Modifier.height(10.dp))

            // The two workflow cards fill the remaining height (audit fix: no bottom void).
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WorkflowCard(
                    title = strings.bpCardTitle,
                    description = strings.bpCardDesc,
                    bullets = strings.bpCardBullets,
                    inputChip = strings.bpCardInputHint,
                    openLabel = strings.startOpen,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onOpen(MainTab.BLUEPRINTS) }
                WorkflowCard(
                    title = strings.rfCardTitle,
                    description = strings.rfCardDesc,
                    bullets = strings.rfCardBullets,
                    inputChip = strings.rfCardInputHint,
                    openLabel = strings.startOpen,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onOpen(MainTab.REFINERY) }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KrtChip(strings.unofficialChip)
                KrtChip("v${BlueprintExtractor.TOOL_VERSION}")
                Text(strings.startLocalNote, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
            }
        }
    }
}

/**
 * One full-height workflow card (`REDESIGN_IMPLEMENTATION.md` §4.1): head (title), description,
 * a vertically centred bullet block in the growing middle zone (hairline-framed), and a pinned
 * foot row with the input chip and the open action. The whole card is clickable; hovering lights
 * the hairline (and the open label) orange.
 */
@Composable
private fun WorkflowCard(
    title: String,
    description: String,
    bullets: List<String>,
    inputChip: String,
    openLabel: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .hudBox(
                fill = if (hovered) Krt.Orange.copy(alpha = 0.05f) else Krt.Gray4.copy(alpha = 0.5f),
                hairline = if (hovered) Krt.Orange else Krt.Gray3,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(18.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = Krt.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)

        // The growing middle zone: bullets centred between hairlines (the height is earned).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
            Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                bullets.forEach { bullet ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(6.dp).background(Krt.Orange))
                        Text(bullet, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Gray3))
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KrtChip(inputChip)
            Spacer(Modifier.weight(1f))
            GhostButton(openLabel, onClick = onOpen)
        }
    }
}
