package com.basetool.bpextractor.ui.refinery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.basetool.bpextractor.refinery.CaptureTime
import com.basetool.bpextractor.refinery.ExtractWarning
import com.basetool.bpextractor.refinery.GpuInfo
import com.basetool.bpextractor.refinery.HardwareSnapshot
import com.basetool.bpextractor.refinery.HardwareTier
import com.basetool.bpextractor.refinery.HttpOllamaClient
import com.basetool.bpextractor.refinery.ImageOutcome
import com.basetool.bpextractor.refinery.JmxRamProbe
import com.basetool.bpextractor.refinery.JvmScProcessProbe
import com.basetool.bpextractor.refinery.Locate
import com.basetool.bpextractor.refinery.OllamaApi
import com.basetool.bpextractor.refinery.PipelineCancelledException
import com.basetool.bpextractor.refinery.PipelineInput
import com.basetool.bpextractor.refinery.PipelineListener
import com.basetool.bpextractor.refinery.PipelineResult
import com.basetool.bpextractor.refinery.PipelineStage
import com.basetool.bpextractor.refinery.Preflight
import com.basetool.bpextractor.refinery.RefineryPipeline
import com.basetool.bpextractor.refinery.TierDecision
import com.basetool.bpextractor.refinery.Validation
import com.basetool.bpextractor.refinery.WindowsGpuProbe
import com.basetool.bpextractor.refinery.model.RefineryExtract
import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.refinery.model.RefineryExtractOrder
import com.basetool.bpextractor.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** The Ollama-runtime card states of design spec §5.1, one object per visual state. */
sealed interface OllamaStatus {
    /** Probe in flight. */
    data object Checking : OllamaStatus

    /** Reachable and the selected model is installed. */
    data object Ready : OllamaStatus

    /** Reachable, but the selected model is not installed — offer the guided pull. */
    data class ModelMissing(val model: String) : OllamaStatus

    /** A guided `ollama pull` is streaming; progress in bytes when the registry reports it. */
    data class Pulling(val completed: Long?, val total: Long?, val status: String) : OllamaStatus

    /** The guided pull failed (network/registry error). */
    data class PullFailed(val message: String) : OllamaStatus

    /** No Ollama at the endpoint — show the 2-step install hint. */
    data class Unreachable(val message: String) : OllamaStatus
}

/** The below-recommended fallback choice (§5.1 radio): compact GPU model vs. CPU-only mode. */
enum class FallbackChoice { LOW_VRAM, CPU }

/** One loaded screenshot: file + native size + crop tag + a grid thumbnail. */
data class RefineryImage(
    val file: File,
    val width: Int,
    val height: Int,
    /** True → tagged `vorgecroppt` and Locate is skipped (design §5.2 crop tags). */
    val precropped: Boolean,
    val thumbnail: ImageBitmap?,
    /** False → kept in the grid but excluded from the extraction run (§5.2 tile checkbox). */
    val selected: Boolean = true,
)

/**
 * All UI state of the refinery workflow (design spec §5) + the glue that drives the preflight
 * probes, the guided model pull, folder loading and the extraction pipeline. Heavy work always
 * runs on [Dispatchers.IO]; Compose snapshot state is safely written cross-thread.
 */
class RefineryUiState(
    /** Ollama client factory — injectable so UI logic could be exercised without a server. */
    private val clientFor: (String) -> OllamaApi = { HttpOllamaClient(it) },
) {
    // --- navigation ---
    var step by mutableStateOf(0)
    var maxReached by mutableStateOf(0)

    // --- §5.1 preflight ---
    var endpoint by mutableStateOf(HttpOllamaClient.DEFAULT_ENDPOINT)
    var ollamaStatus by mutableStateOf<OllamaStatus>(OllamaStatus.Checking)
    var hardware by mutableStateOf<HardwareSnapshot?>(null)
    var decision by mutableStateOf<TierDecision?>(null)
    var fallback by mutableStateOf(FallbackChoice.LOW_VRAM)
    var scAcknowledged by mutableStateOf(false)
    private var preflightRan = false

    // --- §5.2 images ---
    var folder by mutableStateOf("")

    /** The path the loaded [images] reflect — guards the auto-load from rescanning the same dir. */
    var loadedFolder by mutableStateOf<String?>(null)
    val images = mutableStateListOf<RefineryImage>()
    var imagesNote by mutableStateOf<String?>(null)
    var loadingImages by mutableStateOf(false)

    /** Paths the user removed via the tile ✕ — the §5.2 folder watch must not re-add them. */
    private val dismissedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True while a folder-watch tick is diffing — overlapping ticks are skipped. */
    private var rescanning by mutableStateOf(false)

    // --- §5.3 extraction ---
    /** The selection snapshot the active/last run works on (drives the §5.3 stage rows). */
    val runImages = mutableStateListOf<RefineryImage>()
    var running by mutableStateOf(false)

    /** Whether the active/last run includes the cross-model verify pass (the VERIFY stage). */
    var runVerify by mutableStateOf(false)
        private set
    var currentIndex by mutableStateOf(-1)
    val stageReached = mutableStateMapOf<Int, PipelineStage>()
    val outcomes = mutableStateMapOf<Int, ImageOutcome>()
    val console = mutableStateListOf<String>()
    var extractError by mutableStateOf<String?>(null)
    var cancelRequested by mutableStateOf(false)
    var result by mutableStateOf<PipelineResult?>(null)

    // --- §5.4 manual review corrections ---
    /** The order header with the user's corrections applied; null until the first header edit. */
    var editedOrder by mutableStateOf<RefineryExtractOrder?>(null)
        private set

    /** Row index → manually corrected goods row (exported at [CONFIDENCE_MANUAL]). */
    val editedGoods = mutableStateMapOf<Int, RefineryExtractGood>()

    /**
     * The order as the review shows and the export writes it: the machine read overlaid with the
     * user's corrections. `layoutConfidence` and the order warnings stay machine-derived — they
     * document the READ quality; only corrected rows carry the manual confidence.
     */
    val reviewedOrder: RefineryExtractOrder?
        get() {
            val machine = result?.extract?.orders?.firstOrNull() ?: return null
            val base = editedOrder ?: machine
            return base.copy(goods = machine.goods.map { editedGoods[it.rowIndex] ?: it })
        }

    /** Apply a header correction (location/method/cost/duration) on top of earlier ones. */
    fun editHeader(transform: (RefineryExtractOrder) -> RefineryExtractOrder) {
        val base = editedOrder ?: result?.extract?.orders?.firstOrNull() ?: return
        editedOrder = transform(base)
    }

    /**
     * Commit a corrected goods row. A row whose values equal the machine read again drops back
     * to un-edited (keeps the derived confidence); otherwise the row exports at
     * [CONFIDENCE_MANUAL] — the user has looked at the screenshot, which beats any heuristic.
     */
    fun editGood(original: RefineryExtractGood, edited: RefineryExtractGood) {
        if (edited.copy(confidence = original.confidence) == original) {
            editedGoods.remove(original.rowIndex)
        } else {
            editedGoods[original.rowIndex] = edited.copy(confidence = CONFIDENCE_MANUAL)
        }
    }

    /** Restore the machine read of one goods row (the ↺ action). */
    fun revertGood(rowIndex: Int) {
        editedGoods.remove(rowIndex)
    }

    /**
     * Machine warnings the user's corrections have RESOLVED — recomputed deterministically
     * against [reviewedOrder] so the §5.4 banner can show them as settled instead of pretending
     * the finding still stands. Only the value-dependent warnings are re-checkable
     * (SUM_MISMATCH via [Validation.sumMismatch]; IMPLAUSIBLE_CELL when every flagged row was
     * corrected to plausible numbers; YIELD_RATIO_OUTLIER via [Validation.yieldRatioOutliers]) —
     * the read-provenance warnings (REFINE/VERIFY/UNQUOTED/STITCH_CONTESTED)
     * describe how the values came to be and stay as they are.
     */
    val resolvedWarnings: Set<ExtractWarning>
        get() {
            val validated = result?.validated ?: return emptySet()
            if (editedGoods.isEmpty()) return emptySet()
            val goods = reviewedOrder?.goods ?: return emptySet()
            val resolved = mutableSetOf<ExtractWarning>()
            if (ExtractWarning.SUM_MISMATCH in validated.warnings &&
                !Validation.sumMismatch(goods, validated.toRefineTotal)
            ) {
                resolved += ExtractWarning.SUM_MISMATCH
            }
            if (ExtractWarning.IMPLAUSIBLE_CELL in validated.warnings && goods.none(::implausibleReviewed)) {
                resolved += ExtractWarning.IMPLAUSIBLE_CELL
            }
            // The per-material yield/qty ratio re-check: correcting the divergent digit settles it.
            if (ExtractWarning.YIELD_RATIO_OUTLIER in validated.warnings &&
                Validation.yieldRatioOutliers(goods).isEmpty()
            ) {
                resolved += ExtractWarning.YIELD_RATIO_OUTLIER
            }
            return resolved
        }

    /** Reviewed-state implausibility: an edited row is judged by its values, an unedited one by its machine confidence. */
    private fun implausibleReviewed(good: RefineryExtractGood): Boolean {
        val output = good.outputQuantity
        val input = good.inputQuantity
        val yieldExceedsQty = output != null && input != null && output > input
        val machineImplausible = good.rowIndex !in editedGoods &&
            good.confidence == Validation.CONFIDENCE_IMPLAUSIBLE
        return yieldExceedsQty || machineImplausible
    }

    private fun clearReviewEdits() {
        editedOrder = null
        editedGoods.clear()
    }

    // --- §5.5 export ---
    var exportedFile by mutableStateOf<File?>(null)
    var exportError by mutableStateOf<String?>(null)

    /** The model the run will use: the auto decision, overridden by the §5.1 fallback radio. */
    val selectedModel: String
        get() {
            val d = decision ?: return Preflight.MODEL_RECOMMENDED
            return if (d.tier == HardwareTier.RECOMMENDED) d.model else Preflight.MODEL_MINIMUM
        }

    /** Whether the run uses the GPU (false = forced CPU mode via `num_gpu 0`). */
    val gpuMode: Boolean
        get() {
            val d = decision ?: return true
            return when (d.tier) {
                HardwareTier.RECOMMENDED -> true
                // Below recommended, the radio decides: the compact model on the GPU (Ollama
                // auto-offloads when it doesn't fully fit) or forced CPU mode (num_gpu 0).
                HardwareTier.MINIMUM, HardwareTier.CPU -> fallback != FallbackChoice.CPU
            }
        }

    /** True when the cross-model verify partner is installed (probed alongside the model check). */
    var verifyAvailable by mutableStateOf(false)
        private set

    /**
     * The cross-model verify partner for this run, or null. Policy (PHASE0 addendum 2026-06-12):
     * recommended tier on the GPU only — the verify pass is an accuracy bonus and must never
     * cost a below-tier machine double CPU time — and only when the partner is already installed
     * (no extra download flow; the pass simply lights up once the model is present).
     */
    val verifyModel: String?
        get() = Preflight.MODEL_VERIFY.takeIf {
            it != selectedModel && gpuMode && selectedModel == Preflight.MODEL_RECOMMENDED && verifyAvailable
        }

    /** Measured per-image ETA for the effective (model, mode) pair (`PHASE0_FINDINGS.md` §5). */
    val etaSecondsPerImage: Int
        get() = Preflight.etaSecondsPerImage(selectedModel, gpuMode) +
            (if (verifyModel != null) Preflight.ETA_GPU_MINIMUM_S else 0)

    /** True when the §5.1 CTA may enable: Ollama ready AND the SC warning is acknowledged. */
    val preflightReady: Boolean
        get() = ollamaStatus == OllamaStatus.Ready &&
            (hardware?.scRunning != true || scAcknowledged)

    /** Run (or re-run) the §5.1 probes: hardware snapshot + Ollama reachability + model check. */
    fun runPreflight(scope: CoroutineScope, force: Boolean = false) {
        if (preflightRan && !force) return
        preflightRan = true
        ollamaStatus = OllamaStatus.Checking
        scope.launch(Dispatchers.IO) {
            val snapshot = Preflight.snapshot(JvmScProcessProbe(), JmxRamProbe(), WindowsGpuProbe())
            hardware = snapshot
            val auto = Preflight.decide(snapshot.gpu?.vramBytes)
            decision = auto
            fallback = if (auto.tier == HardwareTier.CPU) FallbackChoice.CPU else FallbackChoice.LOW_VRAM
            checkOllama()
        }
    }

    /** Re-check Ollama only (the §5.1 "Erneut prüfen" ghost action + after endpoint edits). */
    fun recheckOllama(scope: CoroutineScope) {
        ollamaStatus = OllamaStatus.Checking
        scope.launch(Dispatchers.IO) { checkOllama() }
    }

    private fun checkOllama() {
        val model = selectedModel
        ollamaStatus = try {
            val installed = clientFor(endpoint).installedModels()
            verifyAvailable = installed.any {
                it.name == Preflight.MODEL_VERIFY || it.name.startsWith("${Preflight.MODEL_VERIFY}:")
            }
            if (installed.any { it.name == model || it.name.startsWith("$model:") }) {
                OllamaStatus.Ready
            } else {
                OllamaStatus.ModelMissing(model)
            }
        } catch (t: Throwable) {
            OllamaStatus.Unreachable(t.message ?: t::class.simpleName ?: "?")
        }
    }

    /** The guided `ollama pull` of the missing model with streaming progress (§5.1). */
    fun pullModel(scope: CoroutineScope) {
        val model = selectedModel
        ollamaStatus = OllamaStatus.Pulling(null, null, "")
        scope.launch(Dispatchers.IO) {
            try {
                clientFor(endpoint).pull(model) { completed, total, status ->
                    ollamaStatus = OllamaStatus.Pulling(completed, total, status)
                }
                checkOllama()
            } catch (t: Throwable) {
                ollamaStatus = OllamaStatus.PullFailed(t.message ?: t::class.simpleName ?: "?")
            }
        }
    }

    /** Load every PNG/JPG of [path] (1 folder = 1 order): native size + crop tag + thumbnail. */
    fun loadFolder(scope: CoroutineScope, path: String) {
        folder = path
        loadedFolder = path
        loadingImages = true
        imagesNote = null
        dismissedPaths.clear()
        images.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val loaded = imageFilesIn(File(path)).mapNotNull(::loadImage)
                withContext(Dispatchers.Default) {
                    images.clear()
                    images.addAll(loaded)
                }
            } finally {
                loadingImages = false
            }
        }
    }

    /**
     * §5.2 folder watch — one poll tick: diff [path] against the loaded grid instead of
     * reloading it. New image files appear at the end of the grid (selected by default), images
     * whose file vanished from the folder drop out, and every surviving tile keeps its checkbox
     * state untouched. Images removed via the tile ✕ stay out ([dismissedPaths]); a file still
     * being written simply fails to decode and is retried on the next tick. No-op while the
     * initial load or a previous tick is in flight, or when [path] is no longer the loaded
     * folder.
     */
    fun rescanFolder(scope: CoroutineScope, path: String) {
        if (loadingImages || rescanning || path != loadedFolder) return
        rescanning = true
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(path)
                if (!dir.isDirectory) return@launch
                val files = imageFilesIn(dir)
                val onDisk = files.mapTo(HashSet()) { it.absolutePath }
                val known = images.mapTo(HashSet()) { it.file.absolutePath }
                val added = files
                    .filter { it.absolutePath !in known && it.absolutePath !in dismissedPaths }
                    .mapNotNull(::loadImage)
                withContext(Dispatchers.Default) {
                    if (path != loadedFolder) return@withContext
                    images.removeAll {
                        it.file.absoluteFile.parentFile == dir.absoluteFile &&
                            it.file.absolutePath !in onDisk
                    }
                    images.addAll(added)
                }
            } finally {
                rescanning = false
            }
        }
    }

    /** Drop one loaded image from the order (the §5.2 tile's remove action). */
    fun removeImage(image: RefineryImage) {
        dismissedPaths += image.file.absolutePath
        images.remove(image)
    }

    /** The images the next extraction run will use (the §5.2 tile checkboxes). */
    val selectedImages: List<RefineryImage>
        get() = images.filter { it.selected }

    /** Toggle whether [image] is part of the extraction run (the §5.2 tile checkbox). */
    fun toggleImageSelected(image: RefineryImage) {
        val index = images.indexOf(image)
        if (index >= 0) images[index] = images[index].copy(selected = !images[index].selected)
    }

    /** §5.2 bulk action: tick or untick every loaded image in one click. */
    fun setAllImagesSelected(selected: Boolean) {
        for (i in images.indices) {
            val img = images[i]
            if (img.selected != selected) images[i] = img.copy(selected = selected)
        }
    }

    /**
     * §5.2 CTA — (re)enter the extraction step. Any previous run's result is discarded so the
     * step's auto-start fires again with the CURRENT selection, and the stepper ceiling drops
     * back to this step: a changed input invalidates the old review/export.
     */
    fun startExtraction() {
        if (running) return
        result = null
        extractError = null
        cancelRequested = false
        currentIndex = -1
        stageReached.clear()
        outcomes.clear()
        console.clear()
        clearReviewEdits()
        step = 2
        maxReached = 2
    }

    /** True when any loaded image sits in the session temp folder (shows the §5.2 temp note). */
    val hasTempImages: Boolean
        get() = images.any { ImageIntake.isTempFile(it.file) }

    /**
     * §5.2 intake — add the images carried by [t] (Strg+V clipboard paste or external drag &
     * drop). Files are persisted via [ImageIntake]: into the picked folder when one is set,
     * otherwise into the session temp folder that is deleted on exit. Returns false when [t]
     * carries nothing usable, so key-event callers can leave the event to e.g. a text field.
     */
    fun importTransferable(scope: CoroutineScope, t: Transferable): Boolean {
        if (!ImageIntake.accepts(t)) return false
        if (loadingImages) return true // swallow re-entrant pastes while a load is in flight
        loadingImages = true
        scope.launch(Dispatchers.IO) {
            try {
                val dir = intakeDir()
                val saved = mutableListOf<File>()
                ImageIntake.imageFilesFrom(t).forEach { file ->
                    runCatching { saved += ImageIntake.copyInto(file, dir) }
                }
                if (saved.isEmpty()) {
                    ImageIntake.rawImageFrom(t)?.let { saved += ImageIntake.saveClipboardImage(it, dir) }
                }
                val known = images.mapTo(HashSet()) { it.file.absolutePath }
                val loaded = saved.filter { it.absolutePath !in known }.distinct().mapNotNull(::loadImage)
                withContext(Dispatchers.Default) {
                    // A re-paste of a previously ✕-removed image is an explicit re-add.
                    loaded.forEach { dismissedPaths -= it.file.absolutePath }
                    images.addAll(loaded)
                }
            } finally {
                loadingImages = false
            }
        }
        return true
    }

    /** Strg+V handler: import the system clipboard. False when it holds no image content. */
    fun pasteFromClipboard(scope: CoroutineScope): Boolean {
        val t = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }
            .getOrNull() ?: return false
        return importTransferable(scope, t)
    }

    /** Where pastes/drops are persisted: the picked folder if valid, else the temp folder. */
    private fun intakeDir(): File {
        val picked = File(folder.trim())
        if (folder.isNotBlank() && picked.isDirectory) return picked
        return ImageIntake.tempFolder()
    }

    /** Start the §5.3 extraction run over the SELECTED images, one at a time. */
    fun runExtraction(scope: CoroutineScope, toolVersion: String) {
        val snapshot = selectedImages
        if (running || snapshot.isEmpty()) return
        running = true
        cancelRequested = false
        extractError = null
        result = null
        currentIndex = -1
        stageReached.clear()
        outcomes.clear()
        console.clear()
        clearReviewEdits()
        runImages.clear()
        runImages.addAll(snapshot)
        runVerify = verifyModel != null
        val pipeline = RefineryPipeline(
            ollama = clientFor(endpoint),
            model = selectedModel,
            toolVersion = toolVersion,
            isActive = { !cancelRequested },
            numGpu = if (gpuMode) null else 0,
            verifyModel = verifyModel,
        )
        val listener = object : PipelineListener {
            override fun onStage(index: Int, name: String, stage: PipelineStage) {
                currentIndex = index
                stageReached[index] = stage
            }

            override fun onImageDone(index: Int, outcome: ImageOutcome) {
                outcomes[index] = outcome
            }

            override fun onLog(line: String) {
                console += line
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val inputs = snapshot.map {
                    PipelineInput(it.file.name, readFull(it.file), CaptureTime.of(it.file))
                }
                result = pipeline.extract(inputs, listener)
            } catch (c: PipelineCancelledException) {
                extractError = CANCELLED_MARKER
            } catch (t: Throwable) {
                extractError = t.message ?: t::class.simpleName ?: "?"
            } finally {
                running = false
            }
        }
    }

    /**
     * The contract extract with the user's review corrections overlaid (machine read + edits) —
     * the single source of truth for both sending to the basetool and writing the JSON. Null until
     * an extraction has produced a reviewable order.
     */
    fun reviewedExtract(): RefineryExtract? {
        val machine = result?.extract ?: return null
        val order = reviewedOrder ?: return null
        return machine.copy(orders = listOf(order))
    }

    /**
     * Write the reviewed contract JSON to [target] — the export step's "save JSON" alternative to
     * sending. Does NOT advance the step (the user is already on the export screen); on success it
     * sets [exportedFile] so the screen shows the written path. Failures surface in [exportError].
     */
    fun export(scope: CoroutineScope, target: File, strings: Strings) {
        val extract = reviewedExtract() ?: return
        exportError = null
        scope.launch(Dispatchers.IO) {
            try {
                RefineryPipeline.writeJson(extract, target)
                exportedFile = target
            } catch (t: Throwable) {
                exportError = strings.rfExportFailed(t.message ?: t::class.simpleName ?: strings.unknownError)
            }
        }
    }

    /** Navigate to [target] step, tracking the furthest step reached (stepper back-nav). */
    fun goTo(target: Int) {
        step = target
        if (target > maxReached) maxReached = target
    }

    /** Reset images/extraction/export for a fresh run, keeping the preflight results (§5.5). */
    fun newExtraction() {
        folder = ""
        loadedFolder = null
        images.clear()
        imagesNote = null
        dismissedPaths.clear()
        runImages.clear()
        running = false
        cancelRequested = false
        extractError = null
        result = null
        currentIndex = -1
        stageReached.clear()
        outcomes.clear()
        console.clear()
        clearReviewEdits()
        exportedFile = null
        exportError = null
        maxReached = 1
        step = 1
    }

    /** The PNG/JPG files of [dir], name-sorted — the view shared by [loadFolder] and the watch. */
    private fun imageFilesIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in ImageIntake.IMAGE_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Decode [file] into a grid tile (native size, crop tag, thumbnail), null when unreadable. */
    private fun loadImage(file: File): RefineryImage? = runCatching {
        val img = ImageIO.read(file) ?: return@runCatching null
        RefineryImage(
            file = file,
            width = img.width,
            height = img.height,
            precropped = Locate.isPrecropped(img.width, img.height),
            thumbnail = thumbnail(img),
        )
    }.getOrNull()

    private fun readFull(file: File): BufferedImage =
        requireNotNull(ImageIO.read(file)) { "cannot decode ${file.name}" }

    /** Downscale to a grid thumbnail (long edge [THUMB_LONG_EDGE]) and convert for Compose. */
    private fun thumbnail(img: BufferedImage): ImageBitmap {
        val factor = THUMB_LONG_EDGE.toDouble() / maxOf(img.width, img.height)
        val w = maxOf(1, (img.width * factor).toInt())
        val h = maxOf(1, (img.height * factor).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.drawImage(img, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out.toComposeImageBitmap()
    }

    companion object {
        /** Sentinel in [extractError] marking a user cancel (rendered as a neutral note). */
        const val CANCELLED_MARKER = "cancelled"

        /** Exported confidence of a row the user corrected by hand in the review (§5.4). */
        const val CONFIDENCE_MANUAL = 1.0

        private const val THUMB_LONG_EDGE = 240
    }
}

/** Human-readable GiB rendering for the hardware rows ("16,0 GB" style, locale-neutral dot). */
fun bytesToGb(bytes: Long?): String? = bytes?.let { String.format("%.1f GB", it / 1073741824.0) }

/** Compact VRAM line for the GPU row: name + VRAM when both are known. */
fun gpuLabel(gpu: GpuInfo?, unknown: String): String = gpu?.name ?: unknown
