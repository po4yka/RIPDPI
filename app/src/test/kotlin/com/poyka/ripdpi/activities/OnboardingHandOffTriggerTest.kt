package com.poyka.ripdpi.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.poyka.ripdpi.ui.components.feedback.RipDpiHandOffReveal
import com.poyka.ripdpi.ui.components.feedback.RipDpiHandOffState
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class OnboardingHandOffTriggerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val handOff = RipDpiHandOffState()
    private var destination by mutableStateOf<Route>(Route.Onboarding)

    @Test
    fun `the first frame after onboarding shows the veil, not the main screen`() {
        composeRule.mainClock.autoAdvance = false
        setContent()

        destination = Route.Status
        composeSwap()
        val firstFrame = composeRule.onRoot().captureToImage().toPixelMap()

        assertTrue(handOff.isRunning)
        assertNotEquals(MainScreenColor, firstFrame[2, 2])
        assertNotEquals(MainScreenColor, firstFrame[firstFrame.width / 2, firstFrame.height / 2])

        composeRule.mainClock.advanceTimeBy(
            2L * (DefaultRipDpiMotion.stateDurationMillis + DefaultRipDpiMotion.signatureDurationMillis),
        )
        val settled = composeRule.onRoot().captureToImage().toPixelMap()
        assertFalse(handOff.isRunning)
        assertEquals(MainScreenColor, settled[2, 2])
    }

    @Test
    fun `the hand-off runs to every destination that follows onboarding`() {
        val destinations = listOf(Route.Status, Route.BiometricPrompt)
        val states = destinations.map { RipDpiHandOffState() }
        var left by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                destinations.forEachIndexed { index, next ->
                    OnboardingHandOffTrigger(states[index], if (left) next else Route.Onboarding)
                }
            }
        }

        left = true
        composeSwap()

        assertEquals(listOf(1, 1), states.map { it.epoch })
    }

    @Test
    fun `a destination change that does not leave onboarding starts no hand-off`() {
        destination = Route.BiometricPrompt
        composeRule.mainClock.autoAdvance = false
        setContent()

        destination = Route.Status
        composeSwap()
        destination = Route.Onboarding
        composeSwap()

        assertEquals(0, handOff.epoch)
    }

    @Test
    fun `with animations off the main screen shows in the first frame`() {
        composeRule.mainClock.autoAdvance = false
        setContent(DefaultRipDpiMotion.copy(animationsEnabled = false))

        destination = Route.Status
        composeSwap()
        val firstFrame = composeRule.onRoot().captureToImage().toPixelMap()

        assertFalse(handOff.isRunning)
        assertEquals(1, handOff.epoch)
        assertEquals(MainScreenColor, firstFrame[2, 2])
    }

    /** The idle pass delivers the state write, and one frame composes it: that frame is the frame of the swap. */
    private fun composeSwap() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun setContent(motion: RipDpiMotion = DefaultRipDpiMotion) {
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(LocalRipDpiMotion provides motion) {
                    OnboardingHandOffTrigger(handOff, destination)
                    Box(modifier = Modifier.fillMaxSize()) {
                        key(destination) {
                            if (destination != Route.Onboarding) {
                                Box(modifier = Modifier.fillMaxSize().background(MainScreenColor))
                            }
                        }
                        RipDpiHandOffReveal(state = handOff)
                    }
                }
            }
        }
    }

    private companion object {
        val MainScreenColor = Color.Red
    }
}
