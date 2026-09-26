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
 * The Refinery workflow surface hosting the active step screen. [onPicker] hosts file-picker requests
 * at the window root; long-running work runs on [appScope], the window-root scope, so step or tab
 * switches cannot cancel it.
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
