package com.basetool.bpextractor.refinery

/**
 * The Read stage: one VLM call per image through [OllamaApi], asking for a freeform markdown answer
 * that [MarkdownPanelParser] reformats deterministically. Retries once at a doubled output budget
 * when generation stopped on `length`.
 */
class PanelReader(
    private val ollama: OllamaApi,
    private val model: String,
    /** `0` forces CPU-only inference (below-minimum tier); null = Ollama's automatic offload. */
    private val numGpu: Int? = null,
    /** Whether the captures come from a German-language client, whose panel labels the prompt then names too. */
    germanClient: Boolean = false,
) {

    private val prompt: String = if (germanClient) PROMPT_GERMAN_CLIENT else PROMPT

    /**
     * Read one SETUP panel image (base64 PNG) into a [PanelRead]; null when the answer carried no
     * recognizable layout (off-script response — the caller surfaces it as a failed image). Every
     * read pins the model for [KEEP_ALIVE_BATCH]; the pipeline releases via [OllamaApi.unload]
     * when it is done with a model (master plan Phase 3, Ollama integration).
     */
    fun readPanel(imageB64: String): PanelRead? {
        var result = ollama.chat(model, prompt, imageB64, NUM_PREDICT, KEEP_ALIVE_BATCH, numGpu)
        if (result.doneReason == "length") {
            result = ollama.chat(model, prompt, imageB64, NUM_PREDICT_RETRY, KEEP_ALIVE_BATCH, numGpu)
        }
        return MarkdownPanelParser.parse(result.text)
    }

    /**
     * Read the refinery location from the terminal-header strip (the second read region — the
     * location sits OUTSIDE the work-order panel and is lost on pre-cropped input). 9/9 exact on
     * the Phase 0 golden set at ~3 output tokens; the location expectations live in the
     * golden-expected file of the [PromptSmokeTest] sweep.
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

        /** The line of [PROMPT] after which [PROMPT_GERMAN_CLIENT] names the German labels. */
        private const val GERMAN_LABELS_AFTER = "- a bottom button labelled either CONFIRM or GET QUOTE\n"

        /**
         * [PROMPT] plus the German client's panel labels, copied from the German pack's `refinery_ui_*`
         * keys; used only for a German-language client so every English read stays on the frozen prompt.
         */
        val PROMPT_GERMAN_CLIENT: String = run {
            val labels = PanelReader::class.java
                .getResourceAsStream("/refinery/setup_panel_prompt_de_labels.txt")!!
                .bufferedReader(Charsets.UTF_8)
                .readText()
            val normalized = PROMPT.replace("\r\n", "\n")
            check(GERMAN_LABELS_AFTER in normalized) { "prompt anchor for the German labels is missing" }
            normalized.replaceFirst(GERMAN_LABELS_AFTER, GERMAN_LABELS_AFTER + labels.replace("\r\n", "\n"))
        }

        private val LOCATION_PROMPT = """
            This is the header area of a Star Citizen refinement terminal.
            It shows the station/outpost name as the most prominent text on the left.
            Reply with ONLY that name, verbatim and uppercase.
            Do not reply with UI labels such as REFINEMENT, REFINERY SYSTEM, WORK ORDER or PROFILE.
            If no station/outpost name is readable, reply NONE — never guess.
        """.trimIndent()


        /** Generous output budget for the table (Phase 0 spike value). */
        private const val NUM_PREDICT = 4096

        /** Doubled budget for the one retry after a `length` stop. */
        private const val NUM_PREDICT_RETRY = 8192

        /** The location answer is ~3 tokens. */
        private const val NUM_PREDICT_LOCATION = 64
    }
}
