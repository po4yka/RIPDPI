package com.poyka.ripdpi.ui.screens.status

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StatusDiagnosticRunControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `quick check and full analysis have separate actions`() {
        var quickRuns = 0
        var fullRuns = 0
        val diagnostics = StatusDiagnosticsUiState(analysisAction = StatusDiagnosticsActionUiState(enabled = true))
        composeRule.setContent {
            RipDpiTheme {
                StatusScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    homeDiagnostics = diagnostics,
                    diagnosticCard =
                        buildDiagnosticCard(
                            diagnostics,
                            AndroidStringResolver(RuntimeEnvironment.getApplication()),
                        ),
                    onToggleConnection = {},
                    onDiagnosticRun = { quickRuns++ },
                    onRunFullDiagnosticRun = { fullRuns++ },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        expandModesAndDiagnostics()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.Diagnostic.name))
            .performScrollTo()
            .assertTextEquals("Quick scan")
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis)
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, quickRuns)
            assertEquals(1, fullRuns)
        }
    }

    @Test
    fun `running home analysis reveals stop and blocks another full run`() {
        var stops = 0
        var quickRuns = 0
        val diagnostics =
            StatusDiagnosticsUiState(
                analysisRunStatus = StatusDiagnosticsRunUiStatus.RUNNING,
                analysisAction = StatusDiagnosticsActionUiState(busy = true),
            )
        composeRule.setContent {
            RipDpiTheme {
                StatusScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    homeDiagnostics = diagnostics,
                    diagnosticCard =
                        buildDiagnosticCard(
                            diagnostics,
                            AndroidStringResolver(RuntimeEnvironment.getApplication()),
                        ),
                    onToggleConnection = {},
                    onDiagnosticRun = { quickRuns++ },
                    onCancelDiagnosticRun = { stops++ },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.Diagnostic.name))
            .performScrollTo()
            .assertIsEnabled()
            .assertTextEquals(
                RuntimeEnvironment.getApplication().getString(
                    R.string.diagnostics_action_cancel,
                ),
            ).performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(1, stops)
            assertEquals(0, quickRuns)
        }
    }

    private fun expandModesAndDiagnostics() {
        composeRule.onNodeWithTag(RipDpiTestTags.HomeModesDiagnosticsHeader).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun modeCards() = StatusMode.entries.map { mode -> StatusModeCardUiState(mode = mode) }.toImmutableList()
}
