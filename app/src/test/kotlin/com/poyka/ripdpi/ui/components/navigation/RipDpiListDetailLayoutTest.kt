package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiListDetailLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w1300dp-h900dp")
    fun wideWindowAtDefaultFontShowsTwoPanes() {
        assertTrue(twoPaneAt(fontScale = 1f))
    }

    @Test
    @Config(qualifiers = "w860dp-h900dp")
    fun expandedWindowWithLargeFontFallsBackToOnePane() {
        // 860dp minus the expanded rail leaves less than the minimum list and detail panes need at 1.5x.
        assertFalse(twoPaneAt(fontScale = 1.5f))
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun compactWindowShowsOnePane() {
        assertFalse(twoPaneAt(fontScale = 1f))
    }

    private fun twoPaneAt(fontScale: Float): Boolean {
        var twoPane = false
        composeRule.setContent {
            RipDpiTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    twoPane = rememberListDetailTwoPane()
                }
            }
        }
        composeRule.waitForIdle()
        return twoPane
    }
}
