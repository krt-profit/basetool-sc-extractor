package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtract
import com.basetool.bpextractor.refinery.model.RefineryExtractImage
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.imageio.ImageIO

/** One input screenshot, already decoded — decoding stays at the edge so tests build images. */
data class PipelineInput(val name: String, val image: BufferedImage)

/** The per-image pipeline stages, in run order — the §5.3 stage track of the extraction UI. */
enum class PipelineStage { LOCATE, NORMALIZE, READ }

/** Per-image outcome for the UI: crop mode, quoted state (§5.3 amber warning) and row count. */
data class ImageOutcome(
    val name: String,
    val cropMode: String,
    /** Whether this capture saw the quoted state; null when the read failed entirely. */
    val quoted: Boolean?,
    /** Parsed (non-partial) rows this capture contributed. */
    val rows: Int,
)

/** Progress callbacks for the extraction UI; every method defaults to no-op. */
interface PipelineListener {
    /** [stage] started for image [index] ([name]) — exactly one image is active at a time. */
    fun onStage(index: Int, name: String, stage: PipelineStage) {}

    /** Image [index] finished all stages. */
    fun onImageDone(index: Int, outcome: ImageOutcome) {}

    /** One console line (§5.3 console pane). */
    fun onLog(line: String) {}
}

/** Thrown when the caller's `isActive` gate turned false between images. */
class PipelineCancelledException : RuntimeException("extraction cancelled")

/** The full pipeline result: the contract document + the pre-export review data. */
data class PipelineResult(
    val extract: RefineryExtract,
    val validated: ValidatedOrder,
    val location: String?,
    val outcomes: List<ImageOutcome>,
)

/**
 * Orchestrates one extraction run (master plan §9 Phase 3): for each screenshot — strictly one at
 * a time, matching Ollama's default `OLLAMA_NUM_PARALLEL=1` and the resource-safety throttle —
 * Locate → Normalize → Read, then Stitch across images, Validate (Phase 0 confidence policy) and
 * assemble the frozen [RefineryExtract] contract. The refinery location is read once, from the
 * terminal-header strip of the first full-frame capture (pre-cropped input has none). The model
 * stays pinned (`keep_alive 10m`) across the batch; the final read of the run passes
 * `keep_alive 0` so the VRAM is released as soon as the extraction is done.
 */
class RefineryPipeline(
    private val ollama: OllamaApi,
    private val model: String,
    private val toolVersion: String,
    /** Injectable clock (the contract's `generatedAt`); tests pin it. */
    private val now: () -> Instant = Instant::now,
    /** Cooperative-cancel gate, checked between images. */
    private val isActive: () -> Boolean = { true },
) {

    /**
     * Run the pipeline over [inputs] (all captures of ONE order — 1 folder = 1 order). Throws
     * [PipelineCancelledException] when cancelled and [IllegalStateException] when not a single
     * image produced a readable panel.
     */
    fun extract(inputs: List<PipelineInput>, listener: PipelineListener = object : PipelineListener {}): PipelineResult {
        require(inputs.isNotEmpty()) { "no input images" }
        val reader = PanelReader(ollama, model)

        val reads = mutableListOf<ImageRead>()
        val sourceImages = mutableListOf<RefineryExtractImage>()
        val outcomes = mutableListOf<ImageOutcome>()
        var location: String? = null
        var locationRead = false

        inputs.forEachIndexed { index, input ->
            if (!isActive()) throw PipelineCancelledException()
            val name = input.name

            listener.onStage(index, name, PipelineStage.LOCATE)
            val precropped = Locate.isPrecropped(input.image.width, input.image.height)
            val box = if (precropped) null else Locate.locatePanel(input.image)
            listener.onLog(
                if (box != null) {
                    "· Locate — $name: panel at ${box.x},${box.y} ${box.width}×${box.height}"
                } else {
                    "· Locate — $name: pre-cropped panel, skipping"
                },
            )

            listener.onStage(index, name, PipelineStage.NORMALIZE)
            val prepared = Locate.prepare(input.image, box)
            listener.onLog("· Normalize — $name: ${prepared.readImage.width}×${prepared.readImage.height} (${prepared.cropMode})")

            listener.onStage(index, name, PipelineStage.READ)
            // The location is read from the first capture that has a header strip; the FINAL
            // chat call of the whole run releases the model (keep_alive 0).
            val readsLocationHere = !locationRead && prepared.locationImage != null
            val lastImage = index == inputs.lastIndex
            val panelKeepAlive = if (lastImage && !readsLocationHere) RELEASE else PanelReader.KEEP_ALIVE_BATCH
            val panel = reader.readPanel(toBase64Png(prepared.readImage), panelKeepAlive)
            if (readsLocationHere) {
                val locationKeepAlive = if (lastImage) RELEASE else PanelReader.KEEP_ALIVE_BATCH
                location = reader.readLocation(toBase64Png(prepared.locationImage!!), locationKeepAlive)
                locationRead = true
                listener.onLog("· Location — $name: ${location ?: "not readable"}")
            }

            sourceImages += RefineryExtractImage(
                name = name,
                width = input.image.width,
                height = input.image.height,
                cropMode = prepared.cropMode,
            )
            val outcome = if (panel == null) {
                listener.onLog("⚠ Read — $name: no recognizable panel layout in the answer")
                ImageOutcome(name, prepared.cropMode, quoted = null, rows = 0)
            } else {
                reads += ImageRead(name, panel)
                val rows = panel.rows.count { !it.partial }
                listener.onLog("✓ Read — $name: $rows row(s)${if (!panel.quoted) ", un-quoted (GET-QUOTE state)" else ""}")
                ImageOutcome(name, prepared.cropMode, quoted = panel.quoted, rows = rows)
            }
            outcomes += outcome
            listener.onImageDone(index, outcome)
        }

        check(reads.isNotEmpty()) { "none of the ${inputs.size} image(s) produced a readable panel" }

        val stitched = Stitcher.stitch(reads)
        val validated = Validation.validate(stitched)
        listener.onLog("✓ Stitch — ${validated.goods.size} row(s) from ${reads.size} read(s)")
        if (validated.warnings.isNotEmpty()) {
            listener.onLog("⚠ Validation — ${validated.warnings.joinToString(", ")}")
        }

        val extract = RefineryExtract(
            tool = TOOL,
            toolVersion = toolVersion,
            model = model,
            generatedAt = now().truncatedTo(ChronoUnit.SECONDS).toString(),
            orders = listOf(validated.toContractOrder(sourceImages, location)),
        )
        return PipelineResult(extract, validated, location, outcomes)
    }

    /** Encode a prepared image as base64 PNG for the Ollama `images` field. */
    private fun toBase64Png(img: BufferedImage): String {
        val buffer = ByteArrayOutputStream()
        ImageIO.write(img, "png", buffer)
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }

    companion object {
        /** Contract `tool` field (provenance). */
        const val TOOL = "basetool-sc-extractor"

        /** `keep_alive` value that releases the model right after the call. */
        private const val RELEASE = "0"

        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        /** Write the contract document as pretty-printed JSON (every field explicit). */
        fun writeJson(extract: RefineryExtract, target: File) {
            target.absoluteFile.parentFile?.mkdirs()
            target.writeText(JSON.encodeToString(RefineryExtract.serializer(), extract))
        }
    }
}
