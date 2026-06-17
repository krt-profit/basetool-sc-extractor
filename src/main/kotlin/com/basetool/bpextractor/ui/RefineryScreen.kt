package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.refinery.ExportStep
import com.basetool.bpextractor.ui.refinery.ExtractStep
import com.basetool.bpextractor.ui.refinery.ImagesStep
import com.basetool.bpextractor.ui.refinery.PreflightStep
import com.basetool.bpextractor.ui.refinery.RefineryUiState
import com.basetool.bpextractor.ui.refinery.ReviewStep
import kotlinx.coroutines.CoroutineScope

/**
 * The Refinery workflow surface (design spec §5): hosts the active step screen. Step navigation
 * lives in the window-level [CommandStrip] inline stepper (back up to the furthest reached step;
 * forward only through each screen's CTA). [onPicker] hosts KRT file-picker requests at the
 * window root (no native dialogs). [appScope] is the window-root coroutine scope — long-running
 * work (preflight probes, model pull, the extraction pipeline, the export write) must run on it
 * so step or tab switches, which destroy the step composables, cannot cancel it mid-flight.
 */
@Composable
fun RefineryScreen(state: RefineryUiState, appScope: CoroutineScope, onPicker: (PickerRequest) -> Unit) {
    val honeycomb = rememberHoneycombPainter()
    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        when (state.step) {
            0 -> PreflightStep(state, appScope)
            1 -> ImagesStep(state, appScope, onPicker)
            2 -> ExtractStep(state, appScope)
            3 -> ReviewStep(state)
            else -> ExportStep(state, appScope, onPicker)
        }
    }
}
