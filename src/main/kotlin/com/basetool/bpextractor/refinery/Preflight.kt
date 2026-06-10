package com.basetool.bpextractor.refinery

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** What a GPU probe found: the adapter name (if known) and its dedicated VRAM in bytes. */
data class GpuInfo(val name: String?, val vramBytes: Long?)

/**
 * Star-Citizen-process detection (master plan Phase 3 resource safety): the VLM and a running
 * game share GPU/VRAM, so the preflight shows a soft warning while `StarCitizen.exe` is alive.
 * Behind an interface so the decision logic and the UI are testable without a running game.
 */
fun interface ScProcessProbe {
    /** True when a Star Citizen game process is currently running on this machine. */
    fun isStarCitizenRunning(): Boolean
}

/** Total-system-RAM probe (bytes); null when the platform bean does not expose it. */
fun interface RamProbe {
    /** Total physical memory in bytes, or null when unknown. */
    fun totalRamBytes(): Long?
}

/** Dedicated-GPU-VRAM probe; null when no discrete GPU (or no probe path) was found. */
fun interface GpuProbe {
    /** The best available GPU description, or null when nothing could be probed. */
    fun probe(): GpuInfo?
}

/**
 * Production [ScProcessProbe] over [ProcessHandle.allProcesses] — dependency-free and works
 * without elevated rights (processes of other users simply have an empty command). Matches the
 * executable name case-insensitively anywhere in the command path.
 */
class JvmScProcessProbe : ScProcessProbe {
    override fun isStarCitizenRunning(): Boolean =
        ProcessHandle.allProcesses().anyMatch { handle ->
            val command = handle.info().command().orElse("")
            command.substringAfterLast('\\').substringAfterLast('/')
                .equals(SC_EXECUTABLE, ignoreCase = true)
        }

    private companion object {
        /** The live game executable name (same for LIVE/PTU/HOTFIX channels). */
        const val SC_EXECUTABLE = "StarCitizen.exe"
    }
}

/**
 * Production [RamProbe] via `com.sun.management.OperatingSystemMXBean.getTotalMemorySize()` —
 * the reason the slim jlink runtime carries the `jdk.management` module.
 */
class JmxRamProbe : RamProbe {
    override fun totalRamBytes(): Long? =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize
}

/**
 * Production [GpuProbe]: `nvidia-smi` first (exact dedicated VRAM + adapter name), then the
 * display-class registry value `HardwareInformation.qwMemorySize` via `reg query` as the
 * vendor-neutral fallback (a QWORD, so it does not lie above 4 GB the way
 * `Win32_VideoController.AdapterRAM` does). Deliberately NEVER `wmic` — removed from current
 * Windows 11 builds. Both probes are best-effort: any failure yields null and the preflight
 * falls back to the authoritative `ollama ps` fit check after the probe load.
 */
class WindowsGpuProbe : GpuProbe {
    override fun probe(): GpuInfo? = nvidiaSmi() ?: registryQwMemorySize()

    /** `nvidia-smi --query-gpu=name,memory.total` — MiB, CSV, one line per GPU; take the max. */
    private fun nvidiaSmi(): GpuInfo? = runCatching {
        val lines = run(listOf("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"))
        lines.mapNotNull { line ->
            val parts = line.split(',').map { it.trim() }
            val mib = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            GpuInfo(name = parts.getOrNull(0), vramBytes = mib * 1024 * 1024)
        }.maxByOrNull { it.vramBytes ?: 0L }
    }.getOrNull()

    /**
     * Registry fallback: every display adapter under the display class GUID carries
     * `HardwareInformation.qwMemorySize` (REG_QWORD, hex). Take the largest — on hybrid
     * laptops the iGPU entry is the smaller one.
     */
    private fun registryQwMemorySize(): GpuInfo? = runCatching {
        val lines = run(
            listOf(
                "reg", "query",
                """HKLM\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}""",
                "/s", "/v", "HardwareInformation.qwMemorySize",
            ),
        )
        val sizes = lines.mapNotNull { line ->
            QWORD_LINE.find(line)?.groupValues?.get(1)?.removePrefix("0x")?.toLongOrNull(16)
        }
        sizes.maxOrNull()?.let { GpuInfo(name = null, vramBytes = it) }
    }.getOrNull()

    /** Run a short external probe command; non-zero exit or timeout yields an exception. */
    private fun run(command: List<String>): List<String> {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readLines()
        check(process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "probe timed out: $command" }
        check(process.exitValue() == 0) { "probe failed (${process.exitValue()}): $command" }
        return output
    }

    private companion object {
        /** Probes must answer fast — the preflight screen waits on them. */
        const val PROBE_TIMEOUT_SECONDS = 5L

        /** `    HardwareInformation.qwMemorySize    REG_QWORD    0x2fb000000` */
        val QWORD_LINE = Regex("""REG_QWORD\s+(0x[0-9a-fA-F]+)""")
    }
}

/** The three hardware tiers of the Phase 0 measurement (`PHASE0_FINDINGS.md` §5). */
enum class HardwareTier {
    /** ≥ 12 GB VRAM: the bake-off winner runs fully GPU-resident at full accuracy. */
    RECOMMENDED,

    /** ≥ 8 GB VRAM: the low-VRAM fallback model fits; same validated accuracy, 2 known toggle misreads. */
    MINIMUM,

    /** Below minimum (or no usable GPU): CPU-only mode — works, but ~half a minute per image. */
    CPU,
}

/** The preflight outcome: tier, auto-selected model, GPU/CPU mode and the measured per-image ETA. */
data class TierDecision(
    val tier: HardwareTier,
    val model: String,
    val gpuMode: Boolean,
    val etaSecondsPerImage: Int,
)

/** One snapshot of everything the preflight probes found, for the §5.1 hardware card. */
data class HardwareSnapshot(
    val scRunning: Boolean,
    val totalRamBytes: Long?,
    val gpu: GpuInfo?,
)

/** The `ollama ps` fit verdict after a probe load — the authoritative, vendor-neutral signal. */
enum class GpuFit {
    /** The model is fully GPU-resident (`size_vram >= size`). */
    FULL_GPU,

    /** Partially offloaded to system RAM — it runs, but slower than the measured GPU tier. */
    PARTIAL,

    /** Entirely on CPU. */
    CPU_ONLY,
}

/**
 * The pure preflight decision logic (master plan Phase 3 resource safety; measured values from
 * `PHASE0_FINDINGS.md` §5). Kept free of probing side effects so it is unit-testable with mocked
 * probes: VRAM picks the tier and the auto-selected model; the per-image ETA comes from the
 * Phase 0 measurements (RTX-4080-class GPU ~4–5 s, Ryzen-9950X3D-class CPU ~53 s / ~27 s); the
 * `ollama ps` split after the probe load is the final "does it actually fit" arbiter.
 */
object Preflight {

    /** Bytes per GiB. */
    private const val GIB: Long = 1024L * 1024 * 1024

    /** Bake-off winner — markdown read at 0.9872, all residual errors validation-absorbed. */
    const val MODEL_RECOMMENDED = "qwen3-vl:8b-instruct"

    /** Low-VRAM/CPU fallback — same validated accuracy, only the REFINE-toggle class regressed. */
    const val MODEL_MINIMUM = "qwen3-vl:4b-instruct"

    /** Fully-GPU-resident footprint of the 8b model at default context (~10 GB) + headroom. */
    const val VRAM_RECOMMENDED_BYTES: Long = 12L * GIB

    /** Footprint of the 4b fallback (~7.9 GB) — an 8 GB card fits it (barely). */
    const val VRAM_MINIMUM_BYTES: Long = 8L * GIB

    /** Measured GPU read time per image, recommended model (4.2–4.4 s + margin). */
    const val ETA_GPU_RECOMMENDED_S = 5

    /** Measured GPU read time per image, fallback model. */
    const val ETA_GPU_MINIMUM_S = 4

    /** Measured CPU-only read time per image for the fallback model (Ryzen 9950X3D: ~27 s). */
    const val ETA_CPU_MINIMUM_S = 30

    /** Measured CPU-only read time per image for the recommended model (~53 s). */
    const val ETA_CPU_RECOMMENDED_S = 55

    /**
     * Pick the tier + model from probed dedicated VRAM. Unknown VRAM (null — no NVIDIA tool, no
     * registry value, e.g. some AMD/Intel setups) decides CPU conservatively; the UI still offers
     * the GPU models as a manual override and [fit] corrects the picture after the probe load.
     */
    fun decide(vramBytes: Long?): TierDecision = when {
        vramBytes != null && vramBytes >= VRAM_RECOMMENDED_BYTES ->
            TierDecision(HardwareTier.RECOMMENDED, MODEL_RECOMMENDED, gpuMode = true, ETA_GPU_RECOMMENDED_S)
        vramBytes != null && vramBytes >= VRAM_MINIMUM_BYTES ->
            TierDecision(HardwareTier.MINIMUM, MODEL_MINIMUM, gpuMode = true, ETA_GPU_MINIMUM_S)
        else ->
            TierDecision(HardwareTier.CPU, MODEL_MINIMUM, gpuMode = false, ETA_CPU_MINIMUM_S)
    }

    /** The per-image ETA for an explicit (model, gpuMode) override of the auto decision. */
    fun etaSecondsPerImage(model: String, gpuMode: Boolean): Int = when {
        gpuMode && model == MODEL_RECOMMENDED -> ETA_GPU_RECOMMENDED_S
        gpuMode -> ETA_GPU_MINIMUM_S
        model == MODEL_RECOMMENDED -> ETA_CPU_RECOMMENDED_S
        else -> ETA_CPU_MINIMUM_S
    }

    /**
     * Interpret the `ollama ps` answer (after a probe chat loaded [model]) — the authoritative,
     * vendor-neutral fit signal: `size_vram >= size` means fully GPU-resident; a smaller
     * `size_vram` means partial CPU offload; zero/absent means CPU-only. Null when the model is
     * not loaded at all (probe failed or was skipped).
     */
    fun fit(loaded: List<OllamaModel>, model: String): GpuFit? {
        val entry = loaded.firstOrNull { it.name == model || it.name.startsWith("$model:") } ?: return null
        val size = entry.sizeBytes ?: return null
        val vram = entry.sizeVramBytes ?: 0L
        return when {
            vram >= size -> GpuFit.FULL_GPU
            vram > 0L -> GpuFit.PARTIAL
            else -> GpuFit.CPU_ONLY
        }
    }

    /** Collect one probe snapshot for the §5.1 hardware card (each probe individually optional). */
    fun snapshot(sc: ScProcessProbe, ram: RamProbe, gpu: GpuProbe): HardwareSnapshot =
        HardwareSnapshot(
            scRunning = runCatching { sc.isStarCitizenRunning() }.getOrDefault(false),
            totalRamBytes = runCatching { ram.totalRamBytes() }.getOrNull(),
            gpu = runCatching { gpu.probe() }.getOrNull(),
        )
}
