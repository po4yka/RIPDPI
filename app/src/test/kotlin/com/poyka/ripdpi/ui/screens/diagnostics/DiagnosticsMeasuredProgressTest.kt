package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "en-w500dp-h1000dp-mdpi")
class DiagnosticsMeasuredProgressTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `forward scan progress smooths its fill while semantics report the latest measurement`() {
        val progress =
            mutableStateOf(
                DiagnosticsProgressUiModel(
                    phase = "tcp",
                    summary = "Checking the network",
                    completedSteps = 2,
                    totalSteps = 10,
                    fraction = 0.2f,
                    scanKind = ScanKind.STRATEGY_PROBE,
                    isFullAudit = false,
                    scanStartedAtMs = 0L,
                    phaseSteps = persistentListOf(),
                    currentProbeLabel = "Checking TCP",
                ),
            )
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides DefaultRipDpiMotion,
                    LocalInspectionMode provides false,
                ) {
                    ScanProgressCard(progress.value, strategyProbeSelected = true)
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        val bar = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        val initialPixels = bar.captureToImage().toPixelMap()
        val sampleX = initialPixels.width * 2 / 3
        val sampleY = initialPixels.height / 2
        val track = initialPixels[sampleX, sampleY]

        composeRule.runOnUiThread { progress.value = progress.value.copy(completedSteps = 8, fraction = 0.8f) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()

        bar.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.8f, 0f..1f),
            ),
        )
        val movingPixels = bar.captureToImage().toPixelMap()
        assertEquals(
            "The fill must not jump to the new measurement in one frame",
            track,
            movingPixels[sampleX, sampleY],
        )

        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        val settledPixels = bar.captureToImage().toPixelMap()
        assertNotEquals(
            "The fill must reach the measured value after the finite transition",
            track,
            settledPixels[sampleX, sampleY],
        )
    }

    @Test
    fun `first measured value is displayed without filling from zero`() {
        showProgress(mutableStateOf<Float?>(0.8f))

        assertFillAt(0.65f, filled = true)
        assertMeasured(0.8f)
    }

    @Test
    fun `visual frames do not change the accessible measurement or add a live region`() {
        val progress = mutableStateOf<Float?>(0.2f)
        showProgress(progress)
        val semanticId = bar().fetchSemanticsNode().id

        update { progress.value = 0.8f }
        listOf(0L, 64L, 192L).forEach { elapsed ->
            composeRule.mainClock.advanceTimeBy(elapsed)
            composeRule.waitForIdle()
            assertMeasured(0.8f)
            bar().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            assertEquals(semanticId, bar().fetchSemanticsNode().id)
        }
    }

    @Test
    fun `new scan snaps to its own measurement without replacing the accessible node`() {
        val progress = mutableStateOf<Float?>(0.2f)
        val scanIdentity = mutableStateOf(1L)
        showProgress(progress, scanIdentity = scanIdentity)
        val semanticId = bar().fetchSemanticsNode().id
        update { progress.value = 0.8f }

        update { scanIdentity.value = 2L }

        assertFillAt(0.65f, filled = true)
        assertMeasured(0.8f)
        assertEquals(semanticId, bar().fetchSemanticsNode().id)
    }

    @Test
    fun `backward measurement correction snaps even when the visual fill still lags behind it`() {
        val progress = mutableStateOf<Float?>(0.2f)
        showProgress(progress)
        update { progress.value = 0.8f }
        assertFillAt(0.65f, filled = false)

        update { progress.value = 0.5f }

        assertFillAt(0.4f, filled = true)
        assertMeasured(0.5f)
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        assertFillAt(0.65f, filled = false)
    }

    @Test
    fun `unknown progress remains indeterminate and newly known progress snaps without replacing focus`() {
        val progress = mutableStateOf<Float?>(null)
        showProgress(progress)
        val semanticId = bar().fetchSemanticsNode().id
        bar().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )

        update { progress.value = 0.8f }

        assertFillAt(0.65f, filled = true)
        assertMeasured(0.8f)
        assertEquals(semanticId, bar().fetchSemanticsNode().id)
        update { progress.value = null }
        bar().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
        assertEquals(semanticId, bar().fetchSemanticsNode().id)
    }

    @Test
    fun `disabling motion during interpolation shows the current value and preserves focus`() {
        val progress = mutableStateOf<Float?>(0.2f)
        val motion = mutableStateOf(DefaultRipDpiMotion)
        showProgress(progress, motion = motion)
        val semanticId = bar().fetchSemanticsNode().id
        update { progress.value = 0.8f }
        assertFillAt(0.65f, filled = false)

        update { motion.value = DefaultRipDpiMotion.copy(animationsEnabled = false) }

        assertFillAt(0.65f, filled = true)
        assertMeasured(0.8f)
        assertEquals(semanticId, bar().fetchSemanticsNode().id)
    }

    @Test
    fun `reduced motion shows a new measured value directly`() {
        val progress = mutableStateOf<Float?>(0.2f)
        showProgress(progress, motion = mutableStateOf(DefaultRipDpiMotion.copy(reducedMotion = true)))

        update { progress.value = 0.8f }

        assertFillAt(0.65f, filled = true)
        assertMeasured(0.8f)
    }

    @Test
    fun `inspection mode keeps the fill deterministic`() {
        val progress = mutableStateOf<Float?>(0.2f)
        showProgress(progress, inspectionMode = true)

        update { progress.value = 0.8f }

        assertFillAt(0.65f, filled = true)
        assertMeasured(0.8f)
    }

    @Test
    fun `rapid measured updates settle at the latest value without waiting for older targets`() {
        val progress = mutableStateOf<Float?>(0.1f)
        showProgress(progress)
        update { progress.value = 0.5f }
        update { progress.value = 0.8f }
        assertMeasured(0.8f)
        assertFillAt(0.7f, filled = false)

        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()

        assertFillAt(0.7f, filled = true)
        assertFillAt(0.9f, filled = false)
        assertMeasured(0.8f)
    }

    private fun showProgress(
        progress: MutableState<Float?>,
        scanIdentity: MutableState<Long> = mutableStateOf(1L),
        motion: MutableState<RipDpiMotion> = mutableStateOf(DefaultRipDpiMotion),
        inspectionMode: Boolean = false,
    ) {
        composeRule.setContent {
            RipDpiTheme(themePreference = "light") {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides motion.value,
                    LocalInspectionMode provides inspectionMode,
                ) {
                    Box(Modifier.requiredWidth(300.dp)) {
                        DiagnosticsMeasuredProgress(progress.value, scanIdentity.value)
                    }
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
    }

    private fun update(change: () -> Unit) {
        composeRule.runOnUiThread(change)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
    }

    private fun bar() = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    private fun assertMeasured(value: Float) {
        bar().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(value, 0f..1f),
            ),
        )
    }

    private fun assertFillAt(
        fraction: Float,
        filled: Boolean,
    ) {
        val pixels = bar().captureToImage().toPixelMap()
        val color = pixels[(pixels.width * fraction).toInt(), pixels.height / 2]
        val luminance = (color.red + color.green + color.blue) / 3f
        assertTrue("Expected filled=$filled at fraction=$fraction, actual color=$color", (luminance < 0.5f) == filled)
    }
}
