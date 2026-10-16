package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class SimpleDiagnosticsChangesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `completed analysis shows actual saved setting values`() {
        composeRule.setContent {
            RipDpiTheme {
                SimpleDiagnosticsStatus(
                    diagnostics =
                        HomeDiagnosticsUiState(
                            analysisRunStatus = HomeDiagnosticsRunUiStatus.COMPLETED,
                            analysisSheet =
                                HomeDiagnosticsAnalysisSheetUiState(
                                    runId = "completed-run",
                                    headline = "Check complete",
                                    summary = "Saved settings are available",
                                    appliedSettings =
                                        persistentListOf(
                                            DiagnosticsAppliedSetting(
                                                "Resolver",
                                                "Encrypted DNS",
                                            ),
                                        ),
                                ),
                        ),
                )
            }
        }
        composeRule.onNodeWithText("Resolver").assertIsDisplayed()
        composeRule.onNodeWithText("Encrypted DNS").assertIsDisplayed()
    }

    @Test
    fun `failed analysis still shows settings already saved`() {
        renderStatus(HomeDiagnosticsRunUiStatus.FAILED, sheet())
        composeRule.onNodeWithText("Resolver").assertIsDisplayed()
        composeRule.onNodeWithText("Encrypted DNS").assertIsDisplayed()
    }

    @Test
    fun `cancelled analysis with retained sheet never implies changes were undone`() {
        renderStatus(HomeDiagnosticsRunUiStatus.CANCELLED, sheet())
        composeRule.onNodeWithText(savedChangesNotice).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Share Logs & Results").assertDoesNotExist()
    }

    @Test
    fun `cancelled analysis without sheet warns that settings may have changed`() {
        renderStatus(HomeDiagnosticsRunUiStatus.CANCELLED)
        composeRule.onNodeWithText(savedChangesNotice).assertIsDisplayed()
    }

    @Test
    fun `completed analysis without sheet warns that settings may have changed`() {
        renderStatus(HomeDiagnosticsRunUiStatus.COMPLETED)
        composeRule.onNodeWithText(savedChangesNotice).assertIsDisplayed()
    }

    @Test
    fun `failed analysis without sheet warns that settings may have changed`() {
        renderStatus(HomeDiagnosticsRunUiStatus.FAILED)
        composeRule.onNodeWithText(savedChangesNotice).assertIsDisplayed()
    }

    @Test
    fun `running analysis does not present a retained sheet as newly applied settings`() {
        renderStatus(HomeDiagnosticsRunUiStatus.RUNNING, sheet())
        composeRule.onNodeWithText("Resolver").assertDoesNotExist()
        composeRule.onNodeWithText(savedChangesNotice).assertDoesNotExist()
    }

    private fun renderStatus(
        status: HomeDiagnosticsRunUiStatus,
        sheet: HomeDiagnosticsAnalysisSheetUiState? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SimpleDiagnosticsStatus(
                        diagnostics = HomeDiagnosticsUiState(analysisRunStatus = status, analysisSheet = sheet),
                    )
                }
            }
        }
    }

    private fun sheet() =
        HomeDiagnosticsAnalysisSheetUiState(
            runId = "run-with-saved-settings",
            headline = "Check outcome",
            summary = "A setting was saved before the run stopped",
            appliedSettings = persistentListOf(DiagnosticsAppliedSetting("Resolver", "Encrypted DNS")),
        )

    private companion object {
        const val savedChangesNotice =
            "This check may already have saved changes. Restore earlier app settings from a saved FULL backup."
    }
}
