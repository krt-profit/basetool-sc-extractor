package com.basetool.bpextractor.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards class loading and DE/EN parity of the string catalogues: every entry of both objects is
 * touched, forcing both to initialise, and the [Strings] interface is walked by reflection.
 */
class StringsCatalogueTest {

    private val accessors = Strings::class.java.methods.filter { it.parameterCount == 0 }

    @Test
    fun `both catalogues load and answer every entry of the interface`() {
        assertTrue(accessors.size > 100, "reflection should find the whole catalogue, got ${accessors.size}")

        for (catalogue in listOf<Strings>(StringsDe, StringsEn)) {
            val name = catalogue::class.simpleName
            for (accessor in accessors) {
                val value = assertNotNull(accessor.invoke(catalogue), "$name.${accessor.name} is null")
                when (value) {
                    is String ->
                        assertTrue(value.isNotBlank(), "$name.${accessor.name} is blank")
                    is List<*> -> assertNoBlankLeaf(value, "$name.${accessor.name}")
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `the grouped send and account holders are filled in both languages`() {
        for (catalogue in listOf<Strings>(StringsDe, StringsEn)) {
            val name = catalogue::class.simpleName
            with(catalogue.send) {
                listOf(button, consentTitle, consentBody, consentConfirm, authTitle, authBody,
                    authOpenBrowser, waiting, inProgress, resultTitle, resultBody, openInBasetool, saveLocally)
                    .forEach { assertTrue(it.isNotBlank(), "$name.send has a blank entry") }
                assertTrue(authCode("WXYZ-1234").contains("WXYZ-1234"))
                assertTrue(error("boom").contains("boom"))
                assertTrue(errorClientNotAllowed("boom").contains("boom"))
                assertTrue(
                    errorClientNotAllowed("boom").length > error("boom").length,
                    "$name: a permanent refusal needs more than the generic failure line",
                )
                assertTrue(errorDpopNonceRequired("boom").contains("boom"))
                assertTrue(errorClockSkew(-42, "boom").contains("boom"))
                assertTrue(errorClockSkew(-42, "boom").contains("42"), "$name: state the measurement")
                assertTrue(errorClockSkew(42, "boom").contains("42"))
                assertTrue(
                    errorClockSkew(-42, "x") != errorClockSkew(42, "x"),
                    "$name: fast and slow must not read the same",
                )
            }
            with(catalogue.account) {
                listOf(connected, disconnected, disconnect, disconnectTitle, disconnectBody, disconnectConfirm)
                    .forEach { assertTrue(it.isNotBlank(), "$name.account has a blank entry") }
            }
        }
    }

    @Test
    fun `the parameterised export guards render their argument`() {
        for (catalogue in listOf<Strings>(StringsDe, StringsEn)) {
            assertTrue(catalogue.rfSendBlockedMissingQty(3).contains("3"))
            assertTrue(catalogue.rfExportSuccess("C:\\tmp\\x.json").contains("C:\\tmp\\x.json"))
        }
        assertNotEquals(StringsDe.rfSendBlockedNoGoods, StringsEn.rfSendBlockedNoGoods)
        assertNotEquals(StringsDe.rfSendBlockedNoSourceImages, StringsEn.rfSendBlockedNoSourceImages)
    }

    @Test
    fun `the catalogues are distinct objects with distinct wording`() {
        val differing =
            accessors.count { accessor ->
                val de = accessor.invoke(StringsDe)
                val en = accessor.invoke(StringsEn)
                de is String && en is String && de != en
            }
        assertTrue(differing > 50, "expected the catalogues to actually differ, only $differing entries did")
        assertEquals(Lang.entries.size, 2)
    }

    private fun assertNotEquals(a: String, b: String) =
        assertTrue(a != b, "the two catalogues must not share the same sentence")

    /** Walks a catalogue list (help tables nest one level) and rejects empty or blank leaves. */
    private fun assertNoBlankLeaf(value: List<*>, path: String) {
        assertTrue(value.isNotEmpty(), "$path is an empty list")
        value.forEachIndexed { index, entry ->
            when (entry) {
                is String -> assertTrue(entry.isNotBlank(), "$path[$index] is blank")
                is List<*> -> assertNoBlankLeaf(entry, "$path[$index]")
                else -> assertNotNull(entry, "$path[$index] is null")
            }
        }
    }
}
