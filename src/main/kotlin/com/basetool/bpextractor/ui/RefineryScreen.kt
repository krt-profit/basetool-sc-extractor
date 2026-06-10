package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * The Refinery workflow surface (design spec §5): the five-step stepper (Vorprüfung · Bilder ·
 * Extraktion · Review · Export) above the step content. The step screens are filled in over
 * Phase 3 (#436); until each lands, the content area carries a clearly-labelled placeholder so
 * the navigation shell is testable end-to-end.
 */
@Composable
fun RefineryScreen() {
    val strings = LocalStrings.current
    val honeycomb = rememberHoneycombPainter()
    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        Column(modifier = Modifier.fillMaxSize()) {
            StepperBar(steps = strings.rfSteps, current = 0)
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().hudBox().padding(18.dp)) {
                    Text(
                        strings.rfPlaceholderTitle.uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Krt.Orange,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        strings.rfPlaceholderBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
            }
        }
    }
}
