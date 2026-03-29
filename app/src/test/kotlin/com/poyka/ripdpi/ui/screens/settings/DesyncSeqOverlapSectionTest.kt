package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.SeqOverlapFakeModeRand
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
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
class DesyncSeqOverlapSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `unsupported state explains seqovl fallback`() {
        setCard(uiState = SettingsUiState(seqovlSupported = false))

        composeRule.onNodeWithText("Sequence overlap unavailable").assertExists()
        composeRule.onNodeWithText("Falls back to split on this device").assertExists()
    }

    @Test
    fun `configured state shows ready summary values`() {
        setCard(
            uiState =
                SettingsUiState(
                    seqovlSupported = false,
                    desync =
                        DesyncCoreUiState(
                            tcpChainSteps =
                                listOf(
                                    TcpChainStepModel(
                                        kind = TcpChainStepKind.SeqOverlap,
                                        marker = "midsld",
                                        overlapSize = 16,
                                        fakeMode = SeqOverlapFakeModeRand,
                                    ),
                                ),
                        ),
                ),
        )

        composeRule.onNodeWithText("Sequence overlap configured").assertExists()
        composeRule.onNodeWithText("midsld").assertExists()
        composeRule.onNodeWithText("rand").assertExists()
        composeRule.onNodeWithText("16").assertExists()
    }

    @Test
    fun `running configured state asks for restart`() {
        setCard(
            uiState =
                SettingsUiState(
                    seqovlSupported = false,
                    serviceStatus = AppStatus.Running,
                    desync =
                        DesyncCoreUiState(
                            tcpChainSteps =
                                listOf(
                                    TcpChainStepModel(
                                        kind = TcpChainStepKind.SeqOverlap,
                                        marker = "host+1",
                                        overlapSize = 12,
                                        fakeMode = "profile",
                                    ),
                                ),
                        ),
                ),
        )

        composeRule.onNodeWithText("Restart to reapply seqovl").assertExists()
    }

    @Test
    fun `command line mode takes priority over configured seqovl status`() {
        setCard(
            uiState =
                SettingsUiState(
                    enableCmdSettings = true,
                    seqovlSupported = false,
                    serviceStatus = AppStatus.Running,
                    desync =
                        DesyncCoreUiState(
                            tcpChainSteps =
                                listOf(
                                    TcpChainStepModel(
                                        kind = TcpChainStepKind.SeqOverlap,
                                        marker = "host+1",
                                        overlapSize = 12,
                                        fakeMode = "profile",
                                    ),
                                ),
                        ),
                ),
        )

        composeRule.onNodeWithText("Controlled by command line").assertExists()
    }

    private fun setCard(uiState: SettingsUiState) {
        composeRule.setContent {
            RipDpiTheme {
                SeqOverlapProfileCard(uiState = uiState)
            }
        }
    }
}
