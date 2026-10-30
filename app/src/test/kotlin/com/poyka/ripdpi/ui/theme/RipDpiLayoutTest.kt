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

    @Test
    fun `large and extra-large widths keep the expanded content width`() {
        val expanded = ripDpiLayoutForWidth(screenWidthDp = 1040)
        val large = ripDpiLayoutForWidth(screenWidthDp = 1300)
        val extraLarge = ripDpiLayoutForWidth(screenWidthDp = 1700)

        assertEquals(RipDpiWidthClass.Large, large.widthClass)
        assertEquals(RipDpiWidthClass.ExtraLarge, extraLarge.widthClass)
        assertEquals(expanded.contentMaxWidth, large.contentMaxWidth)
        assertEquals(expanded.contentMaxWidth, extraLarge.contentMaxWidth)
    }

    @Test
    fun `width classes follow the Material breakpoints`() {
        val expected =
            mapOf(
                599 to RipDpiWidthClass.Compact,
                600 to RipDpiWidthClass.Medium,
                839 to RipDpiWidthClass.Medium,
                840 to RipDpiWidthClass.Expanded,
                1199 to RipDpiWidthClass.Expanded,
                1200 to RipDpiWidthClass.Large,
                1599 to RipDpiWidthClass.Large,
                1600 to RipDpiWidthClass.ExtraLarge,
            )

        expected.forEach { (widthDp, widthClass) ->
            assertEquals("width $widthDp", widthClass, ripDpiWidthClassForWidth(widthDp))
        }
    }
}
