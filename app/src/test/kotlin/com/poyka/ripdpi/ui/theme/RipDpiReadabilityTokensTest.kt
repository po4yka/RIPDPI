package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiReadabilityTokensTest {
    @Test
    fun `primary text uses readable scalable sizes above supporting text`() {
        listOf(RipDpiTypeScale.body, RipDpiTypeScale.bodyEmphasis, RipDpiTypeScale.bodyEmphasisBold).forEach { style ->
            assertEquals(16.sp, style.fontSize)
            assertEquals(24.sp, style.lineHeight)
            assertEquals(GeistSansFamily, style.fontFamily)
        }
        assertEquals(14.sp, RipDpiTypeScale.secondaryBody.fontSize)
        assertEquals(20.sp, RipDpiTypeScale.secondaryBody.lineHeight)
        assertEquals(12.sp, RipDpiTypeScale.caption.fontSize)
        assertEquals(16.sp, RipDpiTypeScale.caption.lineHeight)
        assertTrue(RipDpiTypeScale.body.fontSize.value > RipDpiTypeScale.secondaryBody.fontSize.value)
        assertTrue(RipDpiTypeScale.secondaryBody.fontSize.value > RipDpiTypeScale.caption.fontSize.value)
    }

    @Test
    fun `card titles remain distinct from primary text and section labels`() {
        assertEquals(18.sp, RipDpiTypeScale.cardTitle.fontSize)
        assertEquals(24.sp, RipDpiTypeScale.cardTitle.lineHeight)
        assertEquals(FontWeight.Medium, RipDpiTypeScale.cardTitle.fontWeight)
        assertEquals(0.sp, RipDpiTypeScale.cardTitle.letterSpacing)
        assertEquals(14.sp, RipDpiTypeScale.sectionTitle.fontSize)
        assertTrue(RipDpiTypeScale.cardTitle.fontSize.value > RipDpiTypeScale.body.fontSize.value)
    }

    @Test
    fun `material typography maps primary and supporting roles consistently`() {
        assertEquals(RipDpiTypeScale.bodyEmphasis, RipDpiTypography.titleMedium)
        assertEquals(22.sp, RipDpiTypeScale.sheetTitle.fontSize)
        assertEquals(28.sp, RipDpiTypeScale.sheetTitle.lineHeight)
        assertEquals(RipDpiTypeScale.body, RipDpiTypography.bodyLarge)
        assertEquals(RipDpiTypeScale.secondaryBody, RipDpiTypography.bodyMedium)
        assertEquals(RipDpiTypeScale.caption, RipDpiTypography.bodySmall)
        assertEquals(24.sp, RipDpiTypography.headlineSmall.fontSize)
        assertEquals(32.sp, RipDpiTypography.headlineSmall.lineHeight)
        assertEquals(22.sp, RipDpiTypography.titleLarge.fontSize)
        assertEquals(28.sp, RipDpiTypography.titleLarge.lineHeight)
        assertEquals(16.sp, RipDpiTypography.labelLarge.fontSize)
        assertEquals(24.sp, RipDpiTypography.labelLarge.lineHeight)
    }

    @Test
    fun `standalone icons use material size while inline roles stay compact`() {
        assertEquals(24.dp, RipDpiIconSizes.Default)
        assertEquals(24.dp, RipDpiIconSizes.Medium)
        assertEquals(16.dp, RipDpiIconSizes.Small)
        assertEquals(18.dp, RipDpiIconSizes.Compact)
        assertTrue(RipDpiIconSizes.Default > RipDpiIconSizes.Compact)
    }

    @Test
    fun `decorative badges and inline chip glyphs have separate readable sizes`() {
        val components = DefaultRipDpiComponents
        assertEquals(40.dp, components.feedback.decorativeBadgeSize)
        assertEquals(18.dp, components.inputs.chipIconSize)
        assertTrue(components.feedback.decorativeBadgeSize > RipDpiIconSizes.Large)
        assertTrue(components.inputs.chipIconSize < RipDpiIconSizes.Default)
    }
}
