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

/**
 * The Refinery workflow surface (design spec §5): the five-step stepper (Vorprüfung · Bilder ·
 * Extraktion · Review · Export) above the active step screen. Back-navigation goes through the
 * stepper (steps up to the furthest reached); forward navigation only through each screen's CTA.
 * [onPicker] hosts KRT file-picker requests at the window root (no native dialogs).
 */
@Composable
fun RefineryScreen(state: RefineryUiState, onPicker: (PickerRequest) -> Unit) {
    val strings = LocalStrings.current
    val honeycomb = rememberHoneycombPainter()
    Box(modifier = Modifier.fillMaxSize().background(Krt.Black).tiled(honeycomb)) {
        Column(modifier = Modifier.fillMaxSize()) {
            StepperBar(
                steps = strings.rfSteps,
                current = state.step,
                maxReached = state.maxReached,
                onSelect = { target ->
                    // Never jump backwards INTO a running extraction's screen state mid-run.
                    if (!state.running) state.step = target
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.step) {
                    0 -> PreflightStep(state)
                    1 -> ImagesStep(state, onPicker)
                    2 -> ExtractStep(state)
                    3 -> ReviewStep(state, onPicker)
                    else -> ExportStep(state)
                }
            }
        }
    }
}
