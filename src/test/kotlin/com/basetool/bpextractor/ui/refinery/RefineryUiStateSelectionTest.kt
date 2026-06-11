package com.basetool.bpextractor.ui.refinery

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefineryUiStateSelectionTest {

    private fun image(name: String) =
        RefineryImage(File(name), width = 100, height = 50, precropped = false, thumbnail = null)

    @Test
    fun `images are selected by default and toggle off and on`() {
        val state = RefineryUiState()
        val a = image("a.png")
        val b = image("b.png")
        state.images.addAll(listOf(a, b))
        assertEquals(listOf(a, b), state.selectedImages)

        state.toggleImageSelected(a)
        assertEquals(listOf("b.png"), state.selectedImages.map { it.file.name })
        assertFalse(state.images[0].selected)

        state.toggleImageSelected(state.images[0])
        assertEquals(2, state.selectedImages.size)
    }

    @Test
    fun `toggling keeps grid order and the other tiles untouched`() {
        val state = RefineryUiState()
        state.images.addAll(listOf(image("a.png"), image("b.png"), image("c.png")))
        state.toggleImageSelected(state.images[1])
        assertEquals(listOf("a.png", "b.png", "c.png"), state.images.map { it.file.name })
        assertEquals(listOf(true, false, true), state.images.map { it.selected })
    }

    @Test
    fun `startExtraction discards the previous run and drops the stepper ceiling`() {
        val state = RefineryUiState()
        state.images.add(image("a.png"))
        // Simulate a finished run whose review/export was already reachable.
        state.extractError = "boom"
        state.outcomes[0] = com.basetool.bpextractor.refinery.ImageOutcome(
            name = "a.png",
            cropMode = "vlm",
            quoted = true,
            rows = 1,
        )
        state.console += "old line"
        state.goTo(4)

        state.startExtraction()
        assertEquals(2, state.step)
        assertEquals(2, state.maxReached)
        assertEquals(null, state.extractError)
        assertTrue(state.outcomes.isEmpty())
        assertTrue(state.console.isEmpty())
    }
}
