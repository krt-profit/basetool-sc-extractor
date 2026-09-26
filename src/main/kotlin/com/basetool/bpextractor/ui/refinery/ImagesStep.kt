package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.KrtCheckbox
import com.basetool.bpextractor.ui.KrtTextField
import com.basetool.bpextractor.ui.PickerMode
import com.basetool.bpextractor.ui.PickerRequest
import com.basetool.bpextractor.ui.StatusDot
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.io.File

/**
 * The Bilder laden step: folder bar, stats row and thumbnail grid, with back and "Extraktion starten"
 * in the pinned footer. The whole step is a drop target for external image files.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ImagesStep(state: RefineryUiState, appScope: CoroutineScope, onPicker: (PickerRequest) -> Unit) {
    var dragOver by remember { mutableStateOf(false) }
    val dropTarget = remember(state, appScope) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                dragOver = true
            }

            override fun onExited(event: DragAndDropEvent) {
                dragOver = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragOver = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragOver = false
                return runCatching { state.importTransferable(appScope, event.awtTransferable) }
                    .getOrDefault(false)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .then(if (dragOver) Modifier.border(1.dp, Krt.Orange) else Modifier),
    ) {
        ImagesStepContent(state, appScope, onPicker)
    }
}

@Composable
private fun ImagesStepContent(
    state: RefineryUiState,
    appScope: CoroutineScope,
    onPicker: (PickerRequest) -> Unit,
) {
    val strings = LocalStrings.current

    LaunchedEffect(state.folder) {
        val path = state.folder
        if (path.isNotBlank() && path != state.loadedFolder && File(path).isDirectory) {
            delay(400)
            if (path == state.folder) state.loadFolder(appScope, path)
        }
    }

    LaunchedEffect(state.loadedFolder) {
        val path = state.loadedFolder ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            state.rescanFolder(appScope, path)
        }
    }

    LaunchedEffect(state.watchClipboard) {
        while (state.watchClipboard) {
            delay(1_000)
            state.pollClipboard(appScope)
        }
    }

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
                enabled = state.selectedImages.isNotEmpty() && !state.loadingImages,
                onClick = { state.startExtraction() },
            )
        },
    ) {
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
                            ) { state.loadFolder(appScope, it) },
                        )
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(strings.rfPasteDropHint, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
            Spacer(Modifier.height(8.dp))
            KrtCheckbox(
                checked = state.watchClipboard,
                onCheckedChange = { state.setClipboardWatch(it) },
                label = strings.rfWatchClipboard,
                labelStyle = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        AlertBox(Krt.Warning) {
            Text(
                strings.rfCaptureAberrationTitle.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Krt.Warning,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                strings.rfCaptureAberrationHint,
                style = MaterialTheme.typography.bodySmall,
                color = Krt.Gray1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                strings.rfCaptureFramingTitle.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Krt.Warning,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                strings.rfCaptureFramingHint,
                style = MaterialTheme.typography.bodySmall,
                color = Krt.Gray1,
            )
            val lowRes = state.images.count { it.lowResolution }
            if (lowRes > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ ${strings.rfLowResNote(lowRes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Krt.Warning,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KrtChip("${strings.rfStatImages}: ${state.images.size}")
            val selectedCount = state.selectedImages.size
            if (state.images.isNotEmpty()) {
                KrtChip(
                    "${strings.rfStatSelected}: $selectedCount",
                    color = if (selectedCount == 0) Krt.Warning else Krt.Gray1,
                )
            }
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
            state.hasTempImages -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(Krt.Orange)
                    Text(strings.rfTempNote, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                }
            }
            else -> {}
        }
        Spacer(Modifier.height(10.dp))

        if (state.images.isNotEmpty()) {
            val anySelected = state.selectedImages.isNotEmpty()
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                GhostButton(
                    if (anySelected) strings.rfDeselectAll else strings.rfSelectAll,
                    onClick = { state.setAllImagesSelected(!anySelected) },
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.images, key = { it.file.absolutePath }) { image ->
                ImageTile(
                    image,
                    onToggle = { state.toggleImageSelected(image) },
                    onRemove = { state.removeImage(image) },
                )
            }
        }
    }
}

@Composable
private fun ImageTile(image: RefineryImage, onToggle: () -> Unit, onRemove: () -> Unit) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.hudBox().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Krt.SurfaceInput)
                .clickable(onClick = onToggle)
                .alpha(if (image.selected) 1f else 0.35f),
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
        KrtCheckbox(
            checked = image.selected,
            onCheckedChange = { onToggle() },
            label = image.file.name,
            labelStyle = MaterialTheme.typography.bodySmall,
            labelMaxLines = 1,
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
