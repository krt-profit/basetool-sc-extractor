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
                listOf(button, consentTitle, consentBody, consentConfirm, labelTitle, labelHint, labelInvalid,
                    defaultLabel, authTitle, authBody, authOpenBrowser, authKeyUpgrade, authScopeUpgrade, waiting,
                    inProgress, resultTitle, resultBody, openInBasetool, saveLocally, errorNoPersistentKey,
                    errorTokenNotBound)
                    .forEach { assertTrue(it.isNotBlank(), "$name.send has a blank entry") }
                assertTrue(
                    com.basetool.bpextractor.net.InstallationLabel.isValid(defaultLabel),
                    "$name: the offered label must be one the server accepts",
                )
                listOf(errorVersionUnsupported, errorRevoked, errorScopeMissing)
                    .forEach { assertTrue(it("boom").contains("boom"), "$name: a code explanation keeps the detail") }
                assertTrue(authCode("WXYZ-1234").contains("WXYZ-1234"))
                assertTrue(error("boom").contains("boom"))
                assertTrue(errorClientNotAllowed("boom").contains("boom"))
                assertTrue(
                    errorClientNotAllowed("boom").length > error("boom").length,
                    "$name: a permanent refusal needs more than the generic failure line",
                )
                assertTrue(errorClockSkew(-42, "boom").contains("boom"))
                assertTrue(errorClockSkew(-42, "boom").contains("42"), "$name: state the measurement")
                assertTrue(errorClockSkew(42, "boom").contains("42"))
                assertTrue(
                    errorClockSkew(-42, "x") != errorClockSkew(42, "x"),
                    "$name: fast and slow must not read the same",
                )
                listOf(errorUnauthenticated, errorTermsNotAccepted, errorPendingApproval, errorAccountRefused)
                    .forEach { assertTrue(it.isNotBlank(), "$name.send has a blank refusal") }
                listOf(errorSlowDown, errorUnavailable).forEach { text ->
                    assertTrue(text(37).contains("37"), "$name: the server's wait is rendered")
                    assertTrue(text(null).isNotBlank())
                }
                assertTrue(errorQuota(7200).contains("2"), "$name: the quota wait is rendered in hours")
                assertTrue(errorRejected("boom").contains("boom"))
                assertTrue(errorTooManyItems(2345).contains("2345"))
                assertTrue(errorReference("abc-123").contains("abc-123"))
                assertTrue(errorBackOff(17).contains("17"), "$name: the back-off wait is rendered")
            }
            with(catalogue.sync) {
                listOf(button, consentTitle, consentBody, consentConfirm, workingTitle, checkingAccount, syncing,
                    mismatchTitle, mismatchContinue, unconfirmedTitle, unconfirmedContinue, resultTitle, resultNothing,
                    overrideButton)
                    .forEach { assertTrue(it.isNotBlank(), "$name.sync has a blank entry") }
                assertTrue(mismatchBody("Pilot_7").contains("Pilot_7"))
                assertTrue(unconfirmedBody("Pilot_7").contains("Pilot_7"))
                assertTrue(resultAdded(3, 9).contains("3") && resultAdded(3, 9).contains("9"))
                listOf(resultRemovedElsewhere, resultUnmatched, resultAmbiguous, resultRefused)
                    .forEach { assertTrue(it(4).contains("4"), "$name: a sync count is rendered") }
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
