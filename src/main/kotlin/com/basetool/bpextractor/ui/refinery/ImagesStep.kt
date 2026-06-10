package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtTextField
import com.basetool.bpextractor.ui.PickerMode
import com.basetool.bpextractor.ui.PickerRequest
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings

/**
 * §5.2 Bilder laden on the [StepScaffold]: the "1 folder = 1 order" framing, a folder bar, the
 * mini-stats row and the thumbnail grid (each tile with resolution chip, file name, crop tag and
 * a remove ×) filling the remaining height; back + "Extraktion starten" live in the pinned
 * footer.
 */
@Composable
fun ImagesStep(state: RefineryUiState, onPicker: (PickerRequest) -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    StepScaffold(
        overline = strings.rfStepOverline(2),
        title = strings.rfImagesTitle,
        subtitle = strings.rfImagesSubtitle,
        scrollBody = false,
        footer = {
            GhostButton(strings.back, onClick = { state.goTo(0) })
            Spacer(Modifier.weight(1f))
            CtaButton(
                strings.rfCtaStartExtraction,
                enabled = state.images.isNotEmpty() && !state.loadingImages,
                onClick = { state.goTo(2) },
            )
        },
    ) {
        // Folder bar: mono-style path + browse.
        Column {
            Text(
                strings.rfFolderLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Krt.Gray1,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KrtTextField(
                    value = state.folder,
                    onValueChange = { state.folder = it },
                    placeholder = "…\\Screenshots\\auftrag-01",
                    enabled = !state.loadingImages,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    strings.rfPickFolder,
                    enabled = !state.loadingImages,
                    modifier = Modifier.height(56.dp),
                    onClick = {
                        onPicker(
                            PickerRequest(
                                mode = PickerMode.FOLDER,
                                title = strings.rfPickerImagesTitle,
                                confirmLabel = strings.rfPickerImagesConfirm,
                                initialPath = state.folder,
                            ) { state.loadFolder(scope, it) },
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Mini-stats row (Bilder · Auftrag · Auflösung · Modell).
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KrtChip("${strings.rfStatImages}: ${state.images.size}")
            KrtChip("${strings.rfStatOrder}: 1")
            val resolutions = state.images.map { "${it.width}×${it.height}" }.distinct()
            if (resolutions.isNotEmpty()) {
                KrtChip("${strings.rfStatResolution}: ${resolutions.joinToString(" · ")}")
            }
            KrtChip("${strings.rfStatModel}: ${state.selectedModel}")
        }
        Spacer(Modifier.height(12.dp))

        when {
            state.loadingImages -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Krt.Orange, strokeWidth = 2.dp)
                    Text(strings.rfChecking, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray2)
                }
            }
            state.images.isEmpty() && state.folder.isNotBlank() -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(Krt.Warning)
                    Text(strings.rfNoImagesInFolder, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }
            }
            else -> {}
        }
        Spacer(Modifier.height(10.dp))

        // Thumbnail grid — fills the remaining height (the grid itself scrolls on overflow).
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.images, key = { it.file.absolutePath }) { image ->
                ImageTile(image, onRemove = { state.removeImage(image) })
            }
        }
    }
}

@Composable
private fun ImageTile(image: RefineryImage, onRemove: () -> Unit) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.hudBox().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(110.dp).background(Krt.SurfaceInput),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = image.thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = image.file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                StatusDot(Krt.Gray2)
            }
        }
        Text(
            image.file.name,
            style = MaterialTheme.typography.bodySmall,
            color = Krt.Gray1,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KrtChip("${image.width}×${image.height}")
            KrtChip(
                if (image.precropped) strings.rfCropTagPre else strings.rfCropTagAuto,
                color = if (image.precropped) Krt.Warning else Krt.Gray1,
            )
            Spacer(Modifier.weight(1f))
            GhostButton("✕", onClick = onRemove, modifier = Modifier.width(40.dp))
        }
    }
}
