package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReplayHistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsScanCopyAndCta() {
        var scanRequested = false

        composeRule.setContent {
            RipDpiTheme {
                ReplayHistoryScreen(
                    replays = persistentListOf(),
                    onRunScan = { scanRequested = true },
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ReplayHistoryEmptyState).assertIsDisplayed()
        composeRule.onNodeWithText("No past replays yet").assertIsDisplayed()
        composeRule
            .onNodeWithText("Run a raw-path or in-path scan to persist reports and probe details here.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.ReplayHistoryRunScanAction).assertIsDisplayed().performClick()

        assertTrue(scanRequested)
    }
}
