package com.basetool.bpextractor.ui

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The device code reaches the system clipboard exactly as shown. */
class CopyToClipboardTest {

    @Test
    fun `the code lands on the clipboard verbatim`() {
        if (GraphicsEnvironment.isHeadless()) return
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val before = runCatching { clipboard.getContents(null) }.getOrNull()
        try {
            assertTrue(copyToClipboard("WXYZ-1234"))
            assertEquals("WXYZ-1234", clipboard.getData(DataFlavor.stringFlavor))
        } finally {
            if (before != null) runCatching { clipboard.setContents(before, null) }
        }
    }
}
