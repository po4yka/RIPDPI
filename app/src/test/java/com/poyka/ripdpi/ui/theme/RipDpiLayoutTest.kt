package com.poyka.ripdpi.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiLayoutTest {
    @Test
    fun `compact width resolves compact layout`() {
        val layout = ripDpiLayoutForWidth(screenWidthDp = 390)

        assertEquals(RipDpiWidthClass.Compact, layout.widthClass)
        assertEquals(RipDpiContentGrouping.SingleColumn, layout.contentGrouping)
        assertTrue(layout.formMaxWidth <= layout.contentMaxWidth)
    }

    @Test
    fun `medium width resolves centered form layout`() {
        val layout = ripDpiLayoutForWidth(screenWidthDp = 700)

        assertEquals(RipDpiWidthClass.Medium, layout.widthClass)
        assertEquals(RipDpiContentGrouping.CenteredColumn, layout.contentGrouping)
        assertTrue(layout.horizontalPadding > DefaultRipDpiLayout.horizontalPadding)
        assertTrue(layout.sectionGap > DefaultRipDpiLayout.sectionGap)
    }

    @Test
    fun `expanded width resolves split layout`() {
        val layout = ripDpiLayoutForWidth(screenWidthDp = 1040)

        assertEquals(RipDpiWidthClass.Expanded, layout.widthClass)
        assertEquals(RipDpiContentGrouping.SplitColumns, layout.contentGrouping)
        assertTrue(layout.contentMaxWidth > layout.formMaxWidth)
        assertTrue(layout.groupGap > DefaultRipDpiLayout.groupGap)
    }
}
