package com.poyka.ripdpi.services

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeServiceController
import com.poyka.ripdpi.activities.FakeServiceStateStore
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickTileControllerTest {
    @Test
    fun `start listening mirrors service status changes`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore()
            val controller =
                QuickTileController(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceController = FakeServiceController(),
                    serviceStateStore = serviceStateStore,
                )
            val host = FakeQuickTileHost()
            val listeningScope = TestScope(StandardTestDispatcher(testScheduler))

            controller.onStartListening(host = host, scope = listeningScope)
            listeningScope.advanceUntilIdle()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            listeningScope.advanceUntilIdle()

            assertEquals(
                listOf(QuickTileVisualState.Inactive, QuickTileVisualState.Active),
                host.renderedStates,
            )
        }

    @Test
    fun `failed event shows failure toast and restores status`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore()
            val controller =
                QuickTileController(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceController = FakeServiceController(),
                    serviceStateStore = serviceStateStore,
                )
            val host = FakeQuickTileHost()
            val listeningScope = TestScope(StandardTestDispatcher(testScheduler))

            controller.onStartListening(host = host, scope = listeningScope)
            listeningScope.advanceUntilIdle()
            serviceStateStore.emitFailed(Sender.VPN, FailureReason.NativeError("boom"))
            listeningScope.advanceUntilIdle()

            assertEquals(listOf("VPN"), host.failures)
            assertEquals(QuickTileVisualState.Inactive, host.renderedStates.last())
        }

    @Test
    fun `click launches resolution instead of starting when permission is missing`() =
        runTest {
            val controller =
                QuickTileController(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceController = FakeServiceController(),
                    serviceStateStore = FakeServiceStateStore(),
                )
            val host =
                FakeQuickTileHost(
                    notificationsPermissionGranted = false,
                )
            val listeningScope = TestScope(StandardTestDispatcher(testScheduler))

            controller.onStartListening(host = host, scope = listeningScope)
            listeningScope.advanceUntilIdle()
            controller.onClick(host)
            listeningScope.advanceUntilIdle()

            assertEquals(1, host.launchResolutionCount)
            assertEquals(
                listOf(
                    QuickTileVisualState.Inactive,
                    QuickTileVisualState.Active,
                    QuickTileVisualState.Unavailable,
                    QuickTileVisualState.Inactive,
                ),
                host.renderedStates,
            )
        }

    @Test
    fun `click starts configured mode when permissions are already satisfied`() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setRipdpiMode(Mode.Proxy.preferenceValue)
                    .build()
            val serviceController = FakeServiceController()
            val controller =
                QuickTileController(
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    serviceController = serviceController,
                    serviceStateStore = FakeServiceStateStore(),
                )
            val host = FakeQuickTileHost()
            val listeningScope = TestScope(StandardTestDispatcher(testScheduler))

            controller.onStartListening(host = host, scope = listeningScope)
            listeningScope.advanceUntilIdle()
            controller.onClick(host)
            listeningScope.advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
            assertEquals(0, host.launchResolutionCount)
        }

    @Test
    fun `click stops service when runtime is active`() =
        runTest {
            val serviceController = FakeServiceController()
            val controller =
                QuickTileController(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceController = serviceController,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                )
            val host = FakeQuickTileHost()
            val listeningScope = TestScope(StandardTestDispatcher(testScheduler))

            controller.onStartListening(host = host, scope = listeningScope)
            listeningScope.advanceUntilIdle()
            controller.onClick(host)
            listeningScope.advanceUntilIdle()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
        }

    private class FakeQuickTileHost(
        private val notificationsPermissionGranted: Boolean = true,
        private val vpnPermissionRequired: Boolean = false,
    ) : QuickTileHost {
        val renderedStates = mutableListOf<QuickTileVisualState>()
        val failures = mutableListOf<String>()
        var launchResolutionCount: Int = 0

        override fun renderTileState(state: QuickTileVisualState) {
            renderedStates += state
        }

        override fun showStartFailure(senderName: String) {
            failures += senderName
        }

        override fun launchStartResolution() {
            launchResolutionCount += 1
        }

        override fun notificationsPermissionGranted(): Boolean = notificationsPermissionGranted

        override fun vpnPermissionRequired(): Boolean = vpnPermissionRequired
    }
}
