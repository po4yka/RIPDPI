package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
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
                    rootModeEnabled = true,
                    pcapRecording = true,
                    onTogglePcapRecording = {},
                )
            }
        }

        // Disclosure body must mention raw IP packet bytes, retention, and no automatic export
        composeRule
            .onNodeWithText("raw IP packet bytes", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("24 h", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("not attached", substring = true)
            .assertIsDisplayed()
    }
}
