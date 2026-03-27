package com.poyka.ripdpi.activities

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
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
class DiagnosticsRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `route initializes the view model once`() {
        val diagnosticsBootstrapper = StubDiagnosticsBootstrapper()
        val viewModel =
            createDiagnosticsViewModel(
                appContext = RuntimeEnvironment.getApplication(),
                diagnosticsBootstrapper = diagnosticsBootstrapper,
                diagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
                appSettingsRepository = FakeAppSettingsRepository(),
                initialize = false,
            )
        val recomposeTrigger = mutableIntStateOf(0)

        composeRule.setContent {
            recomposeTrigger.intValue
            RipDpiTheme {
                DiagnosticsRoute(
                    onShareArchive = { _, _ -> },
                    onSaveArchive = { _, _ -> },
                    onShareSummary = { _, _ -> },
                    onSaveLogs = {},
                    onOpenHistory = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { diagnosticsBootstrapper.initializeCalls == 1 }

        composeRule.runOnUiThread {
            recomposeTrigger.intValue += 1
        }
        composeRule.waitForIdle()

        assertEquals(1, diagnosticsBootstrapper.initializeCalls)
    }

    @Test
    fun `route shows snackbar when scan start fails`() {
        val manager =
            FakeDiagnosticsManager().apply {
                scanController.onStartScan = { _, _ ->
                    throw IllegalStateException("boom")
                }
            }
        val viewModel =
            createDiagnosticsViewModel(
                appContext = RuntimeEnvironment.getApplication(),
                diagnosticsManager = manager,
                appSettingsRepository = FakeAppSettingsRepository(),
            )

        composeRule.setContent {
            RipDpiTheme {
                DiagnosticsRoute(
                    onShareArchive = { _, _ -> },
                    onSaveArchive = { _, _ -> },
                    onShareSummary = { _, _ -> },
                    onSaveLogs = {},
                    onOpenHistory = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.runOnUiThread {
            viewModel.startRawScan()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
    }

    @Test
    fun `route shows hidden probe conflict dialog`() {
        val manager =
            FakeDiagnosticsManager().apply {
                scanController.onStartScan = { _, _ ->
                    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                        requestId = "hidden-request",
                        profileName = "Automatic probing",
                        pathMode = ScanPathMode.RAW_PATH,
                        scanKind = ScanKind.STRATEGY_PROBE,
                        isFullAudit = false,
                    )
                }
            }
        val viewModel =
            createDiagnosticsViewModel(
                appContext = RuntimeEnvironment.getApplication(),
                diagnosticsManager = manager,
                appSettingsRepository = FakeAppSettingsRepository(),
            )

        composeRule.setContent {
            RipDpiTheme {
                DiagnosticsRoute(
                    onShareArchive = { _, _ -> },
                    onSaveArchive = { _, _ -> },
                    onShareSummary = { _, _ -> },
                    onSaveLogs = {},
                    onOpenHistory = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.runOnUiThread {
            viewModel.startRawScan()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule
                    .onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictDialog)
                    .assertIsDisplayed()
            }.isSuccess
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictDialog)
            .assertIsDisplayed()
    }

    @Test
    fun `route shows snackbar when manual scan waits for hidden probe`() {
        val manager =
            FakeDiagnosticsManager().apply {
                scanController.hiddenAutomaticProbeActive.value = true
                scanController.onStartScan = { _, _ ->
                    DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                        requestId = "hidden-request",
                        profileName = "Automatic probing",
                        pathMode = ScanPathMode.RAW_PATH,
                        scanKind = ScanKind.STRATEGY_PROBE,
                        isFullAudit = false,
                    )
                }
            }
        val viewModel =
            createDiagnosticsViewModel(
                appContext = RuntimeEnvironment.getApplication(),
                diagnosticsManager = manager,
                appSettingsRepository = FakeAppSettingsRepository(),
            )

        composeRule.setContent {
            RipDpiTheme {
                DiagnosticsRoute(
                    onShareArchive = { _, _ -> },
                    onSaveArchive = { _, _ -> },
                    onShareSummary = { _, _ -> },
                    onSaveLogs = {},
                    onOpenHistory = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.runOnUiThread {
            viewModel.startRawScan()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule
                    .onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictWait)
                    .assertIsDisplayed()
            }.isSuccess
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictWait)
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
    }
}
