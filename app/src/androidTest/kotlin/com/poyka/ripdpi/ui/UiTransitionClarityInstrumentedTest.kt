package com.poyka.ripdpi.ui

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.activities.HomeAppliedConfigurationUiState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.HomeReconnectComparisonUiState
import com.poyka.ripdpi.activities.ReconnectSettingsIdentity
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.RunningReconnectRequest
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.screens.history.HistoryScreen
import com.poyka.ripdpi.ui.screens.home.HomeAppliedConfigurationPanel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production UI with controlled state; no service, network, or settings persistence is exercised. */
@RunWith(AndroidJUnit4::class)
class UiTransitionClarityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun historyForwardAndReverseMovePhysicallyInLtr() = assertHistoryRoundTrip(largeRtl = false)

    @Test
    fun historyForwardAndReverseMovePhysicallyAtFont2Rtl() = assertHistoryRoundTrip(largeRtl = true)

    @Test
    fun terminalIndicatorFadesWhileCurrentActionAndCancellationStayAvailable() {
        var state by mutableStateOf(transitionActuator(HomeConnectionActuatorStatus.Engaging))
        var cancellations = 0
        compose.setContent {
            TransitionTheme {
                RipDpiConnectionActuator(
                    state,
                    {},
                    { cancellations++ },
                    testTag = RipDpiTestTags.ConnectionActuatorButton,
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
        listOf(HomeConnectionActuatorStatus.Locked, HomeConnectionActuatorStatus.Fault).forEach { terminal ->
            compose.runOnUiThread { state = transitionActuator(HomeConnectionActuatorStatus.Engaging) }
            advance(160)
            compose.runOnUiThread { state = transitionActuator(terminal) }
            advance(32)
            assertCurrentActuator(terminal)
            progress().assertExists()
            capture("actuator-${terminal.name}-fading")
            advance(RipDpiMotion().quickDurationMillis.toLong() + 32)
            progress().assertDoesNotExist()
            assertCurrentActuator(terminal)
        }
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun disabledMotionReplacesTheIndicatorImmediately() = assertIndicatorExit(RipDpiMotion(animationsEnabled = false))

    @Test
    fun reducedMotionKeepsAShortFadeAtFont2Rtl() = assertIndicatorExit(RipDpiMotion(reducedMotion = true))

    @Test
    fun reconnectReviewCancelsWithoutDispatchAndFreshConfirmDispatchesOnce() = assertReconnectReview(largeRtl = false)

    @Test
    fun reconnectReviewCanScrollComparisonAndBothActionsAtFont2Rtl() = assertReconnectReview(largeRtl = true)

    @Test
    fun reviewClosesWithoutDispatchAfterRuntimeOrSavedIdentityChanges() {
        var state by mutableStateOf(transitionReconnectState())
        var reconnects = 0
        compose.setContent { TransitionTheme { HomeAppliedConfigurationPanel(state, { reconnects++ }, {}) } }
        listOf<(HomeAppliedConfigurationUiState) -> HomeAppliedConfigurationUiState>(
            { it.copy(confirmation = RunningReconnectRequest.ConfirmedRuntime(Mode.VPN, "replacement-runtime", 2)) },
            { it.copy(reconnectSettingsIdentity = ReconnectSettingsIdentity()) },
        ).forEachIndexed { index, invalidate ->
            compose.onNodeWithTag("home_runtime_reconnect").performClick()
            compose.runOnIdle { state = invalidate(state) }
            compose.onNodeWithTag(RipDpiTestTags.HomeRuntimeReconnectDialog).assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, reconnects) }
            capture("reconnect-invalidated-$index")
        }
    }

    private fun assertHistoryRoundTrip(largeRtl: Boolean) {
        var section by mutableStateOf(HistorySection.Connections)
        compose.setContent {
            TransitionTheme(largeRtl = largeRtl) {
                TransitionHistory(HistoryUiState(selectedSection = section)) { section = it }
            }
        }
        compose.mainClock.autoAdvance = false
        listOf(HistorySection.Diagnostics, HistorySection.Connections).forEachIndexed { index, target ->
            val oldTag = historyListTag(section)
            val oldX = sectionX(oldTag)
            compose.runOnUiThread { section = target }
            advance(64)
            val newTag = historyListTag(target)
            val enteringX = sectionX(newTag)
            val exitingX = sectionX(oldTag)
            capture("history-${if (largeRtl) "rtl" else "ltr"}-$index-moving")
            advance(320)
            val settledX = sectionX(newTag)
            val entersFromRight = (index == 0) != largeRtl
            assertTrue("Incoming $newTag: $enteringX -> $settledX", (enteringX > settledX) == entersFromRight)
            assertTrue("Outgoing $oldTag: $oldX -> $exitingX", (exitingX < oldX) == entersFromRight)
            assertTrue("Incoming section must translate", enteringX != settledX)
            assertTrue("Outgoing section must translate", exitingX != oldX)
            compose.onNodeWithTag(oldTag).assertDoesNotExist()
            compose.onNodeWithTag(newTag).assertIsDisplayed()
        }
        capture("history-${if (largeRtl) "rtl" else "ltr"}-settled")
    }

    private fun assertIndicatorExit(motion: RipDpiMotion) {
        var state by mutableStateOf(transitionActuator(HomeConnectionActuatorStatus.Engaging))
        compose.setContent {
            TransitionTheme(motion = motion, largeRtl = true) {
                RipDpiConnectionActuator(state, {}, {}, testTag = RipDpiTestTags.ConnectionActuatorButton)
            }
        }
        compose.mainClock.autoAdvance = false
        progress().assertExists()
        compose.runOnUiThread { state = transitionActuator(HomeConnectionActuatorStatus.Locked) }
        advance(32)
        assertCurrentActuator(HomeConnectionActuatorStatus.Locked)
        if (motion.animationsEnabled) {
            progress().assertExists()
            capture("actuator-reduced-fading")
            advance(motion.duration(motion.quickDurationMillis).toLong() + 32)
        }
        progress().assertDoesNotExist()
        capture(if (motion.animationsEnabled) "actuator-reduced-settled" else "actuator-disabled-settled")
    }

    private fun assertReconnectReview(largeRtl: Boolean) {
        val state = transitionReconnectState()
        val requests = mutableListOf<RunningReconnectRequest.ConfirmedRuntime>()
        compose.setContent {
            TransitionTheme(largeRtl = largeRtl) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HomeAppliedConfigurationPanel(state, { requests.add(it) }, {})
                }
            }
        }
        compose.onNodeWithTag(RipDpiTestTags.HomeRuntimeReconnectDialog).assertDoesNotExist()
        compose.onNodeWithText("Saved DNS: ODoH").assertDoesNotExist()
        compose.onNodeWithTag("home_runtime_reconnect").performScrollTo().performClick()
        compose
            .onNodeWithTag(RipDpiTestTags.HomeRuntimeReconnectCurrent)
            .assert(hasAnyDescendant(hasText("Current: RIPDPI · DoT")))
            .performScrollTo()
            .assertIsDisplayed()
        capture("reconnect-current-$largeRtl")
        compose.onNodeWithText("Saved DNS: ODoH").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved TCP: custom").performScrollTo().assertIsDisplayed()
        val strings = InstrumentationRegistry.getInstrumentation().targetContext
        compose
            .onNodeWithText(strings.getString(R.string.runtime_config_preview_route_unresolved))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(strings.getString(R.string.runtime_config_preview_note))
            .performScrollTo()
            .assertIsDisplayed()
        val cancel = compose.onNodeWithText(strings.getString(R.string.config_cancel))
        cancel.performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("home_runtime_confirm").performScrollTo().assertIsDisplayed()
        val cancelledConfirm =
            compose
                .onNodeWithTag("home_runtime_confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        capture("reconnect-actions-$largeRtl")
        cancel.performScrollTo().assertIsDisplayed()
        val cancelAction = cancel.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            cancelAction()
            cancelledConfirm()
        }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag(RipDpiTestTags.HomeRuntimeReconnectDialog).assertDoesNotExist()
        compose.onNodeWithTag("home_runtime_reconnect").performScrollTo().performClick()
        val consumedConfirm =
            compose
                .onNodeWithTag("home_runtime_confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.onNodeWithTag("home_runtime_confirm").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread {
            consumedConfirm()
            consumedConfirm()
        }
        compose.runOnIdle { assertEquals(listOf(state.confirmation), requests) }
        compose.onNodeWithTag(RipDpiTestTags.HomeRuntimeReconnectDialog).assertDoesNotExist()
    }

    private fun assertCurrentActuator(status: HomeConnectionActuatorStatus) {
        compose.onNodeWithText("State ${status.name}").assertIsDisplayed()
        compose.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).assertTextEquals("Action ${status.name}")
    }

    private fun progress() =
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true,
        )

    private fun sectionX(tag: String): Float =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .positionInRoot.x

    private fun advance(millis: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-transition-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }
}

private fun historyListTag(section: HistorySection): String =
    if (section == HistorySection.Connections) {
        RipDpiTestTags.HistoryConnectionsList
    } else {
        RipDpiTestTags.HistoryDiagnosticsList
    }

private fun transitionActuator(status: HomeConnectionActuatorStatus) =
    HomeConnectionActuatorUiState(
        status = status,
        statusDescription = "State ${status.name}",
        actionLabel = "Action ${status.name}",
    )

private fun transitionReconnectState() =
    HomeAppliedConfigurationUiState(
        visible = true,
        status = "Saved edits pending",
        confirmedSummary = "Current: RIPDPI · DoT",
        pending = true,
        confirmation = RunningReconnectRequest.ConfirmedRuntime(Mode.VPN, "controlled-runtime", 1),
        reconnectComparison =
            HomeReconnectComparisonUiState("Current: RIPDPI · DoT", "Saved DNS: ODoH", "Saved TCP: custom"),
    )

@Composable
private fun TransitionTheme(
    motion: RipDpiMotion = RipDpiMotion(),
    largeRtl: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = if (largeRtl) 2f else 1f),
        LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        RipDpiTheme(themePreference = if (largeRtl) "dark" else "light") {
            CompositionLocalProvider(LocalRipDpiMotion provides motion) {
                Column(Modifier.fillMaxSize().background(RipDpiThemeTokens.colors.background).padding(16.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun TransitionHistory(
    state: HistoryUiState,
    onSelect: (HistorySection) -> Unit,
) {
    HistoryScreen(
        uiState = state,
        onBack = {},
        onRefresh = {},
        onSelectSection = onSelect,
        onConnectionModeFilter = {},
        onConnectionStatusFilter = {},
        onConnectionSearch = {},
        onClearConnectionFilters = {},
        onSelectConnection = {},
        onDismissConnectionDetail = {},
        onDiagnosticsPathFilter = {},
        onDiagnosticsStatusFilter = {},
        onDiagnosticsSearch = {},
        onClearDiagnosticsFilters = {},
        onSelectDiagnosticsSession = {},
        onDismissDiagnosticsDetail = {},
        onToggleEventFilter = { _, _ -> },
        onEventSearch = {},
        onClearEventFilters = {},
        onEventAutoScroll = {},
        onSelectEvent = {},
        onDismissEventDetail = {},
    )
}
