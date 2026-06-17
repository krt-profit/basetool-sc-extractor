package com.basetool.bpextractor.ui.refinery

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.SendController
import com.basetool.bpextractor.ui.SendKind
import com.basetool.bpextractor.ui.SendOverlay
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.StringsEn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop

/**
 * §5.5 Export & Upload on the [StepScaffold]: the green written-path success alert, then two
 * height-filling panels side by side — the manual-upload v1 instructions card (Refinery →
 * Import order → pick the JSON → review the pre-filled form) and the provenance panel mirroring
 * the contract fields. "Show in folder" and the "Neue Extraktion" CTA sit in the pinned footer.
 */
@Composable
fun ExportStep(state: RefineryUiState, appScope: CoroutineScope) {
    val strings = LocalStrings.current
    val file = state.exportedFile ?: return
    val extract = state.result?.extract ?: return
    val order = extract.orders.first()
    val canOpen = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }
    val sendController = remember { SendController() }
    // The export's locale tag for the relayed Accept-Language (derived from the active catalogue).
    val langTag = if (strings === StringsEn) "en" else "de"

    Box(Modifier.fillMaxSize()) {
    StepScaffold(
        overline = strings.rfStepOverline(5),
        title = strings.rfExportTitle,
        scrollBody = false,
        footer = {
            if (canOpen) {
                GhostButton(
                    strings.bpShowInFolder,
                    onClick = {
                        val parent = file.absoluteFile.parentFile ?: return@GhostButton
                        appScope.launch { withContext(Dispatchers.IO) { runCatching { Desktop.getDesktop().open(parent) } } }
                    },
                )
            }
            // "Neue Extraktion" is now a secondary (ghost) action: the export step's single filled
            // CTA is the one-click send (epic krt-iri/basetool#639), which is the point of the step.
            GhostButton(strings.rfNewExtraction, onClick = { state.newExtraction() })
            Spacer(Modifier.weight(1f))
            CtaButton(
                strings.send.button,
                onClick = {
                    // Send the exact bytes that were written to disk (a few KB); the gateway
                    // matches it server-side and the browser opens the pre-filled basetool page.
                    val json = runCatching { file.readText() }.getOrNull()
                    if (json != null) sendController.request(appScope, SendKind.REFINERY, json, langTag)
                },
            )
        },
    ) {
        AlertBox(Krt.Success) {
            Text(
                strings.rfExportSuccess(file.absolutePath),
                style = MaterialTheme.typography.bodyMedium,
                color = Krt.Gray1,
            )
        }
        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Manual-upload v1 instructions (no direct upload — Phase 4 is deferred).
            Column(
                modifier = Modifier.weight(1.4f).fillMaxHeight().hudBox().padding(16.dp),
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
                modifier = Modifier.weight(1f).fillMaxHeight().hudBox(bracket = Krt.Gray3).padding(16.dp),
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
    }

        SendOverlay(sendController, appScope)
    }
}
