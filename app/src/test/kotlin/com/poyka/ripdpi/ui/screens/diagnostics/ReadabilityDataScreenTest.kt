package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
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

    private lateinit var textMeasurer: TextMeasurer

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
        ) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                CollapsibleSection(title = title, badgeCount = 1234) { androidx.compose.material3.Text("Expanded") }
            }
        }
        assertFullText(title.uppercase())
        assertWordsFit(title.uppercase())
        composeRule.onNodeWithText(title.uppercase()).performClick()
        composeRule.onNodeWithText("Expanded").assertIsDisplayed()
        composeRule.onNodeWithText("1234").assertIsDisplayed()
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

    private fun show(
        width: Int,
        fontScale: Float,
        direction: LayoutDirection = LayoutDirection.Ltr,
        density: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                RipDpiTheme {
                    textMeasurer = rememberTextMeasurer()
                    Box(Modifier.requiredSize(width.dp, 1400.dp)) { content() }
                }
            }
        }
    }

    private fun assertWordsFit(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        Regex("[\\p{L}\\p{M}]+").findAll(text).forEach { match ->
            val wordWidth = textMeasurer.measure(match.value, layout.layoutInput.style, maxLines = 1).size.width
            assertTrue(
                "Whole word ${match.value} must fit the text viewport: word=$wordWidth bounds=$bounds",
                bounds.width >= wordWidth,
            )
            assertEquals(
                "Word ${match.value} must not break between lines",
                layout.getLineForOffset(match.range.first),
                layout.getLineForOffset(match.range.last),
            )
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
