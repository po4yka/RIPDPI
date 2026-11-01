package com.poyka.ripdpi.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextDedupeTest {
    @Test
    fun `text equal to the title ignoring case and padding is dropped`() {
        assertNull("Report ready".distinctFrom("Report ready"))
        assertNull("  report READY ".distinctFrom("Report ready"))
    }

    @Test
    fun `different or missing text is kept or stays missing`() {
        assertEquals("Open settings", "Open settings".distinctFrom("Report ready"))
        assertNull(null.distinctFrom("Report ready"))
    }
}
