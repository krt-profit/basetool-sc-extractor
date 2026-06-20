package com.basetool.bpextractor.ui.refinery

import com.basetool.bpextractor.refinery.ExtractWarning
import com.basetool.bpextractor.refinery.PipelineResult
import com.basetool.bpextractor.refinery.RefineryPipeline
import com.basetool.bpextractor.refinery.ValidatedOrder
import com.basetool.bpextractor.refinery.model.RefineryExtract
import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.refinery.model.RefineryExtractImage
import com.basetool.bpextractor.refinery.model.RefineryExtractOrder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the §5.4 manual review corrections: [RefineryUiState.reviewedOrder] overlays the machine
 * read with the user's edits, corrected rows export at [RefineryUiState.CONFIDENCE_MANUAL],
 * reverting restores the machine read, and a new run discards all corrections.
 */
class RefineryUiStateReviewEditTest {

    private fun good(rowIndex: Int, qty: Long? = 46, confidence: Double = 0.75) = RefineryExtractGood(
        rowIndex = rowIndex,
        rawMaterialName = "GOLD (ORE)",
        quality = 553,
        inputQuantity = qty,
        outputQuantity = 21,
        refine = true,
        confidence = confidence,
        sourceImage = "a.png",
    )

    private fun stateWith(
        goods: List<RefineryExtractGood>,
        toRefine: Long? = 1645,
        warnings: Set<ExtractWarning> = emptySet(),
    ): RefineryUiState {
        val order = RefineryExtractOrder(
            panelType = "SETUP",
            quoted = true,
            layoutConfidence = 0.93,
            rawLocationName = null,
            rawMethodName = "DINYX SOLVENTATION",
            rawInManifestTotal = toRefine,
            rawToRefineTotal = toRefine,
            expenses = 831.0,
            durationMinutes = 188,
            sourceImages = listOf(RefineryExtractImage("a.png", 100, 100, "precropped")),
            goods = goods,
        )
        val extract = RefineryExtract(
            tool = "test",
            toolVersion = "0",
            model = "m",
            generatedAt = "2026-06-12T00:00:00Z",
            orders = listOf(order),
        )
        val validated =
            ValidatedOrder(goods, true, order.rawMethodName, toRefine, toRefine, 831.0, 188, 0.93, warnings)
        return RefineryUiState().also { it.result = PipelineResult(extract, validated, null, emptyList()) }
    }

    @Test
    fun `a corrected row overlays the machine read at manual confidence`() {
        val machine = good(rowIndex = 0, qty = 483)
        val state = stateWith(listOf(machine, good(rowIndex = 1)))

        state.editGood(machine, machine.copy(inputQuantity = 403))

        val reviewed = state.reviewedOrder!!
        assertEquals(403L, reviewed.goods[0].inputQuantity)
        assertEquals(RefineryUiState.CONFIDENCE_MANUAL, reviewed.goods[0].confidence)
        assertEquals(46L, reviewed.goods[1].inputQuantity, "untouched rows stay the machine read")
        assertEquals(0.75, reviewed.goods[1].confidence)
    }

    @Test
    fun `the send payload (reviewedExtract JSON) carries the manual correction`() {
        // Order 14, the TUNGSTEN row: the VLM copied the QUALITY value (858) into the QTY cell,
        // but the real input is 850. Before v2.4.0 the "send to basetool" CTA serialized
        // result.extract (the raw machine read) instead of reviewedExtract(), so the basetool
        // received 858 even after the user fixed the cell on the review screen (the JSON-save path
        // already overlaid edits — only send was wrong). This pins the actual wire payload:
        // reviewedExtract() AND its exact JSON bytes must carry the corrected value.
        val machine = good(rowIndex = 0, qty = 858).copy(rawMaterialName = "TUNGSTEN (ORE)", quality = 858)
        val state = stateWith(listOf(machine))

        state.editGood(machine, machine.copy(inputQuantity = 850))

        val sent = state.reviewedExtract()!!
        assertEquals(850L, sent.orders.first().goods.first().inputQuantity)

        val json = RefineryPipeline.toJson(sent)
        assertContains(json, "\"inputQuantity\": 850")
        assertFalse(
            json.contains("\"inputQuantity\": 858"),
            "the raw machine read must never reach the basetool once the row is corrected",
        )
    }

    @Test
    fun `reverting a row restores the machine read and its derived confidence`() {
        val machine = good(rowIndex = 0, qty = 483)
        val state = stateWith(listOf(machine))
        state.editGood(machine, machine.copy(inputQuantity = 403))

        state.revertGood(0)

        val reviewed = state.reviewedOrder!!
        assertEquals(483L, reviewed.goods[0].inputQuantity)
        assertEquals(0.75, reviewed.goods[0].confidence)
        assertTrue(state.editedGoods.isEmpty())
    }

    @Test
    fun `re-typing the machine values drops the row back to un-edited`() {
        val machine = good(rowIndex = 0)
        val state = stateWith(listOf(machine))

        state.editGood(machine, machine.copy())

        assertTrue(state.editedGoods.isEmpty())
        assertEquals(0.75, state.reviewedOrder!!.goods[0].confidence)
    }

    @Test
    fun `header corrections stack and keep the goods overlay`() {
        val machine = good(rowIndex = 0, qty = 483)
        val state = stateWith(listOf(machine))
        state.editGood(machine, machine.copy(inputQuantity = 403))

        state.editHeader { it.copy(rawLocationName = "MIC-L1") }
        state.editHeader { it.copy(expenses = 900.0) }

        val reviewed = state.reviewedOrder!!
        assertEquals("MIC-L1", reviewed.rawLocationName)
        assertEquals(900.0, reviewed.expenses)
        assertEquals("DINYX SOLVENTATION", reviewed.rawMethodName, "earlier values survive stacking")
        assertEquals(403L, reviewed.goods[0].inputQuantity)
    }

    @Test
    fun `correcting the offending quantity resolves the SUM_MISMATCH finding`() {
        // The A10 shape after a manual fix: 483 exceeded the header, the corrected 403 lands it.
        val machine = good(rowIndex = 0, qty = 483)
        val state = stateWith(
            listOf(machine),
            toRefine = 403,
            warnings = setOf(ExtractWarning.SUM_MISMATCH),
        )
        assertTrue(state.resolvedWarnings.isEmpty(), "nothing resolves before an edit")

        state.editGood(machine, machine.copy(inputQuantity = 403))

        assertEquals(setOf(ExtractWarning.SUM_MISMATCH), state.resolvedWarnings)
    }

    @Test
    fun `an edit that does not fix the checksum resolves nothing`() {
        val machine = good(rowIndex = 0, qty = 483)
        val state = stateWith(
            listOf(machine),
            toRefine = 403,
            warnings = setOf(ExtractWarning.SUM_MISMATCH),
        )

        state.editGood(machine, machine.copy(inputQuantity = 480))

        assertTrue(state.resolvedWarnings.isEmpty())
    }

    @Test
    fun `correcting an implausible row resolves the IMPLAUSIBLE_CELL finding`() {
        // A machine row whose yield cell did not parse (HUD bleed-through → null, confidence 0.4).
        val machine = good(rowIndex = 0, qty = 100, confidence = 0.4).copy(outputQuantity = null)
        val state = stateWith(listOf(machine), warnings = setOf(ExtractWarning.IMPLAUSIBLE_CELL))

        state.editGood(machine, machine.copy(outputQuantity = 45))

        assertEquals(setOf(ExtractWarning.IMPLAUSIBLE_CELL), state.resolvedWarnings)
    }

    @Test
    fun `a new extraction discards all review corrections`() {
        val machine = good(rowIndex = 0)
        val state = stateWith(listOf(machine))
        state.editGood(machine, machine.copy(inputQuantity = 999))
        state.editHeader { it.copy(rawLocationName = "MIC-L1") }

        state.startExtraction()

        assertTrue(state.editedGoods.isEmpty())
        assertNull(state.editedOrder)
    }
}
