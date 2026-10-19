package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.BlueprintExtractor
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.refinery.KrtChip

/**
 * The launcher on the Start tab: the greeting banner and one card per workflow. In a roomy window the
 * cards sit side by side and fill the height; in a small one they stack and the screen scrolls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartScreen(
    update: UpdateUiState,
    onUpdateInstall: () -> Unit,
    onUpdateDismiss: () -> Unit,
    onOpen: (MainTab) -> Unit,
) {
    val strings = LocalStrings.current
    val account = remember { AccountController() }
    val accountScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { account.refresh() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Krt.Canvas)) {
        val compact = maxHeight < 560.dp || maxWidth < 820.dp
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (compact) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            GreetingHeader(title = strings.startTitle, subtitle = strings.startSubtitle)
            Spacer(Modifier.height(14.dp))
            if (update !is UpdateUiState.Hidden) {
                UpdateBanner(state = update, onInstall = onUpdateInstall, onDismiss = onUpdateDismiss)
                Spacer(Modifier.height(14.dp))
            }
            Caption(strings.startChooseWorkflow)
            Spacer(Modifier.height(10.dp))

            val cards: @Composable (Modifier, Boolean) -> Unit = { cardModifier, fill ->
                WorkflowCard(
                    title = strings.bpCardTitle,
                    description = strings.bpCardDesc,
                    bullets = strings.bpCardBullets,
                    inputChip = strings.bpCardInputHint,
                    openLabel = strings.startOpen,
                    fill = fill,
                    modifier = cardModifier,
                ) { onOpen(MainTab.BLUEPRINTS) }
                WorkflowCard(
                    title = strings.rfCardTitle,
                    description = strings.rfCardDesc,
                    bullets = strings.rfCardBullets,
                    inputChip = strings.rfCardInputHint,
                    openLabel = strings.startOpen,
                    fill = fill,
                    modifier = cardModifier,
                ) { onOpen(MainTab.REFINERY) }
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { cards(Modifier.fillMaxWidth(), false) }
            } else {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) { cards(Modifier.weight(1f).fillMaxHeight(), true) }
            }

            Spacer(Modifier.height(14.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                KrtChip(strings.unofficialChip)
                KrtChip("v${BlueprintExtractor.TOOL_VERSION}")
                Text(
                    strings.startLocalNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.TextMuted,
                )
                Spacer(Modifier.weight(1f))
                AccountStatusRow(account)
            }
        }
        if (compact) KrtVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        AccountDisconnectOverlay(account, accountScope)
    }
}

/**
 * One workflow card (`REDESIGN_IMPLEMENTATION.md` §4.1): head (title), description, the bullet block
 * between hairlines, and a foot row with the input chip and the open action; with [fill] the bullet
 * block takes the free height and the foot row is pinned to the bottom. The whole card is clickable;
 * hovering lifts it to the control fill and lights the hairline orange.
 */@Composable
private fun WorkflowCard(
    title: String,
    description: String,
    bullets: List<String>,
    inputChip: String,
    openLabel: String,
    fill: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .hudBox(
                fill = if (hovered) Krt.Control else Krt.Panel,
                hairline = if (hovered) Krt.Orange else Krt.Hairline,
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

        Column(
            modifier = Modifier
                .then(if (fill) Modifier.weight(1f) else Modifier)
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Hairline))
            Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                bullets.forEach { bullet ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(6.dp).background(Krt.Orange))
                        Text(bullet, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Hairline))
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KrtChip(inputChip)
            Spacer(Modifier.weight(1f))
            GhostButton(openLabel, onClick = onOpen)
        }
    }
}
