package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckScreen
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckUiState
import com.poyka.ripdpi.ui.screens.history.FilterCard
import com.poyka.ripdpi.ui.screens.history.HistoryFilterChipsConfig
import com.poyka.ripdpi.ui.screens.history.HistorySectionChips
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "en-w420dp-h1600dp")
class ReadabilityDataScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recoveryActionsFitAtNormalFont() = checkRecoveryActions(420, 1f)

    @Test
    @Config(sdk = [35], qualifiers = "ru-w420dp-h1600dp")
    fun recoveryActionsWrapAtMaximumFont() = checkRecoveryActions(320, 2f)

    @Test
    @Config(sdk = [35], qualifiers = "ar-w420dp-h1600dp")
    fun recoveryActionsWrapInRtl() = checkRecoveryActions(320, 2f, LayoutDirection.Rtl)

    @Test
    @Config(sdk = [35], qualifiers = "ru-w420dp-h1600dp")
    fun importSourcesKeepFullTextAndCallbacksAtMaximumFont() {
        val selections = mutableListOf<Int>()
        val state = sampleStrategyImportState().copy(recentImports = emptyList())
        show(320, 2f) {
            StrategyImportScreen(
                state.copy(
                    sources =
                        state.sources.mapIndexed { index, source ->
                            source.copy(onSelect = {
                                selections +=
                                    index
                            })
                        },
                ),
                onBack = {},
            )
        }
        state.sources.forEach { source ->
            val label = string(source.titleRes)
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            assertFullText(label)
            assertFullText(string(source.subtitleRes))
            composeRule.onNodeWithText(label).performClick()
        }
        composeRule.runOnIdle { assertEquals(listOf(0, 1, 2, 3), selections) }
    }

    @Test
    fun throughputKeepsSelectedLabelInOnePillAndCompleteStats() {
        show(320, 2f) {
            ThroughputGraphScreen(sampleThroughputGraphState("Throughput", "1.42 MiB/s", "312 KiB/s", "128 MiB"))
        }
        val pill =
            composeRule
                .onNodeWithText("5m")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                .fetchSemanticsNode()
        val text = composeRule.onNodeWithText("5m", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            "Range label must be inside its selected surface",
            pill.boundsInRoot.contains(text.boundsInRoot.center),
        )
        assertTrue("Range surface must contain the full text height", pill.size.height >= text.size.height)
        listOf("1m", "5m", "30m", "1h", "1.42 MiB/s", "312 KiB/s", "128 MiB").forEach(::assertFullText)
    }

    @Test
    fun overviewKeepsLongMetricLabelsAndValuesComplete() {
        val label = "Successful connection attempts for this network"
        val value = "1234567890 MiB"
        show(320, 2f) { OverviewStatGrid(persistentListOf(DiagnosticsMetricUiModel(label, value))) }
        assertFullText(label.uppercase())
        assertFullText(value)
    }

    @Test
    @Config(sdk = [35], qualifiers = "ru-w420dp-h1600dp")
    fun longCollapsibleHeadingKeepsChevronAndToggle() {
        val title = "Результаты проверки доступности зашифрованных соединений"
        show(
            320,
            2f,
        ) { CollapsibleSection(title = title, badgeCount = 1234) { androidx.compose.material3.Text("Expanded") } }
        assertFullText(title.uppercase())
        composeRule.onNodeWithText(title.uppercase()).performClick()
        composeRule.onNodeWithText("Expanded").assertIsDisplayed()
        composeRule.onNodeWithText("1234").assertIsDisplayed()
    }

    @Test
    fun handshakeKeepsStageAndDurationAboveFullTrack() {
        val state =
            sampleHandshakeTimelineState("Handshake", "Current connection", "Total", "Slowest stage", "Within budget")
                .copy(
                    stages =
                        persistentListOf(
                            HandshakeStageRow(
                                "TLS ClientHello",
                                "Encrypted handshake negotiation",
                                0.1f,
                                0.3f,
                                "192 ms",
                                HandshakeStageTone.Info,
                            ),
                        ),
                )
        show(320, 2f) { HandshakeTimelineScreen(state) }
        listOf(
            "TLS ClientHello",
            "Encrypted handshake negotiation",
            "192 ms",
            "Current connection",
            "Slowest stage",
        ).forEach(::assertFullText)
    }

    @Test
    @Config(sdk = [35], qualifiers = "ru-w420dp-h1600dp")
    fun historyTabsScrollToEverySectionAtMaximumFont() {
        var selected by mutableStateOf(HistorySection.Connections)
        val selections = mutableListOf<HistorySection>()
        show(320, 2f) {
            HistorySectionChips(selected) {
                selected = it
                selections += it
            }
        }
        HistorySection.entries.forEach { section ->
            val tab = composeRule.onNodeWithTag(RipDpiTestTags.historySection(section))
            tab.performScrollTo().assertIsDisplayed().performClick()
            tab.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            assertTrue("Tab must retain a 48dp target", tab.fetchSemanticsNode().size.height >= 48)
        }
        composeRule.runOnIdle { assertEquals(HistorySection.entries, selections) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ru-w420dp-h1600dp")
    fun stateDiagramKeepsLocalizedNodeTextAndScrollsToBothEdges() {
        val state =
            sampleStateMachineState(
                "Туннель",
                "24 перехода",
                "простой",
                "разрешение ОС",
                "рукопожатие",
                "12 м 14 с",
                "отсрочка",
                "посл. 6 ч: 0",
                "2 за 24 ч",
            )
        show(320, 2f) { StateMachineScreen(state) }
        val nodeLabels =
            listOf(
                R.string.vpn_state_machine_node_disconnected,
                R.string.vpn_state_machine_node_permissioning,
                R.string.vpn_state_machine_node_connecting,
                R.string.vpn_state_machine_node_tunneling,
                R.string.vpn_state_machine_node_reconnecting,
                R.string.vpn_state_machine_node_failed,
                R.string.vpn_state_machine_node_degraded,
            )
        nodeLabels.forEach { resource -> assertFullText(string(resource)) }
        composeRule
            .onNode(
                hasText(string(R.string.vpn_state_machine_node_tunneling)) and hasAnyAncestor(hasScrollAction()),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                string(R.string.vpn_state_machine_node_disconnected),
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-w420dp-h1600dp")
    fun clearFiltersHasFullLabelAndMinimumTargetInRtl() {
        var clears = 0
        val config = HistoryFilterChipsConfig(emptyList(), null, {}, { it })
        show(320, 2f, LayoutDirection.Rtl) {
            FilterCard(
                title = "مرشحات السجل والتشخيص",
                searchValue = "TLS",
                searchPlaceholder = "بحث",
                onSearch = {},
                searchTestTag = "search",
                primaryFilter = config,
                secondaryFilter = config,
                onClearFilters = { clears++ },
            )
        }
        val action = composeRule.onNodeWithTag(RipDpiTestTags.HistoryFilterClearAll).assertIsDisplayed()
        assertTrue("Clear filters must have a 48dp target", action.fetchSemanticsNode().size.height >= 48)
        assertFullText(string(R.string.history_filter_clear_all))
        action.performClick()
        composeRule.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ru-w420dp-h1600dp")
    fun subnetActionsKeepLabelsAndCallbacksAtMaximumFont() {
        var caches = 0
        var checks = 0
        show(320, 2f) {
            Ipv4WhitelistSubnetDiscoveryCard(DiagnosticsIpv4WhitelistToolUiModel(), { caches++ }, { checks++ }, {})
        }
        listOf(
            R.string.diagnostics_ipv4_whitelist_cache,
            R.string.diagnostics_ipv4_whitelist_check_cached,
        ).forEach { resource ->
            val label = string(resource)
            assertFullText(label)
            composeRule.onNodeWithText(label).assertIsDisplayed().performClick()
        }
        composeRule.runOnIdle {
            assertEquals(1, caches)
            assertEquals(1, checks)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ru-w420dp-h1600dp")
    fun blockcheckActionsKeepLabelsAndEnabledCallbacksAtMaximumFont() {
        var applies = 0
        var exports = 0
        val state =
            BlockcheckUiState(
                results = persistentListOf(StrategyProbeResult("split", "Split", "example.org", true, 25L)),
                rankedStrategies = persistentListOf(RankedStrategyProbeResult("split", "Split", 1, 1, 1.0, 25L, 0)),
            )
        show(320, 2f) { BlockcheckScreen(state, {}, {}, {}, {}, { applies++ }, {}, { exports++ }) }
        listOf(R.string.blockcheck_apply_best, R.string.blockcheck_export).forEach { resource ->
            val label = string(resource)
            composeRule
                .onNodeWithText(label)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertFullText(label)
        }
        composeRule.runOnIdle {
            assertEquals(1, applies)
            assertEquals(1, exports)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-w420dp-h1600dp")
    fun coordinateDiagramsKeepTimeAndNodeDirectionInRtl() {
        val state =
            sampleStateMachineState(
                "current",
                "24",
                "idle",
                "prompt",
                "handshake",
                "12m",
                "retry",
                "failed",
                "degraded",
            )
        show(320, 2f, LayoutDirection.Rtl) { StateMachineScreen(state) }
        val disconnected = string(R.string.vpn_state_machine_node_disconnected)
        val tunneling = string(R.string.vpn_state_machine_node_tunneling)
        assertFullText(disconnected)
        assertFullText(tunneling)
        val first =
            composeRule
                .onNodeWithText(disconnected)
                .fetchSemanticsNode()
                .positionInRoot.x
        val last =
            composeRule
                .onNodeWithText(tunneling)
                .fetchSemanticsNode()
                .positionInRoot.x
        assertTrue("Node positions must use the same LTR coordinates as Canvas edges", first < last)
        composeRule.onNodeWithText(tunneling).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(disconnected).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun handshakeAxisAlignsAllTicksWithTrackFractions() = checkHandshakeAxis(420, 1f, LayoutDirection.Ltr)

    @Test
    fun handshakeAxisKeepsReadableTicksAtMaximumFont() = checkHandshakeAxis(320, 2f, LayoutDirection.Ltr)

    @Test
    @Config(sdk = [35], qualifiers = "ar-w420dp-h1600dp")
    fun handshakeAxisKeepsFractionsAndReadableTicksInRtl() = checkHandshakeAxis(320, 2f, LayoutDirection.Rtl)

    private fun checkHandshakeAxis(
        width: Int,
        fontScale: Float,
        direction: LayoutDirection,
    ) {
        val state =
            sampleHandshakeTimelineState("Handshake", "Connection", "Total", "Slowest", "Budget")
                .copy(
                    stages =
                        persistentListOf(
                            HandshakeStageRow("TLS", "Handshake", 0.1f, 0.3f, "192 ms", HandshakeStageTone.Info),
                        ),
                )
        show(width, fontScale, direction) { HandshakeTimelineScreen(state) }
        val first =
            composeRule
                .onNodeWithText("0 ms")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val last =
            composeRule
                .onNodeWithText("1500")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val labels = if (fontScale == 1f) state.axisTickLabels else listOf("0 ms", "900", "1500")
        var previousRight = first.left
        labels.forEach { text ->
            val bounds =
                composeRule
                    .onNodeWithText(text)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("Axis text must not overlap: $text", bounds.left >= previousRight - 1f)
            assertTrue(
                "Axis text must stay inside its viewport: $text",
                bounds.left >= first.left && bounds.right <= last.right,
            )
            val index = state.axisTickLabels.indexOf(text)
            if (index > 0 && index < state.axisTickLabels.lastIndex) {
                val expectedPoint = first.left + (last.right - first.left) * index / state.axisTickLabels.lastIndex
                assertEquals("Tick must match its track fraction: $text", expectedPoint, bounds.center.x, 1f)
            }
            assertFullText(text)
            previousRight = bounds.right
        }
    }

    private fun checkRecoveryActions(
        width: Int,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        var reconnects = 0
        var incidents = 0
        show(width, fontScale, direction) {
            OomRecoveryScreen(OomRecoveryState("12:42 UTC", "4 m 18 s"), { reconnects++ }, { incidents++ }, {}, {})
        }
        listOf(R.string.oom_recovery_action_reconnect, R.string.oom_recovery_action_view_incident).forEach { resource ->
            val label = string(resource)
            composeRule
                .onNodeWithText(label)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertFullText(label)
        }
        composeRule.runOnIdle {
            assertEquals(1, reconnects)
            assertEquals(1, incidents)
        }
    }

    private fun show(
        width: Int,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(width.dp, 1400.dp)) { content() }
                }
            }
        }
    }

    private fun assertFullText(text: String) {
        val nodes = composeRule.onAllNodesWithText(text, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("Missing text for $text", count > 0)
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            val node = nodes[index]
            val bounds = node.fetchSemanticsNode().boundsInRoot
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing layout for $text", layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse(
                    "Clipped text: $text size=${layout.size} bounds=$bounds " +
                        "constraints=${layout.layoutInput.constraints} " +
                        "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                        "intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}",
                    layout.hasVisualOverflow,
                )
                (0 until layout.lineCount).forEach { line ->
                    assertFalse("Ellipsis for $text", layout.isLineEllipsized(line))
                }
            }
        }
    }

    private fun string(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)
}
