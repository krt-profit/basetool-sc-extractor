package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pure preflight decision logic with mocked probes: VRAM to tier, model and ETA, the override
 * ETA table, the `ollama ps` fit verdict, and tolerance for failing probes.
 */
class PreflightTest {

    private companion object {
        const val GIB = 1024L * 1024 * 1024
    }

    private fun installed(vararg names: String) = names.map { OllamaModel(it, 1L, null) }

    @Test
    fun `on the recommended tier only the fallback model is unneeded`() {
        val all = installed("qwen3-vl:8b-instruct", "qwen3-vl:4b-instruct", "qwen3.5:9b", "llava:13b")

        assertEquals(
            listOf("qwen3-vl:4b-instruct"),
            Preflight.unneededModels(all, HardwareTier.RECOMMENDED).map { it.name },
        )
    }

    @Test
    fun `on the lower tiers the recommended model and the verify partner are unneeded`() {
        val all = installed("qwen3-vl:8b-instruct", "qwen3-vl:4b-instruct", "qwen3.5:9b")

        for (tier in listOf(HardwareTier.MINIMUM, HardwareTier.CPU)) {
            assertEquals(
                listOf("qwen3-vl:8b-instruct", "qwen3.5:9b"),
                Preflight.unneededModels(all, tier).map { it.name },
            )
        }
    }

    @Test
    fun `models the extractor does not pin are never offered for removal`() {
        val all = installed("llava:13b", "gemma4:12b", "qwen3-vl:2b-instruct")

        assertTrue(Preflight.unneededModels(all, HardwareTier.RECOMMENDED).isEmpty())
        assertTrue(Preflight.unneededModels(all, HardwareTier.CPU).isEmpty())
    }

    @Test
    fun `an installed name matches its model with or without a further tag`() {
        assertTrue(Preflight.isModel("qwen3.5:9b", "qwen3.5:9b"))
        assertTrue(Preflight.isModel("qwen3.5:9b:latest", "qwen3.5:9b"))
        assertFalse(Preflight.isModel("qwen3.5:9b-q8_0", "qwen3.5:9b"))
    }

    @Test
    fun `16 GB VRAM selects the recommended model on GPU`() {
        val decision = Preflight.decide(16 * GIB)

        assertEquals(HardwareTier.RECOMMENDED, decision.tier)
        assertEquals(Preflight.MODEL_RECOMMENDED, decision.model)
        assertTrue(decision.gpuMode)
        assertEquals(Preflight.ETA_GPU_RECOMMENDED_S, decision.etaSecondsPerImage)
    }

    @Test
    fun `exactly 12 GB VRAM still makes the recommended tier`() {
        assertEquals(HardwareTier.RECOMMENDED, Preflight.decide(12 * GIB).tier)
    }

    @Test
    fun `an 8 GB card lands on the minimum tier with the 4b fallback`() {
        val decision = Preflight.decide(8 * GIB)

        assertEquals(HardwareTier.MINIMUM, decision.tier)
        assertEquals(Preflight.MODEL_MINIMUM, decision.model)
        assertTrue(decision.gpuMode)
    }

    @Test
    fun `a 6 GB card falls to CPU mode with the slow-mode ETA`() {
        val decision = Preflight.decide(6 * GIB)

        assertEquals(HardwareTier.CPU, decision.tier)
        assertFalse(decision.gpuMode)
        assertEquals(Preflight.ETA_CPU_MINIMUM_S, decision.etaSecondsPerImage)
    }

    @Test
    fun `unknown VRAM decides CPU conservatively`() {
        assertEquals(HardwareTier.CPU, Preflight.decide(null).tier)
    }

    @Test
    fun `the override ETA table matches the measured tiers`() {
        assertEquals(Preflight.ETA_GPU_RECOMMENDED_S, Preflight.etaSecondsPerImage(Preflight.MODEL_RECOMMENDED, gpuMode = true))
        assertEquals(Preflight.ETA_GPU_MINIMUM_S, Preflight.etaSecondsPerImage(Preflight.MODEL_MINIMUM, gpuMode = true))
        assertEquals(Preflight.ETA_CPU_RECOMMENDED_S, Preflight.etaSecondsPerImage(Preflight.MODEL_RECOMMENDED, gpuMode = false))
        assertEquals(Preflight.ETA_CPU_MINIMUM_S, Preflight.etaSecondsPerImage(Preflight.MODEL_MINIMUM, gpuMode = false))
    }

    @Test
    fun `ollama ps with full VRAM residency reads FULL_GPU`() {
        val loaded = listOf(OllamaModel("qwen3-vl:8b-instruct", sizeBytes = 10 * GIB, sizeVramBytes = 10 * GIB))

        assertEquals(GpuFit.FULL_GPU, Preflight.fit(loaded, "qwen3-vl:8b-instruct"))
    }

    @Test
    fun `ollama ps with partial offload reads PARTIAL`() {
        val loaded = listOf(OllamaModel("qwen3-vl:8b-instruct", sizeBytes = 10 * GIB, sizeVramBytes = 7 * GIB))

        assertEquals(GpuFit.PARTIAL, Preflight.fit(loaded, "qwen3-vl:8b-instruct"))
    }

    @Test
    fun `ollama ps without any VRAM share reads CPU_ONLY`() {
        val loaded = listOf(OllamaModel("qwen3-vl:8b-instruct", sizeBytes = 10 * GIB, sizeVramBytes = 0))

        assertEquals(GpuFit.CPU_ONLY, Preflight.fit(loaded, "qwen3-vl:8b-instruct"))
    }

    @Test
    fun `fit is null when the model is not loaded`() {
        assertNull(Preflight.fit(emptyList(), "qwen3-vl:8b-instruct"))
        val other = listOf(OllamaModel("gemma4:12b", sizeBytes = 8 * GIB, sizeVramBytes = 8 * GIB))
        assertNull(Preflight.fit(other, "qwen3-vl:8b-instruct"))
    }

    @Test
    fun `snapshot survives probes that throw`() {
        val snapshot = Preflight.snapshot(
            sc = { error("process iteration failed") },
            ram = { error("no bean") },
            gpu = { error("no nvidia-smi") },
        )

        assertFalse(snapshot.scRunning)
        assertNull(snapshot.totalRamBytes)
        assertNull(snapshot.gpu)
    }

    @Test
    fun `snapshot relays what the mocked probes report`() {
        val snapshot = Preflight.snapshot(
            sc = { true },
            ram = { 64 * GIB },
            gpu = { GpuInfo("NVIDIA GeForce RTX 4080", 16 * GIB) },
        )

        assertTrue(snapshot.scRunning)
        assertEquals(64 * GIB, snapshot.totalRamBytes)
        assertEquals(16 * GIB, snapshot.gpu?.vramBytes)
    }
}
