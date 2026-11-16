package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.ui.components.feedback.LocalRipDpiHandOff
import com.poyka.ripdpi.ui.components.feedback.RipDpiHandOffState
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
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
class OnboardingSignalLineHandOffTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the line stays the source of the hand-off after onboarding leaves the screen`() {
        val handOff = RipDpiHandOffState()
        var shown by mutableStateOf(true)
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(LocalRipDpiHandOff provides handOff) {
                    if (shown) {
                        OnboardingSignalLine(OnboardingSignal(RipDpiSignalMode.Live), pagePosition = { 0f })
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val reported = handOff.sourceLine
        assertTrue(reported != null && reported.bounds.width > 0f)
        assertEquals(RipDpiSignalMode.Live, reported?.mode)

        shown = false
        composeRule.waitForIdle()

        assertEquals(reported, handOff.sourceLine)
    }
}
