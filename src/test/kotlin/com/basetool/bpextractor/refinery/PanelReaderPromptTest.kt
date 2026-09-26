package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.ScLocalization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelReaderPromptTest {

    @Test
    fun `the frozen English prompt names no German label`() {
        assertFalse("Frachtliste" in PanelReader.PROMPT)
        assertFalse("BESTÄTIGEN" in PanelReader.PROMPT)
    }

    @Test
    fun `the German client's prompt is the frozen one plus the German labels after the button line`() {
        val english = PanelReader.PROMPT.replace("\r\n", "\n")
        val german = PanelReader.PROMPT_GERMAN_CLIENT
        assertTrue("Frachtliste" in german && "ANGEBOT EINHOLEN" in german && "AUSGEWÄHLTE MATERIALIEN" in german)
        val anchor = "- a bottom button labelled either CONFIRM or GET QUOTE\n"
        assertEquals(english.substringBefore(anchor), german.substringBefore(anchor))
        assertEquals(english.substringAfter(anchor), german.substringAfter("there too.\n"))
    }

    @Test
    fun `German installs are recognised by their g_language value`() {
        assertTrue(ScLocalization.isGerman("german_(germany)"))
        assertTrue(ScLocalization.isGerman(" German_(Switzerland) "))
        assertFalse(ScLocalization.isGerman("english"))
        assertFalse(ScLocalization.isGerman(null))
    }
}
