package com.poyka.ripdpi.ui.screens.logs

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.activities.LogsUiState
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w360dp-h640dp-mdpi")
class LogsViewportTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `first event is visible on narrow screen at maximum font scale`() {
        val entry =
            LogEntry(
                id = "first-event",
                createdAtMs = 1L,
                timestamp = "12:34:56",
                subsystem = LogSubsystem.Service,
                severity = LogSeverity.Info,
                message = "Service started",
                source = "service",
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    LogsScreen(
                        uiState = LogsUiState(logs = persistentListOf(entry), filteredLogs = persistentListOf(entry)),
                        onToggleSubsystemFilter = {},
                        onToggleSeverityFilter = {},
                        onAutoScrollChanged = {},
                        onActiveSessionOnlyChanged = {},
                        onClearLogs = {},
                        onSaveLogs = {},
                        onShareSupportBundle = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry(entry.id)).assertIsDisplayed()
    }

    @Test
    fun `empty guidance stays complete at maximum font scale and scrolls in a short viewport`() {
        val height = mutableStateOf(640.dp)
        val body = ApplicationProvider.getApplicationContext<Context>().getString(R.string.logs_empty_body)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(Modifier.width(360.dp).height(height.value)) {
                        TestLogsScreen(LogsUiState())
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(body, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("Empty-state guidance must not overflow", layout.hasVisualOverflow)
        assertEquals(body.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))

        composeRule.runOnIdle { height.value = 300.dp }
        val scroll = composeRule.onNode(hasScrollAction())
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Guidance must scroll when the viewport is too short", range.maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        composeRule.runOnIdle {
            assertEquals("The end of the guidance must be reachable", range.maxValue(), range.value(), 0.5f)
        }
    }

    @Test
    fun `stream grows with available screen height and owns the only scroll`() {
        val height = mutableStateOf(400.dp)
        composeRule.setContent {
            RipDpiTheme {
                Box(Modifier.width(360.dp).height(height.value)) {
                    TestLogsScreen(LogsUiState(logs = persistentListOf(testEntry(0))))
                }
            }
        }
        val shortHeight =
            composeRule
                .onNodeWithTag(RipDpiTestTags.LogsStream)
                .fetchSemanticsNode()
                .boundsInRoot.height
        composeRule.runOnIdle { height.value = 600.dp }
        val tallHeight =
            composeRule
                .onNodeWithTag(RipDpiTestTags.LogsStream)
                .fetchSemanticsNode()
                .boundsInRoot.height

        assertEquals(200f, tallHeight - shortHeight, 1f)
        assertEquals(1, composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `new entries preserve the viewport while reading history`() {
        val entries = (0..29).map(::testEntry)
        val state = mutableStateOf(LogsUiState(logs = persistentListOf(*entries.toTypedArray())))
        composeRule.setContent { RipDpiTheme { TestLogsScreen(state.value) } }
        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry("event-29")).assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val before = composeRule.onNodeWithTag(RipDpiTestTags.logsEntry("event-0")).fetchSemanticsNode().boundsInRoot

        composeRule.runOnIdle {
            state.value = LogsUiState(logs = persistentListOf(*(entries + (30..49).map(::testEntry)).toTypedArray()))
        }

        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry("event-0")).assertIsDisplayed()
        val after = composeRule.onNodeWithTag(RipDpiTestTags.logsEntry("event-0")).fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
    }

    @Test
    fun `append preserves viewport a few pixels above the live edge`() {
        val entries = (0..29).map(::testEntry)
        val state = mutableStateOf(LogsUiState(logs = persistentListOf(*entries.toTypedArray())))
        composeRule.setContent { RipDpiTheme { TestLogsScreen(state.value) } }
        val lastEntryTag = RipDpiTestTags.logsEntry("event-29")
        composeRule.onNodeWithTag(lastEntryTag).assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, -8f)
        }
        val before = composeRule.onNodeWithTag(lastEntryTag).fetchSemanticsNode().boundsInRoot

        composeRule.runOnIdle {
            state.value = LogsUiState(logs = persistentListOf(*(entries + testEntry(30)).toTypedArray()))
        }

        val after = composeRule.onNodeWithTag(lastEntryTag).fetchSemanticsNode().boundsInRoot
        assertEquals("An appended event must not move a reader above the live edge", before.top, after.top, 0.5f)
    }

    @Test
    fun `large batch follows newest event when already at live edge`() {
        val state = mutableStateOf(LogsUiState(logs = persistentListOf(testEntry(0))))
        composeRule.setContent { RipDpiTheme { TestLogsScreen(state.value) } }
        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry("event-0")).assertIsDisplayed()

        composeRule.runOnIdle {
            state.value = LogsUiState(logs = persistentListOf(*(0..29).map(::testEntry).toTypedArray()))
        }

        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry("event-29")).assertIsDisplayed()
    }

    @Test
    fun `optional filters keep toggles and severity actions reachable`() {
        var autoScroll: Boolean? = null
        var activeSessionOnly: Boolean? = null
        var selectedSeverity: LogSeverity? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    TestLogsScreen(
                        uiState = LogsUiState(),
                        onAutoScrollChanged = { autoScroll = it },
                        onActiveSessionOnlyChanged = { activeSessionOnly = it },
                        onToggleSeverityFilter = { selectedSeverity = it },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.LogsAutoScroll).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsFilters).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsAutoScroll).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsActiveSessionOnly).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.logsSeverityFilter(LogSeverity.Error)).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(false, autoScroll)
            assertEquals(true, activeSessionOnly)
            assertEquals(LogSeverity.Error, selectedSeverity)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.LogsSheetDone).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsFiltersSheet).assertDoesNotExist()
    }

    @Test
    fun `actions expose save support and clear without displacing the stream`() {
        var saveCount = 0
        var supportCount = 0
        var clearCount = 0
        composeRule.setContent {
            RipDpiTheme {
                TestLogsScreen(
                    uiState = LogsUiState(logs = persistentListOf(testEntry(0))),
                    onSaveLogs = { saveCount++ },
                    onShareSupportBundle = { supportCount++ },
                    onClearLogs = { clearCount++ },
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.LogsSave).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsActions).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsSave).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsActions).performClick()
        composeRule.onNodeWithText("Share support bundle").performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsActions).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.LogsClear).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, saveCount)
            assertEquals(1, supportCount)
            assertEquals(1, clearCount)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry("event-0")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-w1300dp-h900dp-mdpi")
    fun `expanded window shows the selected entry beside the stream`() {
        val entry =
            LogEntry(
                id = "pane-entry",
                createdAtMs = 1L,
                timestamp = "12:34:56",
                subsystem = LogSubsystem.Service,
                severity = LogSeverity.Info,
                message = "Service started",
                source = "service",
            )
        composeRule.setContent {
            RipDpiTheme {
                TestLogsScreen(LogsUiState(logs = persistentListOf(entry), filteredLogs = persistentListOf(entry)))
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.LogsDetailPlaceholder).assertExists()

        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry(entry.id)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RipDpiTestTags.LogsDetailPane).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.logsEntry(entry.id)).assertIsSelected()
    }

    @Composable
    private fun TestLogsScreen(
        uiState: LogsUiState,
        onAutoScrollChanged: (Boolean) -> Unit = {},
        onActiveSessionOnlyChanged: (Boolean) -> Unit = {},
        onToggleSeverityFilter: (LogSeverity) -> Unit = {},
        onSaveLogs: () -> Unit = {},
        onShareSupportBundle: () -> Unit = {},
        onClearLogs: () -> Unit = {},
    ) {
        LogsScreen(
            uiState = uiState,
            onToggleSubsystemFilter = {},
            onToggleSeverityFilter = onToggleSeverityFilter,
            onAutoScrollChanged = onAutoScrollChanged,
            onActiveSessionOnlyChanged = onActiveSessionOnlyChanged,
            onClearLogs = onClearLogs,
            onSaveLogs = onSaveLogs,
            onShareSupportBundle = onShareSupportBundle,
        )
    }

    private fun testEntry(index: Int): LogEntry =
        LogEntry(
            id = "event-$index",
            createdAtMs = index.toLong(),
            timestamp = "12:34:56",
            subsystem = LogSubsystem.Service,
            severity = LogSeverity.Info,
            message = "Event $index",
            source = "service",
        )
}
