package com.poyka.ripdpi.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.screens.diagnostics.ScanProgressCard
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File

/** Production presentation with controlled snapshots; no scan engine or service is substituted. */
@RunWith(AndroidJUnit4::class)
class UiDiagnosticProgressFeedbackInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val testName = TestName()

    private var imageSequence = 0
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val strings get() = instrumentation.targetContext

    @Test
    fun forwardFillInterpolatesWhileAccessibilityAlreadyHasTheMeasurement() {
        var state by mutableStateOf(progress())
        render { ScanProgressCard(state, strategyProbeSelected = false) }
        compose.mainClock.autoAdvance = false
        val initial = fillFraction()
        compose.runOnUiThread { state = state.copy(completedSteps = 8, fraction = 0.8f) }
        advance(64)
        assertRange(0.8f)
        val moving = fillFraction()
        assertTrue("Rendered fill must advance: $initial -> $moving", moving > initial + 0.03f)
        assertTrue("Rendered fill must still lag the actual measurement: $moving", moving < 0.75f)
        advance(320)
        assertRange(0.8f)
        assertEquals(0.8f, fillFraction(), 0.08f)
    }

    @Test
    fun backwardCorrectionNewScanAndUnknownBoundaryDoNotCarryOldFill() {
        var state by mutableStateOf(progress(fraction = 0.8f))
        render { ScanProgressCard(state, strategyProbeSelected = false) }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state = state.copy(completedSteps = 3, fraction = 0.3f) }
        advance(32)
        assertRange(0.3f)
        assertEquals(0.3f, fillFraction(), 0.08f)
        compose.runOnUiThread {
            state = state.copy(scanStartedAtMs = state.scanStartedAtMs + 1_000, completedSteps = 7, fraction = 0.7f)
        }
        advance(32)
        assertRange(0.7f)
        assertEquals(0.7f, fillFraction(), 0.08f)
        compose.runOnUiThread { state = state.copy(totalSteps = 0, completedSteps = 0) }
        advance(32)
        progressNode().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        compose.runOnUiThread { state = state.copy(totalSteps = 10, completedSteps = 4, fraction = 0.4f) }
        advance(32)
        assertRange(0.4f)
        assertEquals(0.4f, fillFraction(), 0.08f)
    }

    @Test
    fun reducedMotionShowsTheLatestMeasurementAndPhaseDirectly() =
        assertStaticFeedback(RipDpiMotion(reducedMotion = true))

    @Test
    fun disabledMotionShowsTheLatestMeasurementAndPhaseDirectly() =
        assertStaticFeedback(RipDpiMotion(animationsEnabled = false, reducedMotion = true))

    @Test
    fun phaseStatesAndCancellationRemainAvailableAtFont2Rtl() {
        var state by mutableStateOf(progress())
        var cancellations = 0
        render(motion = RipDpiMotion(reducedMotion = true), largeRtl = true) {
            ScanProgressCard(state, strategyProbeSelected = false)
            CancelButton { cancellations++ }
        }
        assertPhase("DNS", PhaseState.Active)
        assertPhase("TCP", PhaseState.Pending)
        compose.runOnUiThread { state = nextPhase(state) }
        assertPhase("DNS", PhaseState.Completed)
        assertPhase("TCP", PhaseState.Active)
        cancel()
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun systemMotionPolicyAndPhaseChangesKeepCancellationReachable() {
        var state by mutableStateOf(progress())
        var cancellations = 0
        var observedMotion: RipDpiMotion? = null
        val talkBack = arguments.getString("diagnosticTalkBack") == "true"
        val talkBackSupport =
            if (talkBack) {
                DiagnosticProgressTalkBackSupport(instrumentation) { timeout, condition ->
                    compose.waitUntil(timeoutMillis = timeout, condition = condition)
                }
            } else {
                null
            }
        render(motion = null) {
            observedMotion = RipDpiThemeTokens.motion
            ScanProgressCard(state, strategyProbeSelected = false)
            CancelButton { cancellations++ }
        }
        compose.runOnIdle {
            assertEquals(ValueAnimator.areAnimatorsEnabled(), observedMotion!!.animationsEnabled)
            if (arguments.getString("diagnosticExpectedAnimationsOff") == "true") {
                assertFalse("The acceptance runner must disable system animations", observedMotion!!.animationsEnabled)
            }
        }
        assertRange(0.2f)
        assertPhase("DNS", PhaseState.Active)
        if (talkBackSupport != null) {
            talkBackSupport.focusInitialPhase()
            talkBackSupport.checkpoint("phase-dns")
            talkBackSupport.focusCancellation()
            talkBackSupport.checkpoint("cancel-focused")
            talkBackSupport.assertCancellationFocus()
        }
        if (!observedMotion!!.animationsEnabled) {
            compose.mainClock.autoAdvance = false
            try {
                listOf(0.8f, 0.2f).forEach { fraction ->
                    compose.runOnUiThread {
                        state =
                            state.copy(fraction = fraction, completedSteps = (fraction * 10).toInt())
                    }
                    advance(32)
                    assertRange(fraction)
                    assertEquals(fraction, fillFraction(talkBackSupport), 0.08f)
                    talkBackSupport?.assertCancellationFocus()
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
        listOf(0.3f, 0.4f, 0.5f).forEach { fraction ->
            compose.runOnUiThread { state = state.copy(fraction = fraction, completedSteps = (fraction * 10).toInt()) }
            assertRange(fraction)
            assertPhase("DNS", PhaseState.Active)
            if (talkBackSupport != null) {
                talkBackSupport.assertCancellationFocus()
                talkBackSupport.checkpoint("counter-${(fraction * 10).toInt()}")
            }
        }
        if (!observedMotion!!.animationsEnabled) compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state = nextPhase(state) }
        if (!observedMotion!!.animationsEnabled) {
            try {
                advance(32)
                assertRange(0.5f)
                assertEquals(0.5f, fillFraction(talkBackSupport), 0.08f)
                compose.onNodeWithText("Probe one").assertDoesNotExist()
                compose.onNodeWithText("Probe two").assertIsDisplayed()
                talkBackSupport?.assertCancellationFocus()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
        assertPhase("DNS", PhaseState.Completed)
        assertPhase("TCP", PhaseState.Active)
        compose.onAllNodes(hasContentDescription("TCP") and politeRegion()).assertCountEquals(1)
        if (talkBackSupport != null) {
            talkBackSupport.assertCancellationFocus()
            talkBackSupport.checkpoint("phase-tcp")
            talkBackSupport.focusCurrentProgress(0.5f)
            talkBackSupport.checkpoint("progress-current")
            talkBackSupport.focusCancellation()
            talkBackSupport.assertCancellationFocus()
            talkBackSupport.checkpoint("cancel-refocused")
            talkBackSupport.checkpoint("activate-cancel")
            compose.waitUntil(timeoutMillis = AcceptanceTimeoutMillis) { cancellations == 1 }
            talkBackSupport.assertBound()
        } else {
            cancel().performScrollTo().assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    private fun assertStaticFeedback(motion: RipDpiMotion) {
        var state by mutableStateOf(progress())
        render(motion = motion) { ScanProgressCard(state, strategyProbeSelected = false) }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state = nextPhase(state).copy(completedSteps = 8, fraction = 0.8f) }
        advance(32)
        assertRange(0.8f)
        assertEquals(0.8f, fillFraction(), 0.08f)
        assertPhase("TCP", PhaseState.Active)
        compose.onNodeWithText("Probe one").assertDoesNotExist()
        compose.onNodeWithText("Probe two").assertIsDisplayed()
    }

    private fun render(
        motion: RipDpiMotion? = RipDpiMotion(),
        largeRtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = if (largeRtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LocalLayoutDirection.current,
            ) {
                RipDpiTheme(themePreference = if (largeRtl) "dark" else "light") {
                    val actualMotion = motion ?: RipDpiThemeTokens.motion
                    CompositionLocalProvider(LocalRipDpiMotion provides actualMotion) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(RipDpiThemeTokens.colors.background)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CancelButton(onCancel: () -> Unit) {
        RipDpiButton(
            text = stringResource(R.string.diagnostics_action_cancel),
            onClick = onCancel,
            modifier = Modifier.testTag(CancelTag),
        )
    }

    private fun assertPhase(
        label: String,
        state: PhaseState,
    ) {
        val description =
            strings.getString(
                when (state) {
                    PhaseState.Pending -> R.string.onboarding_test_row_state_pending
                    PhaseState.Active -> R.string.onboarding_test_row_state_active
                    PhaseState.Completed -> R.string.onboarding_test_row_state_done
                },
            )
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description) and
                    hasText(label),
            ).assertExists()
    }

    private fun progressNode() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    private fun assertRange(value: Float) {
        progressNode().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(value, 0f..1f)),
        )
    }

    /** Count dark fill pixels; rounded caps and the fixed end marker fit within the tolerance. */
    private fun fillFraction(talkBackSupport: DiagnosticProgressTalkBackSupport? = null): Float {
        val image = talkBackSupport?.captureProgressBitmap()?.asImageBitmap() ?: progressNode().captureToImage()
        val directory = File(strings.getExternalFilesDir(null), "diagnostic-progress-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "${testName.methodName}-${++imageSequence}.png").outputStream().use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val pixels = image.toPixelMap()
        val y = pixels.height / 2
        val filled =
            (0 until pixels.width).count { x ->
                val color = pixels[x, y]
                color.alpha > 0.8f && maxOf(color.red, color.green, color.blue) < 0.4f
            }
        return filled.toFloat() / pixels.width
    }

    private fun advance(millis: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun cancel() = compose.onNodeWithTag(CancelTag)

    private fun politeRegion() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    companion object {
        private const val CancelTag = "diagnostic-progress-test-cancel"
        private const val AcceptanceTimeoutMillis = 120_000L
    }
}

private fun progress(fraction: Float = 0.2f) =
    DiagnosticsProgressUiModel(
        phase = "dns",
        summary = "Controlled presentation state",
        completedSteps = (fraction * 10).toInt(),
        totalSteps = 10,
        fraction = fraction,
        scanKind = ScanKind.CONNECTIVITY,
        isFullAudit = false,
        scanStartedAtMs = System.currentTimeMillis() - 2_000,
        phaseSteps =
            persistentListOf(
                PhaseStepUiModel("DNS", PhaseState.Active, DiagnosticsTone.Warning),
                PhaseStepUiModel("TCP", PhaseState.Pending, DiagnosticsTone.Neutral),
            ),
        currentProbeLabel = "Probe one",
    )

private fun nextPhase(previous: DiagnosticsProgressUiModel) =
    previous.copy(
        phase = "tcp",
        currentProbeLabel = "Probe two",
        phaseSteps =
            persistentListOf(
                PhaseStepUiModel("DNS", PhaseState.Completed, DiagnosticsTone.Positive),
                PhaseStepUiModel("TCP", PhaseState.Active, DiagnosticsTone.Warning),
            ),
    )
