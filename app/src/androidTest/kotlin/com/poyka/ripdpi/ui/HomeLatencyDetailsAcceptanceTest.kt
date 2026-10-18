package com.poyka.ripdpi.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.ui.screens.home.HomeConnectionMeasurements
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File

/** Controlled production presentation only. Real reconnect/network acceptance is a separate suite. */
@RunWith(AndroidJUnit4::class)
class HomeLatencyDetailsAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val testName = TestName()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val strings get() = instrumentation.targetContext
    private var expectedForeground = 0

    @Test
    fun measuredDetailsRetainTheClickedSnapshotAndReopenWithNewValues() {
        var quality by mutableStateOf(latencyQuality())
        var source by mutableStateOf(latencySource())
        render { LatencyHome(quality, source) }
        origin()
            .performScrollTo()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        detailText(R.string.measurement_quality_p95, 64L).assertIsDisplayed()
        compose.runOnUiThread {
            quality = latencyQuality().copy(rttP50Ms = 99, rttP95Ms = 128, sampleCount = 32, jitterMs = 8)
            source = source.copy(source = "tunnel", capturedAt = source.capturedAt + 2_000)
        }
        detailText(R.string.measurement_quality_p95, 64L).assertIsDisplayed()
        detailText(R.string.measurement_source, strings.getString(R.string.measurement_proxy)).assertExists()
        dismiss().performScrollTo().performClick()
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
        origin().assertIsFocused().performClick()
        detailText(R.string.measurement_quality_p95, 128L).assertIsDisplayed()
        detailText(R.string.measurement_source, strings.getString(R.string.measurement_tunnel)).assertExists()
    }

    @Test
    fun measuredZeroRetainsItsActionAndActualZeroValues() {
        render { LatencyHome(latencyQuality().copy(rttP50Ms = 0, rttP95Ms = 0, jitterMs = 0, sampleCount = 1)) }
        origin().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        detailText(R.string.measurement_quality_rtt, 0L).assertIsDisplayed()
        detailText(R.string.measurement_quality_p95, 0L).assertIsDisplayed()
        detailText(R.string.measurement_quality_samples, 1L, "0").assertIsDisplayed()
    }

    @Test
    fun lossOnlyAndMissingSamplesNeverExposeALatencyAction() {
        var quality by mutableStateOf<ConnectionQualitySnapshot?>(null)
        render { LatencyHome(quality) }
        listOf(
            null,
            ConnectionQualitySnapshot(lossPct = 10f),
            ConnectionQualitySnapshot(windowStartAtMs = 1_000),
        ).forEach {
            compose.runOnUiThread { quality = it }
            origin().assertDoesNotExist()
            compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
        }
    }

    @Test
    fun missingSourceAndWindowRemainExplicitlyUnavailable() {
        render { LatencyHome(latencyQuality().copy(windowStartAtMs = 0), source = null) }
        origin().performClick()
        detailText(R.string.latency_detail_source_unavailable).assertExists()
        detailText(R.string.measurement_unknown).assertExists()
        detailText(R.string.measurement_window_unknown).assertExists()
        detailText(R.string.measurement_current).assertDoesNotExist()
    }

    @Test
    fun clearingLiveMeasurementsDoesNotDisposeTheSelectedDetails() {
        var quality by mutableStateOf<ConnectionQualitySnapshot?>(latencyQuality())
        var connected by mutableStateOf(true)
        render { LatencyHome(quality, connected = connected) }
        origin().performClick()
        compose.runOnUiThread {
            quality = null
            connected = false
        }
        detailText(R.string.measurement_quality_p95, 64L).assertIsDisplayed()
        dismiss().performScrollTo().performClick()
        origin().assertDoesNotExist()
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
    }

    @Test
    fun normalMotionHasAFiniteEntranceAndExit() {
        render { LatencyHome(latencyQuality()) }
        compose.mainClock.autoAdvance = false
        origin().performClick()
        advance(32)
        val entering = capture("entering")
        advance(256)
        val settled = capture("settled")
        assertVisibleEvidence()
        assertEquals(settled.width, entering.width)
        assertEquals(settled.height, entering.height)
        assertFalse("Normal entrance must change actual rendered pixels", entering.sameAs(settled))
        advance(32)
        assertTrue("The settled pane must stop changing pixels", settled.sameAs(capture("stable")))
        dismiss().performScrollTo().performClick()
        advance(32)
        val leaving = capture("leaving")
        assertEquals(settled.width, leaving.width)
        assertEquals(settled.height, leaving.height)
        assertFalse("Normal exit must change actual rendered pixels", settled.sameAs(leaving))
        advance(256)
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
        origin().assertIsFocused()
    }

    @Test
    fun nativeBackClosesThePaneAndReturnsInputFocus() {
        render { LatencyHome(latencyQuality()) }
        origin().performClick()
        detailText(R.string.measurement_quality_p95, 64L).assertIsDisplayed()
        assertTrue(UiDevice.getInstance(instrumentation).pressKeyCode(KeyEvent.KEYCODE_BACK))
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
        // Input focus is distinct from real TalkBack focus, checked in the dedicated lane.
        origin().assertIsFocused()
    }

    @Test
    fun reducedMotionOpensAndClosesWithoutWaitingForAnimation() = assertDirectMotion(RipDpiMotion(reducedMotion = true))

    @Test
    fun disabledMotionOpensAndClosesWithoutWaitingForAnimation() =
        assertDirectMotion(RipDpiMotion(animationsEnabled = false))

    @Test
    fun changingPolicyDuringEntranceSettlesAndDismissesImmediately() {
        var motion by mutableStateOf(RipDpiMotion())
        render {
            CompositionLocalProvider(LocalRipDpiMotion provides motion) { LatencyHome(latencyQuality()) }
        }
        compose.mainClock.autoAdvance = false
        origin().performClick()
        advance(32)
        compose.runOnUiThread { motion = RipDpiMotion(animationsEnabled = false) }
        advance(32)
        detailText(R.string.measurement_quality_p95, 64L).assertIsDisplayed()
        assertVisibleEvidence()
        val direct = capture("policy-off-settled")
        advance(256)
        assertTrue("Off policy must settle immediately", direct.sameAs(capture("policy-off-stable")))
        dismiss().performScrollTo().performClick()
        advance(32)
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
    }

    @Test
    fun rtlAndFont2KeepTheCompleteValuesAndDismissReachable() {
        render(motion = RipDpiMotion(reducedMotion = true), largeRtl = true) { LatencyHome(latencyQuality()) }
        origin().performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        detailText(R.string.measurement_quality_p95, 64L).performScrollTo().assertIsDisplayed()
        assertVisibleEvidence()
        capture("rtl-font2-values")
        dismiss().performScrollTo().assertIsDisplayed().assertHasClickAction()
        capture("rtl-font2-dismiss")
        dismiss().performClick()
        origin().assertIsFocused()
    }

    @Test
    fun systemMotionPolicyAndRealTalkBackRetainContextAndReturnFocus() {
        val arguments = InstrumentationRegistry.getArguments()
        val explicitlySelected =
            arguments.containsKey("acceptanceRunId") || arguments.containsKey("latencyTalkBack") ||
                arguments.containsKey("latencyExpectedAnimationsOff")
        assumeTrue("External TalkBack acceptance requires its dedicated leased driver", explicitlySelected)
        assertEquals("Use the dedicated external TalkBack lane", "true", arguments.getString("latencyTalkBack"))
        val expectedAnimationsOff =
            checkNotNull(arguments.getString("latencyExpectedAnimationsOff")) {
                "The dedicated runner must specify latencyExpectedAnimationsOff=true or false"
            }.toBooleanStrict()
        val runId = checkNotNull(arguments.getString("acceptanceRunId")) { "A unique acceptanceRunId is required" }
        require(
            runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")),
        ) { "acceptanceRunId must be one safe path token" }
        assertEquals(
            "The runner must set the requested system policy",
            !expectedAnimationsOff,
            ValueAnimator.areAnimatorsEnabled(),
        )
        val support =
            HomeLatencyDetailsTalkBackSupport(instrumentation, runId) { timeout, condition ->
                compose.waitUntil(timeoutMillis = timeout, condition = condition)
            }
        var quality by mutableStateOf(latencyQuality())
        var source by mutableStateOf(latencySource())
        var observedMotion: RipDpiMotion? = null
        render(motion = null) {
            observedMotion = RipDpiThemeTokens.motion
            LatencyHome(quality, source)
        }
        compose.runOnIdle {
            assertEquals(ValueAnimator.areAnimatorsEnabled(), checkNotNull(observedMotion).animationsEnabled)
            assertEquals(
                "Theme motion must match the requested policy",
                !expectedAnimationsOff,
                checkNotNull(observedMotion).animationsEnabled,
            )
        }
        support.focusOrigin("origin-focused")
        support.checkpoint("origin-focused")
        support.checkpoint("open-latency")
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertExists()
        listOf(
            "detail-p50" to strings.getString(R.string.measurement_quality_rtt, 32L),
            "detail-p95" to strings.getString(R.string.measurement_quality_p95, 64L),
            "detail-samples" to strings.getString(R.string.measurement_quality_samples, 12L, "4"),
            "detail-source" to
                strings.getString(R.string.measurement_source, strings.getString(R.string.measurement_proxy)),
        ).forEach { (stage, text) ->
            support.focusText(stage, text)
            support.checkpoint(stage)
        }
        compose.runOnUiThread {
            quality = quality.copy(rttP50Ms = 99, rttP95Ms = 128, sampleCount = 32)
            source = source.copy(source = "tunnel", capturedAt = System.currentTimeMillis())
        }
        detailText(R.string.measurement_quality_p95, 64L).assertExists()
        support.checkpoint("snapshot-retained")
        support.focusDismiss("dismiss-focused")
        support.checkpoint("dismiss-focused")
        support.checkpoint("dismiss-latency")
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
        support.assertOriginFocus()
        support.checkpoint("origin-returned")
        support.checkpoint("reopen-latency")
        detailText(R.string.measurement_quality_p95, 128L).assertExists()
        support.focusText("reopened-p95", strings.getString(R.string.measurement_quality_p95, 128L))
        support.checkpoint("reopened-p95")
        support.checkpoint("back-latency")
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
        support.assertOriginFocus()
        support.checkpoint("origin-returned-back")
    }

    private fun assertDirectMotion(motion: RipDpiMotion) {
        render(motion) { LatencyHome(latencyQuality()) }
        compose.mainClock.autoAdvance = false
        origin().performClick()
        advance(32)
        detailText(R.string.measurement_quality_p95, 64L).assertIsDisplayed()
        assertVisibleEvidence()
        val direct = capture("direct")
        advance(256)
        assertTrue("Static motion must already show its settled pixels", direct.sameAs(capture("direct-stable")))
        dismiss().performScrollTo().performClick()
        advance(32)
        compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).assertDoesNotExist()
    }

    private fun render(
        motion: RipDpiMotion? = RipDpiMotion(),
        largeRtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LocalLayoutDirection.current,
            ) {
                RipDpiTheme(themePreference = if (largeRtl) "dark" else "light") {
                    expectedForeground = RipDpiThemeTokens.colors.foreground.toArgb()
                    CompositionLocalProvider(LocalRipDpiMotion provides (motion ?: RipDpiThemeTokens.motion)) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .ripDpiAutomationTreeRoot()
                                .background(RipDpiThemeTokens.colors.background)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) { content() }
                    }
                }
            }
        }
    }

    private fun origin() = compose.onNodeWithTag(RipDpiTestTags.HomeLatencyMetric)

    private fun dismiss() = compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDismiss)

    private fun detailText(
        resource: Int,
        vararg arguments: Any,
    ) = compose.onNode(
        hasText(strings.getString(resource, *arguments)) and
            hasAnyAncestor(hasTestTag(RipDpiTestTags.LatencyDetailDialog)),
    )

    private fun advance(millis: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun capture(stage: String): Bitmap {
        val bitmap = compose.onNodeWithTag(RipDpiTestTags.LatencyDetailDialog).captureToImage().asAndroidBitmap()
        val directory = File(strings.getExternalFilesDir(null), "latency-details-evidence").apply { mkdirs() }
        File(directory, "${testName.methodName}-$stage.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }

    private fun assertVisibleEvidence() {
        val glyphs = detailText(R.string.measurement_quality_p95, 64L).captureToImage()
        val pixels = IntArray(glyphs.width * glyphs.height).also { glyphs.readPixels(it) }
        assertTrue(
            "Actual p95 glyphs must be visible beyond semantics",
            pixels.count { it == expectedForeground } >= 20,
        )
    }
}

@Composable
private fun LatencyHome(
    quality: ConnectionQualitySnapshot?,
    source: RuntimeMeasurementSource? = latencySource(),
    connected: Boolean = true,
) {
    HomeConnectionMeasurements(quality, source, connected, dataTransferred = 0L, onReprobe = {})
}

private fun latencyQuality() =
    ConnectionQualitySnapshot(rttP50Ms = 32, rttP95Ms = 64, jitterMs = 4, sampleCount = 12, windowStartAtMs = 1_000)

private fun latencySource() =
    RuntimeMeasurementSource(
        "proxy",
        System.currentTimeMillis(),
        AppStatus.Running,
        RuntimeTelemetryStatus(RuntimeTelemetryState.Snapshot),
    )
