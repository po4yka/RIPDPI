package com.poyka.ripdpi.ui.screens.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeLatencyDetailsTest {
    @get:Rule val composeRule = createComposeRule()
    private val quality = mutableStateOf<ConnectionQualitySnapshot?>(sample())
    private val source = mutableStateOf<RuntimeMeasurementSource?>(currentSource())
    private val connected = mutableStateOf(true)
    private val bytes = mutableStateOf(204_000L)
    private val motion = mutableStateOf(DefaultRipDpiMotion.copy(animationsEnabled = false))
    private var expectedForeground = 0

    @Test
    fun `selected values remain fixed until details reopen`() {
        render()
        open()
        detailText(R.string.measurement_quality_rtt, 32L).assertExists()
        detailText(R.string.measurement_quality_p95, 64L).assertExists()
        detailText(R.string.measurement_quality_samples, 12L, "7").assertExists()
        detailText(R.string.measurement_source, text(R.string.measurement_proxy)).assertExists()

        composeRule.runOnIdle {
            quality.value = sample().copy(rttP50Ms = 55L, rttP95Ms = 95L, jitterMs = 9L, sampleCount = 20L)
            source.value = currentSource().copy(source = "tunnel")
        }
        detailText(R.string.measurement_quality_rtt, 32L).assertExists()
        detailText(R.string.measurement_quality_p95, 64L).assertExists()
        detailText(R.string.measurement_source, text(R.string.measurement_proxy)).assertExists()
        detailText(R.string.measurement_quality_rtt, 55L).assertDoesNotExist()
        dismiss()
        open()
        detailText(R.string.measurement_quality_rtt, 55L).assertExists()
        detailText(R.string.measurement_quality_p95, 95L).assertExists()
        detailText(R.string.measurement_quality_samples, 20L, "9").assertExists()
        detailText(R.string.measurement_source, text(R.string.measurement_tunnel)).assertExists()
    }

    @Test
    fun `dismiss waits for host window focus before restoring the origin`() {
        val focused = mutableStateOf(true)
        render(windowFocused = focused, inputModeOverride = InputMode.Keyboard)
        open()
        composeRule.runOnIdle { focused.value = false }
        dismiss()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertIsNotFocused()

        composeRule.runOnIdle { focused.value = true }
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertIsFocused()
    }

    @Test
    fun `dismiss restores origin input focus in touch mode`() {
        render(windowFocused = mutableStateOf(true), inputModeOverride = InputMode.Touch)
        open()
        dismiss()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertIsFocused()
    }

    @Test
    fun `a vanished origin cancels pending restoration before later telemetry`() {
        val focused = mutableStateOf(true)
        render(windowFocused = focused, inputModeOverride = InputMode.Touch)
        open()
        composeRule.runOnIdle { focused.value = false }
        dismiss()
        composeRule.runOnIdle { quality.value = null }
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertDoesNotExist()

        composeRule.runOnIdle {
            quality.value = sample()
            focused.value = true
        }
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertIsNotFocused()
    }

    @Test
    fun `clearing current telemetry does not remove selected evidence`() {
        render()
        open()
        composeRule.runOnIdle {
            quality.value = null
            source.value = null
            connected.value = false
            bytes.value = 0L
        }
        detailText(R.string.measurement_quality_p95, 64L).assertExists()
        dismiss()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertDoesNotExist()
    }

    @Test
    fun `no RTT samples does not expose an action or invented zero`() {
        quality.value = ConnectionQualitySnapshot(lossPct = 1f, windowStartAtMs = 1_000L)
        render()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
    }

    @Test
    fun `measured zero RTT remains actionable without invented source`() {
        quality.value = ConnectionQualitySnapshot(sampleCount = 1L)
        source.value = null
        render()
        open()
        detailText(R.string.measurement_quality_rtt, 0L).assertExists()
        detailText(R.string.measurement_quality_p95, 0L).assertExists()
        detailText(R.string.measurement_quality_samples, 1L, "0").assertExists()
        detailText(R.string.latency_detail_source_unavailable).assertExists()
        detailText(R.string.measurement_unknown).assertExists()
        detailText(R.string.measurement_window_unknown).assertExists()
        detailText(R.string.measurement_current).assertDoesNotExist()
    }

    @Test
    fun `disconnected selection does not label an earlier running source current`() {
        connected.value = false
        render()
        open()
        detailText(R.string.measurement_quality_rtt, 32L).assertExists()
        detailText(R.string.home_quality_last_values).assertExists()
        detailText(R.string.measurement_current).assertDoesNotExist()
    }

    @Test
    fun `disabled motion presents and dismisses without an intermediate visual state`() {
        render()
        assertDirectPresentation()
    }

    @Test
    fun `reduced motion presents and dismisses without an intermediate visual state`() {
        motion.value = DefaultRipDpiMotion.copy(reducedMotion = true)
        render()
        assertDirectPresentation()
    }

    @Test
    fun `normal motion has an intermediate frame and settles to visible evidence`() {
        motion.value = DefaultRipDpiMotion
        render()
        composeRule.mainClock.autoAdvance = false
        open()
        composeRule.mainClock.advanceTimeBy(32L)
        val opening = pixels()
        composeRule.mainClock.advanceTimeBy(300L)
        assertFalse("Normal opening must change the rendered surface", opening.contentEquals(pixels()))
        assertVisibleEvidence()
    }

    @Test
    fun `turning off motion settles an opening detail without replay on reenabling`() {
        motion.value = DefaultRipDpiMotion
        render()
        composeRule.mainClock.autoAdvance = false
        open()
        composeRule.mainClock.advanceTimeBy(32L)
        composeRule.runOnIdle { motion.value = DefaultRipDpiMotion.copy(animationsEnabled = false) }
        composeRule.mainClock.advanceTimeByFrame()
        assertVisibleEvidence()
        val direct = pixels()
        composeRule.runOnIdle { motion.value = DefaultRipDpiMotion }
        composeRule.mainClock.advanceTimeByFrame()
        assertArrayEquals(direct, pixels())
        composeRule.mainClock.advanceTimeBy(300L)
        assertArrayEquals(direct, pixels())
    }

    private fun assertDirectPresentation() {
        composeRule.mainClock.autoAdvance = false
        open()
        composeRule.mainClock.advanceTimeBy(32L)
        detailText(R.string.measurement_quality_p95, 64L).assertExists()
        assertVisibleEvidence()
        val first = pixels()
        composeRule.mainClock.advanceTimeBy(300L)
        assertArrayEquals(first, pixels())
        composeRule.onNodeWithTag(RipDpiTestTags.LatencyDetailDismiss).performClick()
        composeRule.mainClock.advanceTimeBy(32L)
        composeRule.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertHasClickAction()
    }

    private fun render(
        windowFocused: State<Boolean>? = null,
        inputModeOverride: InputMode? = null,
    ) {
        composeRule.setContent {
            val hostWindowInfo = LocalWindowInfo.current
            val windowInfo =
                remember(hostWindowInfo, windowFocused) {
                    object : WindowInfo by hostWindowInfo {
                        override val isWindowFocused: Boolean
                            get() = windowFocused?.value ?: hostWindowInfo.isWindowFocused
                    }
                }
            val hostInputMode = LocalInputModeManager.current
            val inputModeManager =
                remember(hostInputMode, inputModeOverride) {
                    object : InputModeManager by hostInputMode {
                        override val inputMode: InputMode
                            get() = inputModeOverride ?: hostInputMode.inputMode
                    }
                }
            RipDpiTheme(themePreference = "light") {
                expectedForeground = RipDpiThemeTokens.colors.foreground.toArgb()
                CompositionLocalProvider(
                    LocalRipDpiMotion provides motion.value,
                    LocalInspectionMode provides false,
                    LocalWindowInfo provides windowInfo,
                    LocalInputModeManager provides inputModeManager,
                ) {
                    HomeConnectionMeasurements(
                        quality = quality.value,
                        source = source.value,
                        connected = connected.value,
                        dataTransferred = bytes.value,
                        onReprobe = {},
                    )
                }
            }
        }
    }

    private fun open() {
        composeRule.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric).assertHasClickAction().performClick()
    }

    private fun dismiss() {
        composeRule.onNodeWithTag(RipDpiTestTags.LatencyDetailDismiss).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
    }

    private fun pixels(): IntArray {
        val bitmap: ImageBitmap = composeRule.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).captureToImage()
        return IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
    }

    private fun assertVisibleEvidence() {
        val bitmap = detailText(R.string.measurement_quality_p95, 64L).captureToImage()
        val values = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        val foregroundPixels = values.count { it == expectedForeground }
        assertTrue("The p95 glyphs must be visible, not only present in semantics", foregroundPixels >= 20)
    }

    private fun detailText(
        resource: Int,
        vararg args: Any,
    ) = composeRule.onNode(
        hasText(text(resource, *args)) and hasAnyAncestor(hasTestTag(RipDpiTestTags.LatencyDetailValues)),
    )

    private fun text(
        resource: Int,
        vararg args: Any,
    ): String = RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun sample() = ConnectionQualitySnapshot(sampleCount = 12L, rttP50Ms = 32L, rttP95Ms = 64L, jitterMs = 7L)

    private fun currentSource() =
        RuntimeMeasurementSource(
            "proxy",
            System.currentTimeMillis(),
            AppStatus.Running,
            RuntimeTelemetryStatus(RuntimeTelemetryState.Snapshot),
        )
}
