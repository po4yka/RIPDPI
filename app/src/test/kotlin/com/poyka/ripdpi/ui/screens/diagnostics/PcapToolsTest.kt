package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsApproachMode
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsShareUiModel
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class PcapToolsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tools let users request recording and open saved captures`() {
        var recordingRequested = false
        var capturesOpened = false
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
                    navActions = DiagnosticsToolsNavActions(onOpenPcapCaptureList = { capturesOpened = true }),
                    onTogglePcapRecording = { recordingRequested = true },
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Start recording"))
        composeRule.onNodeWithText("Start recording").performClick()
        val openCaptures = hasText("Packet captures") and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(openCaptures)
        composeRule.onNode(openCaptures).performClick()

        composeRule.runOnIdle {
            assertTrue(recordingRequested)
            assertTrue(capturesOpened)
        }
    }
}
