package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtract
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pipeline orchestration (master plan §9 Phase 3) against a scripted [OllamaApi] —
 * recorded golden-style markdown answers instead of a live GPU: stage order (one image at a
 * time), the keep_alive pin/release protocol, the single location read from the first full
 * frame, failed-read tolerance, cancellation, and the assembled contract document.
 */
class RefineryPipelineTest {

    /** A scripted Ollama: panel answers from a queue, location answers fixed; records keep_alive. */
    private class FakeOllama(
        panelAnswers: List<String>,
        private val locationAnswer: String = "LEVSKI",
    ) : OllamaApi {
        /** (kind, keepAlive) per chat call, in order. */
        val calls = mutableListOf<Pair<String, String>>()
        private val queue = ArrayDeque(panelAnswers)

        override fun installedModels(): List<OllamaModel> = emptyList()

        override fun loadedModels(): List<OllamaModel> = emptyList()

        override fun chat(model: String, prompt: String, imageB64: String, numPredict: Int, keepAlive: String): ChatResult =
            if (prompt.contains("header bar")) {
                calls += "location" to keepAlive
                ChatResult(locationAnswer, "stop")
            } else {
                calls += "panel" to keepAlive
                ChatResult(queue.removeFirst(), "stop")
            }

        override fun pull(model: String, onProgress: (Long?, Long?, String) -> Unit) = error("not used")
    }

    private val fixedNow: () -> Instant = { Instant.parse("2026-06-10T12:00:00Z") }

    private fun pipeline(ollama: OllamaApi, isActive: () -> Boolean = { true }) =
        RefineryPipeline(ollama, model = "qwen3-vl:8b-instruct", toolVersion = "0.0.0-test", now = fixedNow, isActive = isActive)

    /** A dark full frame (no colour anchors → verified fallback geometry, still a full frame). */
    private fun fullFrame(width: Int = 1920, height: Int = 1080): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color(18, 18, 18)
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return img
    }

    /** A pre-cropped portrait panel shot (no terminal header → no location read possible). */
    private fun precropped(): BufferedImage = fullFrame(500, 1400)

    private val upperAnswer = """
        METHOD: FERRON EXCHANGE
        QUOTED: YES
        IN_MANIFEST: 32295
        TO_REFINE: 32295
        TOTAL_COST: 48928
        PROCESSING_TIME: 20H 58M
        CTA: CONFIRM

        | MATERIAL | QUALITY | QTY | YIELD | REFINE |
        | --- | --- | --- | --- | --- |
        | LINDINIUM (ORE) | 618 | 957 | 448 | ON |
        | TUNGSTEN (ORE) | 530 | 1104 | 560 | ON |
    """.trimIndent()

    private val lowerAnswer = """
        METHOD: FERRON EXCHANGE
        QUOTED: YES
        IN_MANIFEST: 32295
        TO_REFINE: 32295
        TOTAL_COST: 48928
        PROCESSING_TIME: 20H 58M
        CTA: CONFIRM

        | MATERIAL | QUALITY | QTY | YIELD | REFINE |
        | --- | --- | --- | --- | --- |
        | TUNGSTEN (ORE) | 530 | 1104 | 560 | ON |
        | INERT MATERIALS | 0 | 5449 | 0 | OFF |
    """.trimIndent()

    @Test
    fun `two scrolled captures assemble into one contract order`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))

        val result = pipeline(ollama).extract(
            listOf(PipelineInput("a_upper.png", fullFrame()), PipelineInput("b_lower.png", fullFrame())),
        )

        val extract = result.extract
        assertEquals("basetool-sc-extractor", extract.tool)
        assertEquals("qwen3-vl:8b-instruct", extract.model)
        assertEquals("2026-06-10T12:00:00Z", extract.generatedAt)
        val order = extract.orders.single()
        assertEquals("LEVSKI", order.rawLocationName)
        assertEquals("FERRON EXCHANGE", order.rawMethodName)
        assertEquals(48928.0, order.expenses)
        assertEquals(20L * 60 + 58, order.durationMinutes)
        assertTrue(order.quoted)
        // Overlap-stitched: LINDINIUM, TUNGSTEN (shared), INERT — in on-screen order.
        assertEquals(
            listOf("LINDINIUM (ORE)", "TUNGSTEN (ORE)", "INERT MATERIALS"),
            order.goods.map { it.rawMaterialName },
        )
        assertEquals(listOf(0, 1, 2), order.goods.map { it.rowIndex })
        assertEquals(2, order.sourceImages.size)
        assertEquals(listOf("vlm", "vlm"), order.sourceImages.map { it.cropMode })
        assertEquals(1920, order.sourceImages[0].width)
    }

    @Test
    fun `keep_alive pins the batch and the final read releases the model`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))

        pipeline(ollama).extract(
            listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
        )

        // Image 0 hosts the location read; image 1's panel read is the final call → keep_alive 0.
        assertEquals(
            listOf("panel" to "10m", "location" to "10m", "panel" to "0"),
            ollama.calls,
        )
    }

    @Test
    fun `a single full-frame capture releases on the location read`() {
        val ollama = FakeOllama(listOf(upperAnswer))

        pipeline(ollama).extract(listOf(PipelineInput("only.png", fullFrame())))

        assertEquals(listOf("panel" to "10m", "location" to "0"), ollama.calls)
    }

    @Test
    fun `pre-cropped input skips the location read and carries no location`() {
        val ollama = FakeOllama(listOf(upperAnswer))

        val result = pipeline(ollama).extract(listOf(PipelineInput("crop.png", precropped())))

        assertEquals(listOf("panel" to "0"), ollama.calls)
        assertNull(result.location)
        assertEquals("precropped", result.extract.orders.single().sourceImages.single().cropMode)
        assertNull(result.extract.orders.single().rawLocationName)
    }

    @Test
    fun `a failed read becomes a null-quoted outcome and the rest continues`() {
        val ollama = FakeOllama(listOf("I cannot help with that.", lowerAnswer))

        val result = pipeline(ollama).extract(
            listOf(PipelineInput("bad.png", fullFrame()), PipelineInput("good.png", fullFrame())),
        )

        assertNull(result.outcomes[0].quoted)
        assertEquals(0, result.outcomes[0].rows)
        assertEquals(true, result.outcomes[1].quoted)
        assertEquals(
            listOf("TUNGSTEN (ORE)", "INERT MATERIALS"),
            result.extract.orders.single().goods.map { it.rawMaterialName },
        )
    }

    @Test
    fun `all reads failing throws instead of exporting an empty order`() {
        val ollama = FakeOllama(listOf("nope", "still nope"))

        assertFailsWith<IllegalStateException> {
            pipeline(ollama).extract(
                listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
            )
        }
    }

    @Test
    fun `cancellation between images aborts the run`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))
        var checks = 0

        assertFailsWith<PipelineCancelledException> {
            pipeline(ollama, isActive = { checks++ < 1 }).extract(
                listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
            )
        }
        // The first image ran; the second never started a read.
        assertEquals(listOf("panel" to "10m", "location" to "10m"), ollama.calls)
    }

    @Test
    fun `stages fire in order with one image active at a time`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))
        val events = mutableListOf<String>()
        val listener = object : PipelineListener {
            override fun onStage(index: Int, name: String, stage: PipelineStage) {
                events += "$index:$stage"
            }

            override fun onImageDone(index: Int, outcome: ImageOutcome) {
                events += "$index:DONE"
            }
        }

        pipeline(ollama).extract(
            listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
            listener,
        )

        assertEquals(
            listOf(
                "0:LOCATE", "0:NORMALIZE", "0:READ", "0:DONE",
                "1:LOCATE", "1:NORMALIZE", "1:READ", "1:DONE",
            ),
            events,
        )
    }

    @Test
    fun `an un-quoted capture is flagged in its outcome`() {
        val unquoted = """
            METHOD: FERRON EXCHANGE
            QUOTED: NO
            IN_MANIFEST: 3941
            TO_REFINE: 3941
            TOTAL_COST: --
            PROCESSING_TIME: --
            CTA: GET QUOTE

            | MATERIAL | QUALITY | QTY | YIELD | REFINE |
            | --- | --- | --- | --- | --- |
            | LINDINIUM (ORE) | 505 | 2837 | -- | ON |
        """.trimIndent()
        val ollama = FakeOllama(listOf(unquoted))

        val result = pipeline(ollama).extract(listOf(PipelineInput("early.png", fullFrame())))

        assertEquals(false, result.outcomes.single().quoted)
        assertTrue(result.validated.isUnquoted())
        assertEquals(false, result.extract.orders.single().quoted)
    }

    @Test
    fun `writeJson emits a decodable contract document`() {
        val ollama = FakeOllama(listOf(upperAnswer))
        val result = pipeline(ollama).extract(listOf(PipelineInput("a.png", fullFrame())))
        val target = File.createTempFile("refinery-extract", ".json")

        try {
            RefineryPipeline.writeJson(result.extract, target)

            val decoded = Json.decodeFromString<RefineryExtract>(target.readText())
            assertEquals(result.extract, decoded)
            // Frozen-contract keys must be present literally even when null-valued.
            assertTrue(target.readText().contains("\"totalYieldScu\""))
        } finally {
            target.delete()
        }
    }
}
