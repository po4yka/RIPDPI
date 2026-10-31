package com.poyka.ripdpi.ui.screens.diagnostics

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsScreenUiState
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsLatestAuditUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w320dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsGuidedActionsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun startRequiresConfirmationAndRechecksAvailability() {
        val state = mutableStateOf(auditState())
        var calls = 0
        composeRule.setContent { Viewport { DiagnosticsGuidedActions(state.value, {}, { calls++ }) } }
        composeRule.onNodeWithText("Start Verified VPN").performClick()
        composeRule.onNodeWithText("Start VPN and check access?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, calls) }
        composeRule.onNodeWithText("Start and check").performClick()
        composeRule.runOnIdle { assertEquals(1, calls) }
        composeRule.onNodeWithText("Start Verified VPN").performClick()
        composeRule.runOnIdle {
            state.value = state.value.copy(verifiedVpnAction = state.value.verifiedVpnAction.copy(enabled = false))
        }
        composeRule.onNodeWithText("Start and check").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun recheckUsesQuickAnalysisAndShowsMeasuredComparison() {
        var checks = 0
        composeRule.setContent { Viewport { DiagnosticsGuidedActions(auditState(), { checks++ }, {}) } }
        composeRule
            .onNodeWithText(
                "0 newly failed, 1 newly recovered, 3 unchanged.",
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Check this network again").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, checks) }
    }

    @Test
    fun interstageCompositeKeepsStopAvailable() {
        var cancellations = 0
        composeRule.setContent {
            val pager =
                rememberPagerState(initialPage = DiagnosticsSection.Scan.ordinal) { DiagnosticsSection.entries.size }
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsScreenUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            uiPersona = "simple",
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    analysisRunStatus = HomeDiagnosticsRunUiStatus.RUNNING,
                                    analysisAction = HomeDiagnosticsActionUiState(busy = true),
                                ),
                        ),
                    pagerState = pager,
                    actions = DiagnosticsScreenActions(onCancelScan = { cancellations++ }),
                )
            }
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.DiagnosticsScanCancelAction)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun verificationAdmissionBlocksManualControlsWithoutCompositeStop() {
        val profile = DiagnosticsProfileOptionUiModel("check", "Connection check", "builtin")
        val scan = mutableStateOf(DiagnosticsScanUiModel())
        composeRule.setContent {
            val pager =
                rememberPagerState(initialPage = DiagnosticsSection.Scan.ordinal) { DiagnosticsSection.entries.size }
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsScreenUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            scan = scan.value,
                            uiPersona = "simple",
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    analysisRunStatus = HomeDiagnosticsRunUiStatus.IDLE,
                                    analysisAction = HomeDiagnosticsActionUiState(busy = true),
                                ),
                        ),
                    pagerState = pager,
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanCancelAction).assertDoesNotExist()
        composeRule.runOnIdle {
            scan.value =
                scan.value.copy(
                    selectedProfile = profile,
                    selectedProfileId = profile.id,
                )
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchOpen).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanRunRawAction).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanRunInPathAction).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanCancelAction).assertDoesNotExist()
    }

    @Test
    fun activeManualProgressKeepsCancelAvailableDuringVerificationAdmission() {
        var cancellations = 0
        composeRule.setContent {
            val pager =
                rememberPagerState(initialPage = DiagnosticsSection.Scan.ordinal) { DiagnosticsSection.entries.size }
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsScreenUiState(
                            selectedSection = DiagnosticsSection.Scan,
                            uiPersona = "simple",
                            scan =
                                DiagnosticsScanUiModel(
                                    activeProgress =
                                        DiagnosticsProgressUiModel(
                                            phase = "dns",
                                            summary = "Checking DNS",
                                            completedSteps = 0,
                                            totalSteps = 4,
                                            fraction = 0f,
                                            scanKind = ScanKind.CONNECTIVITY,
                                            isFullAudit = false,
                                            scanStartedAtMs = 0L,
                                            phaseSteps = persistentListOf(),
                                            currentProbeLabel = "DNS",
                                        ),
                                ),
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    analysisRunStatus = HomeDiagnosticsRunUiStatus.IDLE,
                                    analysisAction = HomeDiagnosticsActionUiState(busy = true),
                                ),
                        ),
                    pagerState = pager,
                    actions = DiagnosticsScreenActions(onCancelScan = { cancellations++ }),
                )
            }
        }
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.DiagnosticsScanCancelAction,
            ).performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun recheckIsDisabledWhileCompositeOwnsRun() {
        val busy = auditState().copy(analysisAction = HomeDiagnosticsActionUiState(enabled = true, busy = true))
        composeRule.setContent { Viewport { DiagnosticsGuidedActions(busy, {}, {}) } }
        composeRule.onNodeWithText("Check this network again").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun narrowLargeTextRendersWithoutClipping() {
        composeRule.setContent { Viewport(fontScale = 1.5f) { DiagnosticsGuidedActions(auditState(), {}, {}) } }
        val text = composeRule.onNodeWithText("Current network: connection checks completed")
        text.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertFalse(it.hasVisualOverflow) }
        val file = File("build/compose-previews/renders/diagnostics-guided-large-text.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use {
            assertTrue(
                composeRule
                    .onNodeWithTag("guided-viewport")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it),
            )
        }
    }

    @Composable
    private fun Viewport(
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            RipDpiTheme(themePreference = "light") {
                Box(
                    Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(RipDpiThemeTokens.colors.background)
                        .ripDpiTestTag("guided-viewport"),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }

    private fun auditState() =
        HomeDiagnosticsUiState(
            analysisAction = HomeDiagnosticsActionUiState(enabled = true),
            verifiedVpnAction = HomeDiagnosticsActionUiState(label = "Start Verified VPN", enabled = true),
            latestAudit =
                HomeDiagnosticsLatestAuditUiState(
                    headline = "Current network: connection checks completed",
                    summary = "DNS and connection tests completed on this network.",
                    comparisonSummary = "0 newly failed, 1 newly recovered, 3 unchanged.",
                ),
        )
}
