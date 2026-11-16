package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
class RipDpiHandOffRevealTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val state = RipDpiHandOffState()
    private var landings = 0
    private var clicks = 0
    private var revealShown by mutableStateOf(true)

    // The bounded wait for the destination, the reveal, and a margin.
    private val revealMillis =
        2L * (DefaultRipDpiMotion.stateDurationMillis + DefaultRipDpiMotion.signatureDurationMillis)

    @Test
    fun `a complete hand-off lands once, draws nothing after it, and leaves touches to the app`() {
        composeRule.mainClock.autoAdvance = false
        setRevealOverApp()
        val before = composeRule.onRoot().captureToImage().toPixelMap()

        composeRule.runOnIdle { state.start() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        val during = composeRule.onRoot().captureToImage().toPixelMap()
        assertTrue(state.isRunning)
        assertEquals(0, landings)
        assertNotEquals(before[2, 2], during[2, 2])

        composeRule.mainClock.advanceTimeBy(revealMillis)
        val after = composeRule.onRoot().captureToImage().toPixelMap()
        assertFalse(state.isRunning)
        assertEquals(1, landings)
        assertEquals(before[2, 2], after[2, 2])
        assertEquals(before[after.width / 2, after.height / 3], after[after.width / 2, after.height / 3])

        composeRule.onNodeWithTag("app").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, clicks)
    }

    @Test
    fun `a hand-off cancelled on its way leaves nothing drawn, gives no landing, and does not replay`() {
        composeRule.mainClock.autoAdvance = false
        setRevealOverApp()
        val before = composeRule.onRoot().captureToImage().toPixelMap()
        composeRule.runOnIdle { state.start() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue(state.isRunning)

        composeRule.runOnIdle { revealShown = false }
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        assertFalse(state.isRunning)

        composeRule.runOnIdle { revealShown = true }
        composeRule.mainClock.advanceTimeBy(revealMillis)
        val after = composeRule.onRoot().captureToImage().toPixelMap()
        assertFalse(state.isRunning)
        assertEquals(0, landings)
        assertEquals(before[2, 2], after[2, 2])

        composeRule.onNodeWithTag("app").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, clicks)
    }

    @Test
    fun `with animations off the hand-off is a cut that lands once`() {
        composeRule.mainClock.autoAdvance = false
        setRevealOverApp(DefaultRipDpiMotion.copy(animationsEnabled = false))
        val before = composeRule.onRoot().captureToImage().toPixelMap()

        composeRule.runOnIdle { state.start(animated = false) }
        composeRule.mainClock.advanceTimeByFrame()
        val after = composeRule.onRoot().captureToImage().toPixelMap()

        assertFalse(state.isRunning)
        assertEquals(before[2, 2], after[2, 2])
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, landings)
    }

    @Test
    fun `a hand-off whose landing another feedback covers gives no landing`() {
        composeRule.mainClock.autoAdvance = false
        setRevealOverApp()
        composeRule.runOnIdle { state.start() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { state.muteLanding() }

        composeRule.mainClock.advanceTimeBy(revealMillis)

        assertFalse(state.isRunning)
        assertEquals(0, landings)
    }

    @Test
    fun `the line is drawn at its root position in both layout directions`() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        val target = Rect(left = 20f, top = 100f, right = 120f, bottom = 156f)
        state.anchor = Offset(250f, 40f)
        state.start()
        state.targetLine = RipDpiHandOffLine(target, RipDpiSignalMode.Idle, Color.Red)
        state.settle()
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    // A still frame, which the path draws: the host renderer drops a runtime shader.
                    LocalRipDpiMotion provides DefaultRipDpiMotion.copy(reducedMotion = true),
                ) {
                    Box(modifier = Modifier.size(300.dp)) {
                        // The line has landed and the destination has not yet opened.
                        RipDpiHandOffLayer(state = state, progress = { 0.6f })
                    }
                }
            }
        }

        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { layoutDirection ->
            direction = layoutDirection
            composeRule.waitForIdle()
            val pixels = composeRule.onRoot().captureToImage().toPixelMap()
            val y = target.center.y.toInt()
            val onLine = pixels[target.center.x.toInt(), y]
            val mirrored = pixels[pixels.width - target.center.x.toInt(), y]
            assertTrue("$layoutDirection: no line at the destination, found $onLine", onLine.red > onLine.green * 2)
            assertFalse("$layoutDirection: line at the mirrored place", mirrored.red > mirrored.green * 2)
        }
    }

    @Test
    fun `a destination line reports itself only while it is on screen`() {
        var offScreen by mutableStateOf(false)
        var shown by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalRipDpiHandOff provides state) {
                Box(modifier = Modifier.size(300.dp)) {
                    if (shown) {
                        Box(
                            modifier =
                                Modifier
                                    .offset(y = if (offScreen) 4000.dp else 100.dp)
                                    .size(width = 200.dp, height = 56.dp)
                                    .ripDpiHandOffTarget(RipDpiSignalMode.Live, Color.Red),
                        )
                    }
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(RipDpiSignalMode.Live, state.targetLine?.mode)
            assertEquals(56f * composeRule.density.density, state.targetLine?.bounds?.height)
        }

        offScreen = true
        composeRule.runOnIdle { assertNull(state.targetLine) }

        offScreen = false
        composeRule.runOnIdle { assertEquals(RipDpiSignalMode.Live, state.targetLine?.mode) }

        shown = false
        composeRule.runOnIdle { assertNull(state.targetLine) }
    }

    @Test
    fun `the reveal holds the closed veil until the destination reports its line, then the line travels`() {
        composeRule.mainClock.autoAdvance = false
        setRevealOverApp()
        composeRule.runOnIdle { state.start() }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        assertEquals(0f, state.progress)
        assertFalse(state.travels)

        composeRule.runOnIdle {
            state.targetLine = RipDpiHandOffLine(Rect(20f, 100f, 120f, 156f), RipDpiSignalMode.Idle, Color.Blue)
        }
        composeRule.mainClock.advanceTimeBy(DefaultRipDpiMotion.signatureDurationMillis / 2L)

        assertTrue(state.travels)
        assertTrue(state.progress > 0f)
        composeRule.mainClock.advanceTimeBy(revealMillis)
        assertFalse(state.travels)
        assertEquals(1, landings)
    }

    @Test
    fun `with no destination line the reveal starts after a bounded wait`() {
        composeRule.mainClock.autoAdvance = false
        setRevealOverApp()
        composeRule.runOnIdle { state.start() }
        // The idle pass delivers the state write to the composition.
        composeRule.waitForIdle()
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        assertEquals(0f, state.progress)

        composeRule.mainClock.advanceTimeBy(revealMillis)

        assertFalse(state.isRunning)
        assertFalse(state.travels)
        assertEquals(1, landings)
    }

    private fun setRevealOverApp(motion: RipDpiMotion = DefaultRipDpiMotion) {
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(LocalRipDpiMotion provides motion) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Red)
                                    .ripDpiTestTag("app")
                                    .clickable { clicks++ },
                        )
                        if (revealShown) {
                            RipDpiHandOffReveal(state = state, onLanded = { landings++ })
                        }
                    }
                }
            }
        }
    }
}
