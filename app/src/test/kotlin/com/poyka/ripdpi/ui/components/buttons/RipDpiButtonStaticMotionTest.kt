package com.poyka.ripdpi.ui.components.buttons

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiButtonStaticMotionTest {
    @Test
    fun `loading indicator is static for inspection and reduced motion`() {
        assertTrue(shouldUseStaticButtonLoadingIndicator(isInspectionMode = true, allowsInfiniteMotion = true))
        assertTrue(shouldUseStaticButtonLoadingIndicator(isInspectionMode = false, allowsInfiniteMotion = false))
        assertFalse(shouldUseStaticButtonLoadingIndicator(isInspectionMode = false, allowsInfiniteMotion = true))
    }
}
