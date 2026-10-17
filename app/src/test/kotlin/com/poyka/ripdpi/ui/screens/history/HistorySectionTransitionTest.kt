package com.poyka.ripdpi.ui.screens.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.HistoryConnectionsUiModel
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.HistoryUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistorySectionTransitionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `RTL forward history section enters from the physical left`() {
        val section = mutableStateOf(HistorySection.Connections)
        showHistory(section, LayoutDirection.Rtl)
        selectSection(section, HistorySection.Diagnostics)
        val enteringX = sectionX(RipDpiTestTags.HistoryDiagnosticsList)
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        val settledX = sectionX(RipDpiTestTags.HistoryDiagnosticsList)
        assertTrue("Incoming RTL section must enter left of $settledX, was $enteringX", enteringX < settledX)
    }

    @Test
    fun `LTR forward history sections move toward the physical left`() {
        assertTransitionDirection(LayoutDirection.Ltr, forward = true, enterFromRight = true)
    }

    @Test
    fun `RTL forward history sections move toward the physical right`() {
        assertTransitionDirection(LayoutDirection.Rtl, forward = true, enterFromRight = false)
    }

    @Test
    fun `LTR reverse history sections move toward the physical right`() {
        assertTransitionDirection(LayoutDirection.Ltr, forward = false, enterFromRight = false)
    }

    @Test
    fun `RTL reverse history sections move toward the physical left`() {
        assertTransitionDirection(LayoutDirection.Rtl, forward = false, enterFromRight = true)
    }

    @Test
    fun `disabled motion replaces the section without outgoing content or translation`() {
        val section = mutableStateOf(HistorySection.Connections)
        showHistory(section, LayoutDirection.Rtl, motion = RipDpiMotion(animationsEnabled = false))
        selectSection(section, HistorySection.Diagnostics)
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryConnectionsList).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryDiagnosticsList).assertIsDisplayed()
        assertEquals(0f, sectionX(RipDpiTestTags.HistoryDiagnosticsList), 0.01f)
    }

    @Test
    fun `reduced motion keeps the mirrored direction and shorter duration`() {
        val section = mutableStateOf(HistorySection.Connections)
        showHistory(section, LayoutDirection.Rtl, motion = RipDpiMotion(reducedMotion = true))
        selectSection(section, HistorySection.Diagnostics)
        assertTrue(sectionX(RipDpiTestTags.HistoryDiagnosticsList) < 0f)
        composeRule.mainClock.advanceTimeBy(160)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RipDpiTestTags.HistoryConnectionsList).assertDoesNotExist()
        assertEquals(0f, sectionX(RipDpiTestTags.HistoryDiagnosticsList), 0.01f)
    }

    @Test
    fun `RTL section round trip retains expanded filters`() {
        val section = mutableStateOf(HistorySection.Connections)
        showHistory(
            section,
            LayoutDirection.Rtl,
            state =
                HistoryUiState(
                    hasConnectionHistory = true,
                    connections = HistoryConnectionsUiModel(modes = persistentListOf("VPN")),
                ),
        )
        composeRule
            .onNodeWithTag(RipDpiTestTags.historyFilterDisclosure(RipDpiTestTags.HistoryConnectionsSearch))
            .performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RipDpiTestTags.historyConnectionsModeFilter("VPN")).assertIsDisplayed()
        selectSection(section, HistorySection.Diagnostics)
        settleTransition()
        selectSection(section, HistorySection.Connections)
        settleTransition()
        composeRule.onNodeWithTag(RipDpiTestTags.historyConnectionsModeFilter("VPN")).assertIsDisplayed()
    }

    private fun assertTransitionDirection(
        direction: LayoutDirection,
        forward: Boolean,
        enterFromRight: Boolean,
    ) {
        val initial = if (forward) HistorySection.Connections else HistorySection.Diagnostics
        val target = if (forward) HistorySection.Diagnostics else HistorySection.Connections
        val initialTag = if (forward) RipDpiTestTags.HistoryConnectionsList else RipDpiTestTags.HistoryDiagnosticsList
        val targetTag = if (forward) RipDpiTestTags.HistoryDiagnosticsList else RipDpiTestTags.HistoryConnectionsList
        val section = mutableStateOf(initial)
        showHistory(section, direction)
        val initialX = sectionX(initialTag)
        selectSection(section, target)
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.waitForIdle()
        val enteringX = sectionX(targetTag)
        val exitingX = sectionX(initialTag)
        settleTransition()
        val settledX = sectionX(targetTag)
        assertTrue(
            "Incoming $direction section x=$enteringX, settled=$settledX",
            (enteringX > settledX) == enterFromRight,
        )
        assertTrue(
            "Outgoing $direction section x=$exitingX, initial=$initialX",
            (exitingX < initialX) == enterFromRight,
        )
        assertTrue("Incoming section must move", enteringX != settledX)
        assertTrue("Outgoing section must move", exitingX != initialX)
        composeRule.onNodeWithTag(initialTag).assertDoesNotExist()
    }

    private fun showHistory(
        section: MutableState<HistorySection>,
        direction: LayoutDirection,
        motion: RipDpiMotion = RipDpiMotion(),
        state: HistoryUiState = HistoryUiState(),
    ) {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    LocalRipDpiMotion provides motion,
                ) {
                    HistoryScreen(
                        uiState = state.copy(selectedSection = section.value),
                        onBack = {},
                        onRefresh = {},
                        onSelectSection = { section.value = it },
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
            }
        }
        composeRule.mainClock.autoAdvance = false
    }

    private fun selectSection(
        section: MutableState<HistorySection>,
        target: HistorySection,
    ) {
        composeRule.runOnUiThread { section.value = target }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
    }

    private fun sectionX(tag: String): Float =
        composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .positionInRoot.x

    private fun settleTransition() {
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
    }
}
