package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.GreetingHeader
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop

/**
 * §5.5 Export & Upload: the green written-path success alert, the manual-upload v1 instructions
 * card (Refinery → Import order → pick the JSON → review the pre-filled form), the provenance
 * panel mirroring the contract fields, and the "Neue Extraktion" restart.
 */
@Composable
fun ExportStep(state: RefineryUiState) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val file = state.exportedFile ?: return
    val extract = state.result?.extract ?: return
    val order = extract.orders.first()
    val canOpen = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GreetingHeader(title = strings.rfExportTitle, subtitle = strings.rfReviewSubtitle)

        AlertBox(Krt.Success) {
            Text(
                strings.rfExportSuccess(file.absolutePath),
                style = MaterialTheme.typography.bodyMedium,
                color = Krt.Gray1,
            )
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Manual-upload v1 instructions (no direct upload — Phase 4 is deferred).
            Column(
                modifier = Modifier.weight(1.4f).hudBox().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    strings.rfUploadCardTitle.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                )
                strings.rfUploadSteps.forEach { stepText ->
                    Text(stepText, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }
            }
            // Provenance panel mirroring the contract fields.
            Column(
                modifier = Modifier.weight(1f).hudBox().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    strings.rfProvenanceTitle.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                )
                KeyValueRow(strings.rfProvTool, extract.tool)
                KeyValueRow(strings.rfProvVersion, extract.toolVersion)
                KeyValueRow(strings.rfProvModel, extract.model)
                KeyValueRow(strings.rfProvSchema, extract.schemaVersion.toString())
                KeyValueRow(strings.rfProvPanel, order.panelType)
                KeyValueRow(strings.rfProvGenerated, extract.generatedAt)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canOpen) {
                GhostButton(
                    strings.bpShowInFolder,
                    onClick = {
                        val parent = file.absoluteFile.parentFile ?: return@GhostButton
                        scope.launch { withContext(Dispatchers.IO) { runCatching { Desktop.getDesktop().open(parent) } } }
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            CtaButton(strings.rfNewExtraction, onClick = { state.newExtraction() })
        }
    }
}
