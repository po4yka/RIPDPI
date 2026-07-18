package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.pcap.PcapCaptureMetadata
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
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

    @Test
    fun longCaptureHistoryScrollsToTheOldestRow() {
        val captures =
            (0..11)
                .map { index ->
                    PcapCaptureMetadata(
                        path = "/tmp/capture-$index.pcap",
                        byteSize = 1_024,
                        packetCount = index.toLong(),
                        startedAtMs = index.toLong(),
                        endedAtMs = index.toLong() + 1,
                        drops = 0,
                    )
                }.toPersistentList()

        composeRule.setContent {
            RipDpiTheme {
                PcapCaptureListScreen(captures = captures, onCaptureSelected = {}, onBack = {})
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("capture-11.pcap"))
        composeRule.onNodeWithText("capture-11.pcap").assertIsDisplayed()
    }
}
