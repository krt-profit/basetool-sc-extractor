package com.basetool.bpextractor.refinery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pure preflight decision logic against the measured Phase 0 hardware tiers
 * (`PHASE0_FINDINGS.md` §5): VRAM → tier/model/ETA, the explicit-override ETA table, the
 * authoritative `ollama ps` fit verdict, and the snapshot's tolerance for failing probes —
 * all with mocked probes, as the master plan demands ("all behind interfaces").
 */
class PreflightTest {

    private companion object {
        const val GIB = 1024L * 1024 * 1024
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
        // No nvidia-smi and no registry value (e.g. some AMD/Intel setups): never silently
        // overload — the ollama-ps fit check after the probe load corrects the picture.
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
        // A different loaded model does not answer for the probed one.
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
