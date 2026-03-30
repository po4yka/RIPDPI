package com.poyka.ripdpi.activities

import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MainActivityContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `composition initializes the view model once`() {
        val permissionStatusProvider = FakePermissionStatusProvider()
        val controller = MainActivityShellController()
        val recomposeTrigger = mutableIntStateOf(0)
        val viewModel = createViewModel(permissionStatusProvider = permissionStatusProvider)

        composeRule.setContent {
            recomposeTrigger.intValue
            MainActivityContent(
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { permissionStatusProvider.currentSnapshotCalls > 0 }
        val snapshotCalls = permissionStatusProvider.currentSnapshotCalls

        composeRule.runOnUiThread {
            recomposeTrigger.intValue += 1
        }
        composeRule.waitForIdle()

        assertEquals(snapshotCalls, permissionStatusProvider.currentSnapshotCalls)
    }

    @Test
    fun `start configured mode request invokes primary action once and clears shell request`() {
        val serviceController = FakeServiceController()
        val controller =
            MainActivityShellController(
                MainActivity.createLaunchIntent(
                    context = RuntimeEnvironment.getApplication(),
                    requestStartConfiguredMode = true,
                ),
            )
        val viewModel = createViewModel(serviceController = serviceController)

        composeRule.setContent {
            MainActivityContent(
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { serviceController.startedModes.size == 1 }

        assertEquals(1, serviceController.startedModes.size)
        assertFalse(controller.state.value.startConfiguredModeRequested)
    }

    @Test
    fun `vpn dialog renders from shell state and continue emits vpn consent host command`() {
        val controller = MainActivityShellController().apply { showVpnPermissionDialog() }
        val commands = CopyOnWriteArrayList<MainActivityHostCommand>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectorJob =
            collectorScope.launch {
                controller.hostCommands.collect { command ->
                    commands += command
                }
            }
        val viewModel =
            createViewModel(
                permissionStatusProvider =
                    FakePermissionStatusProvider(
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.Granted,
                        ),
                    ),
            )
        composeRule.setContent {
            MainActivityContent(
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialogContinue).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            commands.any { command -> command is MainActivityHostCommand.RequestVpnConsent }
        }

        collectorJob.cancel()
    }

    @Test
    fun `vpn dialog dismiss hides dialog`() {
        val controller = MainActivityShellController().apply { showVpnPermissionDialog() }
        val viewModel = createViewModel()
        composeRule.setContent {
            MainActivityContent(
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialogDismiss).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasTestTag(RipDpiTestTags.VpnPermissionDialog))
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertDoesNotExist()
    }

    @Test
    fun `snackbar renders from shell ui event`() {
        val controller =
            MainActivityShellController().apply {
                onEffect(MainEffect.ShowError("boom"))
            }
        val viewModel = createViewModel()

        composeRule.setContent {
            MainActivityContent(
                viewModel = viewModel,
                controller = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasTestTag(RipDpiTestTags.MainErrorSnackbar))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.MainErrorSnackbar).assertIsDisplayed()
        composeRule.onAllNodesWithText("boom").assertCountEquals(1)
    }

    private fun createViewModel(
        appSettingsRepository: FakeAppSettingsRepository =
            FakeAppSettingsRepository(
                AppSettings
                    .newBuilder()
                    .setOnboardingComplete(true)
                    .setRipdpiMode("vpn")
                    .build(),
            ),
        serviceController: FakeServiceController = FakeServiceController(),
        permissionStatusProvider: FakePermissionStatusProvider = FakePermissionStatusProvider(),
    ): MainViewModel =
        MainViewModel(
            appSettingsRepository = appSettingsRepository,
            serviceStateStore = FakeServiceStateStore(),
            serviceController = serviceController,
            diagnosticsTimelineSource = StubDiagnosticsTimelineSource(),
            diagnosticsScanController = StubDiagnosticsScanController(),
            diagnosticsShareService = StubDiagnosticsShareService(),
            homeDiagnosticsServices =
                HomeDiagnosticsServices(
                    workflowService = StubDiagnosticsHomeWorkflowService(),
                    compositeRunService = StubDiagnosticsHomeCompositeRunService(),
                ),
            stringResolver = FakeStringResolver(),
            trafficStatsReader = FakeTrafficStatsReader(),
            permissionPlatformBridge =
                FakePermissionPlatformBridge(
                    vpnPermissionIntent = Intent("fake.vpn.permission"),
                ),
            permissionStatusProvider = permissionStatusProvider,
            permissionCoordinator = PermissionCoordinator(),
            crashReportReader =
                com.poyka.ripdpi.diagnostics.crash.CrashReportReader(
                    java.io.File(System.getProperty("java.io.tmpdir"), "ripdpi-test-crash-reports"),
                ),
            appLockLifecycleObserver =
                com.poyka.ripdpi.security
                    .AppLockLifecycleObserver(RuntimeEnvironment.getApplication()),
        )
}
