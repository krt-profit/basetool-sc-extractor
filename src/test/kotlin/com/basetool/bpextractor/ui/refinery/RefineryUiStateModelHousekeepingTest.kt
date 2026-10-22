package com.basetool.bpextractor.ui.refinery

import com.basetool.bpextractor.refinery.ChatResult
import com.basetool.bpextractor.refinery.HardwareTier
import com.basetool.bpextractor.refinery.OllamaApi
import com.basetool.bpextractor.refinery.OllamaModel
import com.basetool.bpextractor.refinery.Preflight
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
        val deleted = CopyOnWriteArrayList<String>()
        var refuseDelete = false

        override fun installedModels() = models.map { OllamaModel(it, 3_300_000_000L, null) }

        override fun loadedModels(): List<OllamaModel> = emptyList()

        override fun chat(model: String, prompt: String, imageB64: String, numPredict: Int, keepAlive: String, numGpu: Int?) =
            ChatResult("", "stop")

        override fun pull(model: String, onProgress: (Long?, Long?, String) -> Unit) {
            onProgress(1L, 2L, "pulling")
            models += model
        }

        override fun unload(model: String) {}

        override fun delete(model: String) {
            if (refuseDelete) error("model is in use")
            deleted += model
            models -= model
        }
    }

    private fun onUi(ollama: FakeOllama, tier: HardwareTier, block: suspend (CoroutineScope, RefineryUiState) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val state = RefineryUiState(clientFor = { ollama })
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
}
