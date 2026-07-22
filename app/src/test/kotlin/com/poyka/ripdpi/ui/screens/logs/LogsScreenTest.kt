package com.poyka.ripdpi.ui.screens.logs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class LogsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `log entry exposes one dedicated copy action`() {
        val entry =
            LogEntry(
                id = "entry-1",
                createdAtMs = 1L,
                timestamp = "12:34:56",
                subsystem = LogSubsystem.Proxy,
                severity = LogSeverity.Info,
                message = "Connected to relay",
                source = "runtime",
            )
        val secondEntry =
            LogEntry(
                id = "entry-2",
                createdAtMs = 2L,
                timestamp = "12:35:57",
                subsystem = LogSubsystem.Diagnostics,
                severity = LogSeverity.Warn,
                message = "Probe timed out",
                source = "diagnostics",
            )
        var copiedEntry: LogEntry? = null
        composeRule.setContent {
            RipDpiTheme {
                LogsStreamCard(
                    entries = persistentListOf(entry, secondEntry),
                    listState = rememberLazyListState(),
                    onCopyEntry = { copiedEntry = it },
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.logsEntry(entry.id))
            .assertHasNoClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.logsEntryCopy(entry.id))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithContentDescription("Copy PROXY log at 12:34:56").assertExists()
        composeRule.onNodeWithContentDescription("Copy DIAGNOSTICS log at 12:35:57").assertExists()

        composeRule.runOnIdle { assertEquals(entry, copiedEntry) }
    }

    @Test
    fun `long session metadata wraps as bounded single line chips`() {
        val runtimeChip = "runtime:$LongRuntimeId"
        val scanChip = "scan:$LongScanId"
        val entry =
            LogEntry(
                id = "long-metadata",
                createdAtMs = 1L,
                timestamp = "12:34:56",
                subsystem = LogSubsystem.Diagnostics,
                severity = LogSeverity.Info,
                message = "Diagnostic scan started",
                source = "diagnostics",
                runtimeId = LongRuntimeId,
                diagnosticsSessionId = LongScanId,
                isActiveSession = true,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                RipDpiTheme {
                    Box(Modifier.width(320.dp).height(300.dp)) {
                        LogsStreamCard(
                            entries = persistentListOf(entry),
                            listState = rememberLazyListState(),
                            onCopyEntry = {},
                        )
                    }
                }
            }
        }

        val entryBounds =
            composeRule
                .onNodeWithTag(RipDpiTestTags.logsEntry(entry.id))
                .fetchSemanticsNode()
                .boundsInRoot
        val copyBounds =
            composeRule
                .onNodeWithTag(RipDpiTestTags.logsEntryCopy(entry.id))
                .fetchSemanticsNode()
                .boundsInRoot
        val runtimeBounds =
            composeRule
                .onNodeWithText(runtimeChip, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val scanBounds =
            composeRule
                .onNodeWithText(scanChip, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(runtimeBounds.height <= MaximumChipHeightPx)
        assertTrue(scanBounds.height <= MaximumChipHeightPx)
        assertTrue(runtimeBounds.left >= entryBounds.left)
        assertTrue(runtimeBounds.right <= entryBounds.right)
        assertTrue(scanBounds.left >= entryBounds.left)
        assertTrue(scanBounds.right <= entryBounds.right)
        assertTrue(entryBounds.height <= MaximumEntryHeightPx)
        assertTrue(copyBounds.top <= entryBounds.top + MaximumCopyTopOffsetPx)
    }

    private companion object {
        const val LongRuntimeId = "5d8e4a11-5067-44b2-9a17-e84826709d28"
        const val LongScanId = "f87bba7f-d81f-4376-af1e-7282c4c55f62"
        const val MaximumChipHeightPx = 32f
        const val MaximumEntryHeightPx = 160f
        const val MaximumCopyTopOffsetPx = 32f
    }
}
