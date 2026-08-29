package com.poyka.ripdpi.activities

import android.content.Intent
import android.net.Uri
import app.cash.turbine.test
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.shortcuts.ExtraSelectGroupId
import com.poyka.ripdpi.shortcuts.ExtraSelectProfileId
import com.poyka.ripdpi.ui.navigation.Route
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Base64

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
                controller.onEffect(MainEffect.RequestPermission(kind = PermissionKind.LocalNetwork))
                assertEquals(MainActivityHostCommand.RequestLocalNetworkPermission, awaitItem())

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
    fun `handled host command is not replayed after lifecycle collector restarts`() =
        runTest {
            val controller = MainActivityShellController()

            controller.hostCommands.test {
                controller.requestSaveLogs()
                assertEquals(MainActivityHostCommand.SaveLogs, awaitItem())
            }

            controller.hostCommands.test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `host commands wait for lifecycle collector without collapsing`() =
        runTest {
            val controller = MainActivityShellController()

            controller.requestSaveLogs()
            controller.requestShareDebugBundle()

            controller.hostCommands.test {
                assertEquals(MainActivityHostCommand.SaveLogs, awaitItem())
                assertEquals(MainActivityHostCommand.ShareDebugBundle, awaitItem())
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
    fun `support code remains attached to the error snackbar event`() =
        runTest {
            val controller = MainActivityShellController()

            controller.uiEvents.test {
                controller.onEffect(
                    MainEffect.ShowError(
                        message = "Archive failed",
                        supportCode = "archive_storage",
                        supportPayload = "code=archive_storage\nstage=archive_prepare",
                    ),
                )

                val event = awaitItem() as MainActivityUiEvent.ShowErrorSnackbar
                assertEquals(
                    MainActivityUiEvent.ShowErrorSnackbar(
                        message = "Archive failed",
                        supportCode = "archive_storage",
                        supportPayload = "code=archive_storage\nstage=archive_prepare",
                    ),
                    event,
                )
                assertEquals("code=archive_storage\nstage=archive_prepare", event.supportText)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `show error effect emits snackbar event`() =
        runTest {
            val controller = MainActivityShellController()
            controller.onEffect(MainEffect.ShowError("boom"))

            controller.uiEvents.test {
                assertEquals(MainActivityUiEvent.ShowErrorSnackbar("boom"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            controller.uiEvents.test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connected and connecting states dismiss vpn dialog`() {
        val controller =
            MainActivityShellController().apply {
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

    @Test
    fun `stop configured mode extra populates shell state`() {
        val controller =
            MainActivityShellController(
                MainActivity.createLaunchIntent(
                    context = RuntimeEnvironment.getApplication(),
                    requestStopConfiguredMode = true,
                ),
            )

        assertTrue(controller.state.value.stopConfiguredModeRequested)

        controller.consumeStopConfiguredModeRequest()

        assertFalse(controller.state.value.stopConfiguredModeRequested)
    }

    @Test
    fun `disconnect deep link uri does not populate stop request`() {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("ripdpi://disconnect"))
        val controller = MainActivityShellController(intent)

        assertFalse(controller.state.value.stopConfiguredModeRequested)
    }

    @Test
    fun `support settings deep link populates import route request`() {
        val payload = """{"schema":1,"operations":[{"op":"set","path":"settings.app_theme","value":"dark"}]}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ripdpi://support-config?payload=$encoded"))

        val controller = MainActivityShellController(intent)

        assertEquals(Route.SupportSettings(packageJson = payload), controller.state.value.importRouteRequested)
    }

    @Test
    fun `selector intent extras populate selector selection request`() {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("ripdpi://connect")).apply {
                putExtra(ExtraSelectGroupId, "group-a")
                putExtra(ExtraSelectProfileId, "profile-x")
            }

        val controller = MainActivityShellController(intent)

        val request = controller.state.value.selectorSelectionRequested
        assertEquals("group-a", request?.groupId)
        assertEquals("profile-x", request?.profileId)

        controller.consumeSelectorSelectionRequest()

        assertNull(controller.state.value.selectorSelectionRequested)
    }

    @Test
    fun `partial selector intent extras do not populate selection`() {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("ripdpi://connect")).apply {
                putExtra(ExtraSelectGroupId, "group-a")
            }

        val controller = MainActivityShellController(intent)

        assertNull(controller.state.value.selectorSelectionRequested)
    }

    @Test
    fun `new intent merges stop and selector requests`() {
        val controller = MainActivityShellController()

        controller.onNewIntent(
            MainActivity.createLaunchIntent(
                context = RuntimeEnvironment.getApplication(),
                requestStopConfiguredMode = true,
            ),
        )

        assertTrue(controller.state.value.stopConfiguredModeRequested)

        controller.onNewIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("ripdpi://connect")).apply {
                putExtra(ExtraSelectGroupId, "group-b")
                putExtra(ExtraSelectProfileId, "profile-y")
            },
        )

        assertTrue(controller.state.value.stopConfiguredModeRequested)
        val request = controller.state.value.selectorSelectionRequested
        assertEquals("group-b", request?.groupId)
        assertEquals("profile-y", request?.profileId)
    }
}
