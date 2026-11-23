package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsApproachMode
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsShareUiModel
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RawPacketDisclosureContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disclosureBodyMentionsRawPacketCapture() {
        composeRule.setContent {
            RipDpiTheme {
                ToolsSection(
                    approaches =
                        DiagnosticsApproachesUiModel(
                            selectedMode = DiagnosticsApproachMode.Profiles,
                        ),
                    share = DiagnosticsShareUiModel(),
                    onSelectApproachMode = {},
                    onSelectApproach = {},
                    shareActions =
                        DiagnosticsShareActions(
                            onShareSummary = {},
                            onShareArchive = {},
                            onSaveArchive = {},
                            onSaveLogs = {},
                        ),
                    pcapRecording = true,
                    onTogglePcapRecording = {},
                )
            }
        }

        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("packet payloads", substring = true))

        // The disclosure must describe payloads, retention limits and separate archives.
        composeRule
            .onNodeWithText("packet payloads", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("up to three files", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("older than 24 hours", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("separate from redacted diagnostic archives", substring = true)
            .assertIsDisplayed()
    }
}
