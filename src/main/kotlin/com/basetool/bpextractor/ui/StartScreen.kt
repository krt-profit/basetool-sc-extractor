package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.LocalStrings

/**
 * The launcher (design spec §3, "Start" tab): greeting banner + one card per workflow, each with
 * name, description, input hint and an open action. The fan-kit footer (Made by the Community logo
 * + verbatim trademark notice) is global and lives outside this screen; the launcher adds the
 * "unofficial fan tool" chip the design places with the greeting.
 */
@Composable
fun StartScreen(onOpen: (MainTab) -> Unit) {
    val strings = LocalStrings.current
    val honeycomb = rememberHoneycombPainter()
    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            GreetingHeader(title = strings.startTitle, subtitle = strings.startSubtitle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                WorkflowCard(
                    title = strings.bpCardTitle,
                    description = strings.bpCardDesc,
                    inputHint = strings.bpCardInputHint,
                    openLabel = strings.startOpen,
                    modifier = Modifier.weight(1f),
                ) { onOpen(MainTab.BLUEPRINTS) }
                WorkflowCard(
                    title = strings.rfCardTitle,
                    description = strings.rfCardDesc,
                    inputHint = strings.rfCardInputHint,
                    openLabel = strings.startOpen,
                    modifier = Modifier.weight(1f),
                ) { onOpen(MainTab.REFINERY) }
            }
            // "Unofficial fan tool" chip (design spec §3) — square-first, hairline border.
            Box(
                modifier = Modifier
                    .border(1.dp, Krt.Gray3)
                    .background(Krt.Gray4)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    strings.unofficialChip.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Krt.Gray2,
                )
            }
        }
    }
}

/** One large workflow card: Audiowide title, body description, muted input hint, ghost open action. */
@Composable
private fun WorkflowCard(
    title: String,
    description: String,
    inputHint: String,
    openLabel: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    Column(modifier = modifier.hudBox().padding(18.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            color = Krt.Orange,
        )
        Spacer(Modifier.height(10.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
        Spacer(Modifier.height(10.dp))
        Text(inputHint, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
        Spacer(Modifier.height(14.dp))
        GhostButton(openLabel, onClick = onOpen)
    }
}
