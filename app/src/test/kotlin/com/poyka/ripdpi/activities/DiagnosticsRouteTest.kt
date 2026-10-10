package com.poyka.ripdpi.activities

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRouteCallbacks
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScreenActions
import com.poyka.ripdpi.ui.screens.diagnostics.withNavigationCallbacks
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `manual or verification busy state cancels the native scan controller`() =
        runTest {
            val manager = FakeDiagnosticsManager()
            var scanCancellations = 0
            var homeCancellations = 0
            manager.scanController.onCancel = { scanCancellations++ }
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    initialize = false,
                )
            listOf(HomeDiagnosticsRunUiStatus.IDLE, HomeDiagnosticsRunUiStatus.COMPLETED).forEach { status ->
                val actions =
                    DiagnosticsScreenActions().withNavigationCallbacks(
                        viewModel,
                        DiagnosticsRouteCallbacks(onCancelHomeAnalysis = { homeCancellations++ }),
                        HomeDiagnosticsUiState(
                            analysisAction = HomeDiagnosticsActionUiState(busy = true),
                            analysisRunStatus = status,
                        ),
                    )
                actions.onCancelScan()
                runCurrent()
            }
            assertEquals(2, scanCancellations)
            assertEquals(0, homeCancellations)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `home startup or running state cancels the whole composite`() =
        runTest {
            val manager = FakeDiagnosticsManager()
            var scanCancellations = 0
            var homeCancellations = 0
            manager.scanController.onCancel = { scanCancellations++ }
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    initialize = false,
                )
            listOf(HomeDiagnosticsRunUiStatus.STARTING, HomeDiagnosticsRunUiStatus.RUNNING).forEach { status ->
                val actions =
                    DiagnosticsScreenActions().withNavigationCallbacks(
                        viewModel,
                        DiagnosticsRouteCallbacks(onCancelHomeAnalysis = { homeCancellations++ }),
                        HomeDiagnosticsUiState(analysisRunStatus = status),
                    )
                actions.onCancelScan()
                runCurrent()
            }
            assertEquals(0, scanCancellations)
            assertEquals(2, homeCancellations)
        }

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
    fun `route auto-start argument starts raw-path scan once`() {
        val manager = FakeDiagnosticsManager()
        var startCount = 0
        var startedPathMode: ScanPathMode? = null
        manager.scanController.onStartScan = { pathMode, _ ->
            startCount += 1
            startedPathMode = pathMode
            DiagnosticsManualScanStartResult.Started("session-auto")
        }
        val viewModel =
            createDiagnosticsViewModel(
                appContext = RuntimeEnvironment.getApplication(),
                diagnosticsManager = manager,
                appSettingsRepository = FakeAppSettingsRepository(),
                autoStartScan = true,
                initialize = false,
            )
        val recomposeTrigger = mutableIntStateOf(0)

        composeRule.setContent {
            recomposeTrigger.intValue
            RipDpiTheme {
                DiagnosticsRoute(
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { startCount == 1 }
        composeRule.runOnUiThread {
            recomposeTrigger.intValue += 1
        }
        composeRule.waitForIdle()

        assertEquals(1, startCount)
        assertEquals(ScanPathMode.RAW_PATH, startedPathMode)
    }

    @Test
    fun `advanced dashboard run scan button is enabled and starts raw-path scan`() {
        val manager = FakeDiagnosticsManager()
        var startedPathMode: ScanPathMode? = null
        manager.scanController.onStartScan = { pathMode, _ ->
            startedPathMode = pathMode
            DiagnosticsManualScanStartResult.Started("session-dashboard")
        }
        val viewModel =
            createDiagnosticsViewModel(
                appContext = RuntimeEnvironment.getApplication(),
                diagnosticsManager = manager,
                appSettingsRepository =
                    FakeAppSettingsRepository(
                        initialSettings =
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setUiPersona("advanced")
                                .build(),
                    ),
            )

        composeRule.setContent {
            RipDpiTheme {
                DiagnosticsRoute(
                    viewModel = viewModel,
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { startedPathMode == ScanPathMode.RAW_PATH }

        assertEquals(ScanPathMode.RAW_PATH, startedPathMode)
    }

    @Test
    fun `guided dashboard dispatches quick network check without a competing manual scan`() {
        val manager = FakeDiagnosticsManager()
        var checks = 0
        var manualPath: ScanPathMode? = null
        manager.scanController.onStartScan = { pathMode, _ ->
            manualPath = pathMode
            DiagnosticsManualScanStartResult.Started("unexpected-manual")
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
                    viewModel = viewModel,
                    callbacks = DiagnosticsRouteCallbacks(onCheckNetwork = { checks++ }),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.screenUiState.value.uiPersona == "simple" }
        composeRule
            .onNodeWithTag(RipDpiTestTags.DiagnosticsOverviewRunScanAction)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, checks)
            assertNull(manualPath)
        }
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
