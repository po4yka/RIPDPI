package com.poyka.ripdpi.ui.screens.logs

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
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
        var copiedEntry: LogEntry? = null
        composeRule.setContent {
            RipDpiTheme {
                LogsStreamCard(
                    entries = persistentListOf(entry),
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

        composeRule.runOnIdle { assertEquals(entry, copiedEntry) }
    }
}
