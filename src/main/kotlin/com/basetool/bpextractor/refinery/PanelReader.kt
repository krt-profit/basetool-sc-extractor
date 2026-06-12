package com.basetool.bpextractor.refinery

/**
 * The Read stage: drives one VLM call per image through [OllamaApi] using the Phase 0 frozen
 * strategy — freeform markdown answer + deterministic reformat ([MarkdownPanelParser]), never
 * schema-forcing (the "Format Tax" cost 0.5 pp accuracy and added the only semantic-class errors,
 * `PHASE0_FINDINGS.md` §2/§4). Temperature 0 and the output budget live in the client; this class
 * adds the one retry at a doubled budget when generation stopped on `length` (truncated table).
 */
class PanelReader(
    private val ollama: OllamaApi,
    private val model: String,
    /** `0` forces CPU-only inference (below-minimum tier); null = Ollama's automatic offload. */
    private val numGpu: Int? = null,
) {

    /**
     * Read one SETUP panel image (base64 PNG) into a [PanelRead]; null when the answer carried no
     * recognizable layout (off-script response — the caller surfaces it as a failed image). Every
     * read pins the model for [KEEP_ALIVE_BATCH]; the pipeline releases via [OllamaApi.unload]
     * when it is done with a model (master plan Phase 3, Ollama integration).
     */
    fun readPanel(imageB64: String): PanelRead? {
        var result = ollama.chat(model, PROMPT, imageB64, NUM_PREDICT, KEEP_ALIVE_BATCH, numGpu)
        if (result.doneReason == "length") {
            result = ollama.chat(model, PROMPT, imageB64, NUM_PREDICT_RETRY, KEEP_ALIVE_BATCH, numGpu)
        }
        return MarkdownPanelParser.parse(result.text)
    }

    /**
     * Read the refinery location from the terminal-header strip (the second read region — the
     * location sits OUTSIDE the work-order panel and is lost on pre-cropped input). 9/9 exact on
     * the Phase 0 golden set at ~3 output tokens.
     */
    fun readLocation(imageB64: String): String? {
        val result = ollama.chat(model, LOCATION_PROMPT, imageB64, NUM_PREDICT_LOCATION, KEEP_ALIVE_BATCH, numGpu)
        val name = result.text.trim().uppercase().trim('.')
        return name.takeUnless { it.isEmpty() || it == "NONE" }
    }

    companion object {
        /** Keep the model pinned between batch reads; [OllamaApi.unload] releases it explicitly. */
        const val KEEP_ALIVE_BATCH = "10m"

        /** Frozen prompt v1 (panel layout + transcription rules + markdown answer format). */
        val PROMPT: String = PanelReader::class.java
            .getResourceAsStream("/refinery/setup_panel_prompt_v1.txt")!!
            .bufferedReader()
            .readText()

        private val LOCATION_PROMPT = """
            This is the header bar of a Star Citizen refinement terminal.
            It shows the station/outpost name on the left (e.g. LEVSKI).
            Reply with ONLY that name, verbatim and uppercase. Ignore everything else.
            If no name is visible, reply NONE.
        """.trimIndent()


        /** Generous output budget for the table (Phase 0 spike value). */
        private const val NUM_PREDICT = 4096

        /** Doubled budget for the one retry after a `length` stop. */
        private const val NUM_PREDICT_RETRY = 8192

        /** The location answer is ~3 tokens. */
        private const val NUM_PREDICT_LOCATION = 64
    }
}
