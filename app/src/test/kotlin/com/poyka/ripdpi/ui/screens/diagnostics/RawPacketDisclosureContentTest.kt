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

        // Disclosure body must mention raw IP packet bytes, retention, and the
        // explicit-share-only export guarantee. The third assertion was originally
        // written against an earlier draft string ("not attached"); the canonical
        // R.string.diagnostics_pcap_disclosure_body now uses the clearer
        // "included only in archives you explicitly share or save" phrasing.
        composeRule
            .onNodeWithText("raw IP packet bytes", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("24 h or 3 most-recent files", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("PCAP files are included only", substring = true)
            .assertIsDisplayed()
    }
}
