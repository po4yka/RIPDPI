package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class PcapCaptureListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsContextAndNavigatesBack() {
        var backRequested = false

        composeRule.setContent {
            RipDpiTheme {
                PcapCaptureListScreen(
                    captures = persistentListOf(),
                    onCaptureSelected = {},
                    onBack = { backRequested = true },
                )
            }
        }

        composeRule.onNodeWithText("Packet captures").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").assertIsDisplayed().performClick()

        assertTrue(backRequested)
    }
}
