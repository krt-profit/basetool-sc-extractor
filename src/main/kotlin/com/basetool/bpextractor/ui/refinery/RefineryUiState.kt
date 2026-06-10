package com.basetool.bpextractor.ui.refinery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import com.basetool.bpextractor.refinery.WindowsGpuProbe
import com.basetool.bpextractor.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
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
    val images = mutableStateListOf<RefineryImage>()
    var imagesNote by mutableStateOf<String?>(null)
    var loadingImages by mutableStateOf(false)

    // --- §5.3 extraction ---
    var running by mutableStateOf(false)
    var currentIndex by mutableStateOf(-1)
    val stageReached = mutableStateMapOf<Int, PipelineStage>()
    val outcomes = mutableStateMapOf<Int, ImageOutcome>()
    val console = mutableStateListOf<String>()
    var extractError by mutableStateOf<String?>(null)
    var cancelRequested by mutableStateOf(false)
    var result by mutableStateOf<PipelineResult?>(null)

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

    /** Measured per-image ETA for the effective (model, mode) pair (`PHASE0_FINDINGS.md` §5). */
    val etaSecondsPerImage: Int
        get() = Preflight.etaSecondsPerImage(selectedModel, gpuMode)

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
        loadingImages = true
        imagesNote = null
        images.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val files = File(path).listFiles { f ->
                    f.isFile && f.extension.lowercase() in setOf("png", "jpg", "jpeg")
                }?.sortedBy { it.name.lowercase() } ?: emptyList()
                val loaded = files.mapNotNull { file ->
                    runCatching {
                        val img = ImageIO.read(file) ?: return@runCatching null
                        RefineryImage(
                            file = file,
                            width = img.width,
                            height = img.height,
                            precropped = Locate.isPrecropped(img.width, img.height),
                            thumbnail = thumbnail(img),
                        )
                    }.getOrNull()
                }.filterNotNull()
                withContext(Dispatchers.Default) {
                    images.clear()
                    images.addAll(loaded)
                }
            } finally {
                loadingImages = false
            }
        }
    }

    /** Drop one loaded image from the order (the §5.2 tile's remove action). */
    fun removeImage(image: RefineryImage) {
        images.remove(image)
    }

    /** Start the §5.3 extraction run over the loaded images, one at a time. */
    fun runExtraction(scope: CoroutineScope, toolVersion: String) {
        if (running || images.isEmpty()) return
        running = true
        cancelRequested = false
        extractError = null
        result = null
        currentIndex = -1
        stageReached.clear()
        outcomes.clear()
        console.clear()
        val snapshot = images.toList()
        val pipeline = RefineryPipeline(
            ollama = clientFor(endpoint),
            model = selectedModel,
            toolVersion = toolVersion,
            isActive = { !cancelRequested },
            numGpu = if (gpuMode) null else 0,
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
                val inputs = snapshot.map { PipelineInput(it.file.name, readFull(it.file)) }
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

    /** Write the contract JSON to [target] and advance to the §5.5 export screen. */
    fun export(scope: CoroutineScope, target: File, strings: Strings) {
        val extract = result?.extract ?: return
        exportError = null
        scope.launch(Dispatchers.IO) {
            try {
                RefineryPipeline.writeJson(extract, target)
                exportedFile = target
                goTo(4)
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
        images.clear()
        imagesNote = null
        running = false
        cancelRequested = false
        extractError = null
        result = null
        currentIndex = -1
        stageReached.clear()
        outcomes.clear()
        console.clear()
        exportedFile = null
        exportError = null
        maxReached = 1
        step = 1
    }

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
        const val CANCELLED_MARKER = " cancelled"

        private const val THUMB_LONG_EDGE = 240
    }
}

/** Human-readable GiB rendering for the hardware rows ("16,0 GB" style, locale-neutral dot). */
fun bytesToGb(bytes: Long?): String? = bytes?.let { String.format("%.1f GB", it / 1073741824.0) }

/** Compact VRAM line for the GPU row: name + VRAM when both are known. */
fun gpuLabel(gpu: GpuInfo?, unknown: String): String = gpu?.name ?: unknown
