package com.basetool.bpextractor.ui.refinery

import androidx.compose.runtime.snapshots.Snapshot
import com.basetool.bpextractor.refinery.ChatResult
import com.basetool.bpextractor.refinery.OllamaApi
import com.basetool.bpextractor.refinery.OllamaModel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

/**
 * Every snapshot-state write of [RefineryUiState] lands on the scope the UI passes in, never on an IO
 * worker: the console's lazy list measured while a worker appended a line threw `Index 3, size 3`.
 */
class RefineryUiStateThreadingTest {

    private class UnreadableOllama : OllamaApi {
        override fun installedModels(): List<OllamaModel> = emptyList()

        override fun loadedModels(): List<OllamaModel> = emptyList()

        override fun chat(model: String, prompt: String, imageB64: String, numPredict: Int, keepAlive: String, numGpu: Int?) =
            ChatResult("no panel here", "stop")

        override fun pull(model: String, onProgress: (Long?, Long?, String) -> Unit) {
            repeat(5) { onProgress(it.toLong(), 5L, "pulling") }
        }

        override fun unload(model: String) {}
    }

    private fun darkFrame(dir: File): File {
        val img = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color(18, 18, 18)
            g.fillRect(0, 0, img.width, img.height)
        } finally {
            g.dispose()
        }
        return File(dir, "capture.png").also { ImageIO.write(img, "png", it) }
    }

    private fun onUiThread(block: suspend (CoroutineScope, RefineryUiState) -> Unit): List<String> {
        val writers = ConcurrentLinkedQueue<String>()
        val executor = Executors.newSingleThreadExecutor { Thread(it, UI_THREAD) }
        val ui = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val observer = Snapshot.registerGlobalWriteObserver { writers += Thread.currentThread().name.substringBefore(" @") }
        try {
            val state = RefineryUiState(clientFor = { UnreadableOllama() })
            runBlocking { block(scope, state) }
        } finally {
            observer.dispose()
            scope.cancel()
            executor.shutdown()
        }
        return writers.toList()
    }

    private suspend fun awaitOnUi(scope: CoroutineScope, describe: () -> String, done: () -> Boolean) {
        val reached = withTimeoutOrNull(30_000) {
            while (!withContext(scope.coroutineContext) { done() }) delay(20)
        }
        if (reached == null) fail("timed out waiting on the UI scope; state: ${describe()}\n${threadDump()}")
    }

    private fun threadDump(): String = Thread.getAllStackTraces()
        .filterKeys { it.name.startsWith(UI_THREAD) || it.name.startsWith("DefaultDispatcher") }
        .entries.joinToString("\n") { (thread, frames) ->
            "${thread.name} ${thread.state}\n" + frames.take(25).joinToString("\n") { "    at $it" }
        }

    @Test
    fun `an extraction run writes its console, stages and result only on the UI scope`() {
        val dir = Files.createTempDirectory("threading-test").toFile()
        try {
            val capture = darkFrame(dir)
            var console = emptyList<String>()
            var error: String? = null
            val writers = onUiThread { scope, state ->
                withContext(scope.coroutineContext) {
                    state.images.add(RefineryImage(capture, 1920, 1080, precropped = false, thumbnail = null))
                    state.runExtraction(scope, "0.0.0-test")
                }
                awaitOnUi(scope, { "running=${state.running} console=${state.console.toList()}" }) { !state.running }
                withContext(scope.coroutineContext) {
                    console = state.console.toList()
                    error = state.extractError
                }
            }
            assertTrue(console.size >= 3, "the run logged its stages: $console")
            assertNotNull(error)
            assertEquals(setOf(UI_THREAD), writers.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a model pull reports its progress only on the UI scope`() {
        var status: OllamaStatus? = null
        val writers = onUiThread { scope, state ->
            withContext(scope.coroutineContext) { state.pullModel(scope) }
            awaitOnUi(scope, { "status=${state.ollamaStatus}" }) { state.ollamaStatus !is OllamaStatus.Pulling }
            withContext(scope.coroutineContext) { status = state.ollamaStatus }
        }
        assertTrue(status is OllamaStatus.ModelMissing, "after the pull the check ran: $status")
        assertEquals(setOf(UI_THREAD), writers.toSet())
    }

    private companion object {
        const val UI_THREAD = "refinery-ui-test"
    }
}
