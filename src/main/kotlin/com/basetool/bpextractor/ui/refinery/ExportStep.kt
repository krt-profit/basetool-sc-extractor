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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.refinery.RefineryPipeline
import com.basetool.bpextractor.refinery.model.ordersMissingGoods
import com.basetool.bpextractor.refinery.model.ordersMissingSourceImages
import com.basetool.bpextractor.refinery.model.rowsMissingQuantity
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.PickerMode
import com.basetool.bpextractor.ui.PickerRequest
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
import java.io.File

/**
 * The Export & Versand step: the reviewed contract is sent straight to the basetool or saved as JSON
 * locally, the latter also offered as a fallback when a send fails.
 *
 * @param state the refinery workflow state
 * @param appScope the window-root scope long-running send and write work runs on
 * @param onPicker hosts the KRT save-file picker at the window root
 */
@Composable
fun ExportStep(state: RefineryUiState, appScope: CoroutineScope, onPicker: (PickerRequest) -> Unit) {
    val strings = LocalStrings.current
    val extract = state.reviewedExtract() ?: return
    val order = extract.orders.first()
    val missingQtyRows = extract.rowsMissingQuantity()
    val ordersWithoutImages = extract.ordersMissingSourceImages()
    val ordersWithoutGoods = extract.ordersMissingGoods()
    val canSend = missingQtyRows.isEmpty() && ordersWithoutImages.isEmpty() && ordersWithoutGoods.isEmpty()
    val exportedFile = state.exportedFile
    val exportError = state.exportError
    val canOpen = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }
    val sendController = remember { SendController() }
    val langTag = if (strings === StringsEn) "en" else "de"

    val saveJsonLocally = {
        onPicker(
            PickerRequest(
                mode = PickerMode.SAVE_FILE,
                title = strings.rfPickerExportTitle,
                confirmLabel = strings.rfPickerExportConfirm,
                initialPath = defaultExportPath(state),
            ) { path -> state.export(appScope, File(path), strings) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        StepScaffold(
            overline = strings.rfStepOverline(5),
            title = strings.rfExportTitle,
            scrollBody = false,
            footer = {
                if (canOpen && exportedFile != null) {
                    GhostButton(
                        strings.bpShowInFolder,
                        onClick = {
                            val parent = exportedFile.absoluteFile.parentFile ?: return@GhostButton
                            appScope.launch {
                                withContext(Dispatchers.IO) { runCatching { Desktop.getDesktop().open(parent) } }
                            }
                        },
                    )
                }
                GhostButton(strings.rfNewExtraction, onClick = { state.newExtraction() })
                GhostButton(strings.rfCtaExport, onClick = saveJsonLocally)
                Spacer(Modifier.weight(1f))
                CtaButton(
                    strings.send.button,
                    enabled = canSend,
                    onClick = {
                        if (!canSend) return@CtaButton
                        val json = runCatching { RefineryPipeline.toJson(extract) }.getOrNull()
                        if (json != null) sendController.request(appScope, SendKind.REFINERY, json, langTag)
                    },
                )
            },
        ) {
            if (missingQtyRows.isNotEmpty()) {
                AlertBox(Krt.Danger) {
                    Text(
                        strings.rfSendBlockedMissingQty(missingQtyRows.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            if (ordersWithoutGoods.isNotEmpty()) {
                AlertBox(Krt.Danger) {
                    Text(
                        strings.rfSendBlockedNoGoods,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            if (ordersWithoutImages.isNotEmpty()) {
                AlertBox(Krt.Danger) {
                    Text(
                        strings.rfSendBlockedNoSourceImages,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            if (exportError != null) {
                Text(exportError, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
                Spacer(Modifier.height(8.dp))
            }
            if (exportedFile != null) {
                AlertBox(Krt.Success) {
                    Text(
                        strings.rfExportSuccess(exportedFile.absolutePath),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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

        SendOverlay(sendController, appScope, onSaveLocally = saveJsonLocally)
    }
}
