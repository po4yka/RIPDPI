package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsCompositeRunComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun betweenSessionsStopRemainsAvailableAndNewRunsStayDisabled() {
        val busy = mutableStateOf(true)
        var stopped = 0
        var runs = 0
        val profile = DiagnosticsProfileOptionUiModel("connectivity", "Connectivity", "bundled")
        composeRule.setContent {
            RipDpiTheme {
                ScanSection(
                    scan =
                        DiagnosticsScanUiModel(
                            selectedProfile = profile,
                            runRawEnabled = true,
                            runInPathEnabled = true,
                        ),
                    compositeRunBusy = busy.value,
                    onSelectProfile = {},
                    onRunRawScan = { runs++ },
                    onRunInPathScan = { runs++ },
                    onCancelScan = {
                        stopped++
                        busy.value = false
                    },
                    onOpenAdvancedSettings = {},
                    onOpenDnsSettings = {},
                    onRequestVpnPermission = {},
                    onSelectStrategyProbeCandidate = {},
                    onSelectProbe = {},
                    onOpenHistory = {},
                    onOpenModeEditor = {},
                    onOpenOwnedStackBrowser = {},
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchOpen).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanRunRawAction).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanRunInPathAction).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanCancelAction).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, stopped)
            assertEquals(0, runs)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanCancelAction).assertDoesNotExist()
        composeRule
            .onNodeWithTag(
                RipDpiTestTags.DiagnosticsScanRunRawAction,
            ).performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, runs) }
    }

    @Test
    fun startingCompositeCanStopBeforeSelectedProfileIsAvailable() {
        var stopped = 0
        composeRule.setContent {
            RipDpiTheme {
                ScanSection(
                    scan = DiagnosticsScanUiModel(),
                    compositeRunBusy = true,
                    onSelectProfile = {},
                    onRunRawScan = {},
                    onRunInPathScan = {},
                    onCancelScan = { stopped++ },
                    onOpenAdvancedSettings = {},
                    onOpenDnsSettings = {},
                    onRequestVpnPermission = {},
                    onSelectStrategyProbeCandidate = {},
                    onSelectProbe = {},
                    onOpenHistory = {},
                    onOpenModeEditor = {},
                    onOpenOwnedStackBrowser = {},
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsScanCancelAction).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, stopped) }
    }
}
