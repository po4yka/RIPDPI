package com.poyka.ripdpi.ui.screens.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.buildDiagnosticCard
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
class HomeDiagnosticRunControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `quick check and full analysis have separate actions`() {
        var quickRuns = 0
        var fullRuns = 0
        val diagnostics = HomeDiagnosticsUiState(analysisAction = HomeDiagnosticsActionUiState(enabled = true))
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
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
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.Diagnostic.name))
            .performScrollTo()
            .assertTextEquals("Quick Scan")
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
            HomeDiagnosticsUiState(
                analysisRunStatus = HomeDiagnosticsRunUiStatus.RUNNING,
                analysisAction = HomeDiagnosticsActionUiState(busy = true),
            )
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
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
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.Diagnostic.name))
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

    private fun modeCards() = HomeMode.entries.map { mode -> HomeModeCardUiState(mode = mode) }.toImmutableList()
}
