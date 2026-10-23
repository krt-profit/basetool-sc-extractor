package com.basetool.bpextractor.ui.refinery

import com.basetool.bpextractor.refinery.ChatResult
import com.basetool.bpextractor.refinery.GpuInfo
import com.basetool.bpextractor.refinery.HardwareSnapshot
import com.basetool.bpextractor.refinery.HardwareTier
import com.basetool.bpextractor.refinery.ModelRegistry
import com.basetool.bpextractor.refinery.OllamaApi
import com.basetool.bpextractor.refinery.OllamaModel
import com.basetool.bpextractor.refinery.Preflight
import com.basetool.bpextractor.refinery.RegistryVersion
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The preflight's model housekeeping: the verify partner's download offer and the removal of unneeded models. */
class RefineryUiStateModelHousekeepingTest {

    private class FakeOllama(vararg installed: String) : OllamaApi {
        val models = CopyOnWriteArrayList(installed.toList())
        val digests = java.util.concurrent.ConcurrentHashMap<String, String>()
        val deleted = CopyOnWriteArrayList<String>()
        var refuseDelete = false

        override fun installedModels() = models.map { OllamaModel(it, 3_300_000_000L, null, digests[it] ?: OLD_DIGEST) }

        override fun loadedModels(): List<OllamaModel> = emptyList()

        override fun chat(model: String, prompt: String, imageB64: String, numPredict: Int, keepAlive: String, numGpu: Int?) =
            ChatResult("", "stop")

        override fun pull(model: String, onProgress: (Long?, Long?, String) -> Unit) {
            onProgress(1L, 2L, "pulling")
            if (model !in models) models += model
            digests[model] = NEW_DIGEST
        }

        override fun unload(model: String) {}

        override fun delete(model: String) {
            if (refuseDelete) error("model is in use")
            deleted += model
            models -= model
        }
    }

    private fun onUi(
        ollama: FakeOllama,
        tier: HardwareTier,
        registry: ModelRegistry = ModelRegistry { null },
        block: suspend (CoroutineScope, RefineryUiState) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val state = RefineryUiState(clientFor = { ollama }, registry = registry)
        try {
            runBlocking {
                withContext(scope.coroutineContext) {
                    state.decision = Preflight.decide(if (tier == HardwareTier.RECOMMENDED) 16L shl 30 else 8L shl 30)
                    state.recheckOllama(scope)
                }
                await(scope) { state.ollamaStatus == OllamaStatus.Ready }
                block(scope, state)
            }
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    private suspend fun await(scope: CoroutineScope, done: () -> Boolean) {
        withTimeoutOrNull(10_000) {
            while (!withContext(scope.coroutineContext) { done() }) delay(10)
        } ?: fail("timed out on the UI scope")
    }

    @Test
    fun `the missing verify partner is offered and pulled into use`() {
        val ollama = FakeOllama("qwen3-vl:8b-instruct")
        onUi(ollama, HardwareTier.RECOMMENDED) { scope, state ->
            withContext(scope.coroutineContext) {
                assertEquals(Preflight.MODEL_VERIFY, state.verifyOffer)
                assertNull(state.verifyModel)
                state.pullVerifyModel(scope)
            }
            await(scope) { state.modelTask == null && state.verifyAvailable }
            withContext(scope.coroutineContext) {
                assertNull(state.verifyOffer)
                assertEquals(Preflight.MODEL_VERIFY, state.verifyModel)
            }
        }
    }

    @Test
    fun `no verify partner is offered below the recommended tier`() {
        onUi(FakeOllama("qwen3-vl:4b-instruct"), HardwareTier.MINIMUM) { scope, state ->
            withContext(scope.coroutineContext) { assertNull(state.verifyOffer) }
        }
    }

    @Test
    fun `an unneeded extractor model is offered and removed`() {
        val ollama = FakeOllama("qwen3-vl:8b-instruct", "qwen3.5:9b", "qwen3-vl:4b-instruct")
        onUi(ollama, HardwareTier.RECOMMENDED) { scope, state ->
            withContext(scope.coroutineContext) {
                assertEquals(listOf("qwen3-vl:4b-instruct"), state.removableModels.map { it.name })
                state.removeModel(scope, "qwen3-vl:4b-instruct")
            }
            await(scope) { state.modelTask == null && state.removableModels.isEmpty() }
            assertEquals(listOf("qwen3-vl:4b-instruct"), ollama.deleted.toList())
        }
    }

    @Test
    fun `a model the tier still needs is never removed`() {
        val ollama = FakeOllama("qwen3-vl:8b-instruct", "qwen3.5:9b")
        onUi(ollama, HardwareTier.RECOMMENDED) { scope, state ->
            withContext(scope.coroutineContext) {
                assertTrue(state.removableModels.isEmpty())
                state.removeModel(scope, "qwen3-vl:8b-instruct")
                assertNull(state.modelTask)
            }
            assertTrue(ollama.deleted.isEmpty())
        }
    }

    @Test
    fun `a refused removal is reported and the model stays offered`() {
        val ollama = FakeOllama("qwen3-vl:8b-instruct", "qwen3-vl:4b-instruct").apply { refuseDelete = true }
        onUi(ollama, HardwareTier.RECOMMENDED) { scope, state ->
            withContext(scope.coroutineContext) { state.removeModel(scope, "qwen3-vl:4b-instruct") }
            await(scope) { state.modelTask is ModelTask.Failed }
            withContext(scope.coroutineContext) {
                val failed = state.modelTask as ModelTask.Failed
                assertTrue(failed.removal)
                assertEquals("model is in use", failed.message)
                assertEquals(listOf("qwen3-vl:4b-instruct"), state.removableModels.map { it.name })
            }
        }
    }

    private val newerEverywhere = ModelRegistry { RegistryVersion(NEW_DIGEST, 6_200_000_000L) }

    @Test
    fun `a model whose registry tag moved is offered and updated`() {
        val ollama = FakeOllama("qwen3-vl:8b-instruct", "qwen3.5:9b")
        onUi(ollama, HardwareTier.RECOMMENDED, newerEverywhere) { scope, state ->
            await(scope) { state.updatableModels.size == 2 }
            withContext(scope.coroutineContext) {
                assertEquals(
                    listOf(ModelUpdate("qwen3-vl:8b-instruct", 6_200_000_000L), ModelUpdate("qwen3.5:9b", 6_200_000_000L)),
                    state.updatableModels,
                )
                state.updateModel(scope, "qwen3-vl:8b-instruct")
            }
            await(scope) { state.modelTask == null && state.updatableModels.size == 1 }
            withContext(scope.coroutineContext) {
                assertEquals(listOf("qwen3.5:9b"), state.updatableModels.map { it.model })
            }
        }
    }

    @Test
    fun `an up-to-date model, an unanswered registry and an unneeded model offer no update`() {
        val current = ModelRegistry { RegistryVersion(OLD_DIGEST, 1L) }
        onUi(FakeOllama("qwen3-vl:8b-instruct"), HardwareTier.RECOMMENDED, current) { scope, state ->
            delay(200)
            withContext(scope.coroutineContext) { assertTrue(state.updatableModels.isEmpty()) }
        }
        onUi(FakeOllama("qwen3-vl:8b-instruct"), HardwareTier.RECOMMENDED) { scope, state ->
            delay(200)
            withContext(scope.coroutineContext) { assertTrue(state.updatableModels.isEmpty()) }
        }
        onUi(FakeOllama("qwen3-vl:8b-instruct", "qwen3-vl:4b-instruct"), HardwareTier.RECOMMENDED, newerEverywhere) { scope, state ->
            await(scope) { state.updatableModels.isNotEmpty() }
            withContext(scope.coroutineContext) {
                assertEquals(listOf("qwen3-vl:8b-instruct"), state.updatableModels.map { it.model })
            }
        }
    }

    @Test
    fun `the registry is asked once per model and session`() {
        val asked = CopyOnWriteArrayList<String>()
        val counting = ModelRegistry { model -> asked += model; null }
        onUi(FakeOllama("qwen3-vl:8b-instruct"), HardwareTier.RECOMMENDED, counting) { scope, state ->
            withContext(scope.coroutineContext) { state.recheckOllama(scope) }
            await(scope) { state.ollamaStatus == OllamaStatus.Ready }
            withContext(scope.coroutineContext) { state.recheckOllama(scope) }
            await(scope) { state.ollamaStatus == OllamaStatus.Ready }
            delay(200)
        }
        assertEquals(listOf("qwen3-vl:8b-instruct"), asked.toList())
    }

    @Test
    fun `the start-up check offers the update without running the preflight`() {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val probes = java.util.concurrent.atomic.AtomicInteger()
        val state = RefineryUiState(
            clientFor = { FakeOllama("qwen3-vl:8b-instruct", "qwen3.5:9b") },
            registry = newerEverywhere,
            probeHardware = {
                probes.incrementAndGet()
                HardwareSnapshot(scRunning = false, totalRamBytes = 32L shl 30, gpu = GpuInfo("GPU", 16L shl 30))
            },
        )
        try {
            runBlocking {
                withContext(scope.coroutineContext) { state.checkModelUpdates(scope) }
                await(scope) { state.updatableModels.size == 2 }
                withContext(scope.coroutineContext) { state.runPreflight(scope) }
                await(scope) { probes.get() == 2 && state.ollamaStatus == OllamaStatus.Ready }
            }
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    @Test
    fun `no start-up offer without Ollama`() {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val state = RefineryUiState(
            clientFor = {
                object : OllamaApi by FakeOllama() {
                    override fun installedModels(): List<OllamaModel> = error("connection refused")
                }
            },
            registry = newerEverywhere,
            probeHardware = { HardwareSnapshot(scRunning = false, totalRamBytes = null, gpu = null) },
        )
        try {
            runBlocking {
                withContext(scope.coroutineContext) { state.checkModelUpdates(scope) }
                await(scope) { state.ollamaStatus is OllamaStatus.Unreachable }
                withContext(scope.coroutineContext) { assertTrue(state.updatableModels.isEmpty()) }
            }
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    private companion object {
        const val OLD_DIGEST = "0533d74"
        const val NEW_DIGEST = "70e58e37"
    }
}
