package com.poyka.ripdpi.activities

import android.content.Intent
import app.cash.turbine.test
import com.poyka.ripdpi.permissions.PermissionKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainActivityShellControllerTest {
    @Test
    fun `initial intent sets home and configured start requests`() {
        val controller =
            MainActivityShellController(
                MainActivity.createLaunchIntent(
                    context = RuntimeEnvironment.getApplication(),
                    openHome = true,
                    requestStartConfiguredMode = true,
                ),
            )

        assertTrue(controller.state.value.launchHomeRequested)
        assertTrue(controller.state.value.startConfiguredModeRequested)
    }

    @Test
    fun `new intent merges outstanding shell requests`() {
        val controller = MainActivityShellController()

        controller.onNewIntent(
            MainActivity.createLaunchIntent(
                context = RuntimeEnvironment.getApplication(),
                openHome = true,
                requestStartConfiguredMode = true,
            ),
        )

        assertTrue(controller.state.value.launchHomeRequested)
        assertTrue(controller.state.value.startConfiguredModeRequested)
    }

    @Test
    fun `permission effects emit host commands`() =
        runTest {
            val controller = MainActivityShellController()
            val vpnIntent = Intent("test.vpn")
            val batteryIntent = Intent("test.battery")

            controller.hostCommands.test {
                controller.onEffect(MainEffect.RequestPermission(kind = PermissionKind.Notifications))
                assertEquals(MainActivityHostCommand.RequestNotificationsPermission, awaitItem())

                controller.onEffect(
                    MainEffect.RequestPermission(
                        kind = PermissionKind.VpnConsent,
                        payload = vpnIntent,
                    ),
                )
                assertEquals(MainActivityHostCommand.RequestVpnConsent(vpnIntent), awaitItem())

                controller.onEffect(
                    MainEffect.RequestPermission(
                        kind = PermissionKind.BatteryOptimization,
                        payload = batteryIntent,
                    ),
                )
                assertEquals(MainActivityHostCommand.RequestBatteryOptimization(batteryIntent), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `open app settings and share requests emit host commands`() =
        runTest {
            val controller = MainActivityShellController()
            val appSettingsIntent = Intent("test.settings")

            controller.hostCommands.test {
                controller.onEffect(MainEffect.OpenAppSettings(appSettingsIntent))
                assertEquals(MainActivityHostCommand.OpenIntent(appSettingsIntent), awaitItem())

                controller.requestSaveLogs()
                assertEquals(MainActivityHostCommand.SaveLogs, awaitItem())

                controller.requestShareDebugBundle()
                assertEquals(MainActivityHostCommand.ShareDebugBundle, awaitItem())

                controller.requestSaveDiagnosticsArchive("/tmp/archive.zip", "archive.zip")
                assertEquals(
                    MainActivityHostCommand.SaveDiagnosticsArchive(
                        filePath = "/tmp/archive.zip",
                        fileName = "archive.zip",
                    ),
                    awaitItem(),
                )

                controller.requestShareDiagnosticsArchive("/tmp/share.zip", "share.zip")
                assertEquals(
                    MainActivityHostCommand.ShareDiagnosticsArchive(
                        filePath = "/tmp/share.zip",
                        fileName = "share.zip",
                    ),
                    awaitItem(),
                )

                controller.requestShareDiagnosticsSummary("Title", "Body")
                assertEquals(
                    MainActivityHostCommand.ShareDiagnosticsSummary(
                        title = "Title",
                        body = "Body",
                    ),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `show vpn dialog effect updates shell state`() {
        val controller = MainActivityShellController()

        controller.onEffect(MainEffect.ShowVpnPermissionDialog)

        assertTrue(controller.state.value.vpnPermissionDialogVisible)
    }

    @Test
    fun `show error effect emits snackbar event`() =
        runTest {
            val controller = MainActivityShellController()

            controller.uiEvents.test {
                controller.onEffect(MainEffect.ShowError("boom"))

                assertEquals(MainActivityUiEvent.ShowErrorSnackbar("boom"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connected and connecting states dismiss vpn dialog`() {
        val controller = MainActivityShellController().apply {
            showVpnPermissionDialog()
        }

        controller.onConnectionStateChanged(ConnectionState.Connecting)
        assertFalse(controller.state.value.vpnPermissionDialogVisible)

        controller.showVpnPermissionDialog()
        controller.onConnectionStateChanged(ConnectionState.Connected)
        assertFalse(controller.state.value.vpnPermissionDialogVisible)
    }

    @Test
    fun `start configured mode request is consumed once`() {
        val controller =
            MainActivityShellController(
                MainActivity.createLaunchIntent(
                    context = RuntimeEnvironment.getApplication(),
                    requestStartConfiguredMode = true,
                ),
            )

        assertTrue(controller.state.value.startConfiguredModeRequested)

        controller.consumeStartConfiguredModeRequest()

        assertFalse(controller.state.value.startConfiguredModeRequested)
    }
}
