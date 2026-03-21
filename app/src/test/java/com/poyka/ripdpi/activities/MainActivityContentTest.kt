package com.poyka.ripdpi.activities

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.R
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
    fun `start configured mode request invokes primary action once and clears shell request`() {
        val serviceController = FakeServiceController()
        val controller =
            MainActivityShellController(
                MainActivity.createLaunchIntent(
                    context = RuntimeEnvironment.getApplication(),
                    requestStartConfiguredMode = true,
                ),
            )

        composeRule.setContent {
            MainActivityContent(
                viewModel = createViewModel(serviceController = serviceController),
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
        val collectorJob = collectorScope.launch {
            controller.hostCommands.collect { command ->
                commands += command
            }
        }
        composeRule.setContent {
            MainActivityContent(
                viewModel =
                    createViewModel(
                        permissionStatusProvider =
                            FakePermissionStatusProvider(
                                PermissionSnapshot(
                                    vpnConsent = PermissionStatus.RequiresSystemPrompt,
                                    notifications = PermissionStatus.Granted,
                                    batteryOptimization = PermissionStatus.Granted,
                                ),
                            ),
                    ),
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
        composeRule.setContent {
            MainActivityContent(
                viewModel = createViewModel(),
                controller = controller,
            )
        }

        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialogDismiss).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(RipDpiTestTags.VpnPermissionDialog)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.VpnPermissionDialog).assertDoesNotExist()
    }

    @Test
    fun `snackbar renders from shell ui event`() {
        val controller = MainActivityShellController().apply {
            onEffect(MainEffect.ShowError("boom"))
        }

        composeRule.setContent {
            MainActivityContent(
                viewModel = createViewModel(),
                controller = controller,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(RipDpiTestTags.MainErrorSnackbar)).fetchSemanticsNodes().isNotEmpty()
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
            diagnosticsManager = StubDiagnosticsManager(),
            stringResolver = FakeStringResolver(),
            trafficStatsReader = FakeTrafficStatsReader(),
            permissionPlatformBridge =
                FakePermissionPlatformBridge(
                    vpnPermissionIntent = Intent("fake.vpn.permission"),
                ),
            permissionStatusProvider = permissionStatusProvider,
            permissionCoordinator = PermissionCoordinator(),
        )
}
