package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
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
class RawPacketDisclosureVisibleWhenPcapEnabledTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun toolsSection(
        rootModeEnabled: Boolean,
        pcapRecording: Boolean,
    ) {
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
                    rootModeEnabled = rootModeEnabled,
                    pcapRecording = pcapRecording,
                    onTogglePcapRecording = {},
                )
            }
        }
    }

    @Test
    fun disclosureCardHiddenWhenPcapNotRecording() {
        toolsSection(rootModeEnabled = true, pcapRecording = false)

        composeRule.onNodeWithText("Raw-packet capture disclosure").assertIsNotDisplayed()
    }

    @Test
    fun disclosureCardVisibleWhenPcapRecording() {
        toolsSection(rootModeEnabled = true, pcapRecording = true)

        composeRule.onNodeWithText("Raw-packet capture disclosure").assertIsDisplayed()
    }
}
