package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDnsActionsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `dns save leaves halted runtime for next start`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN)
            val repository = FakeAppSettingsRepository()
            val actions =
                createActions(
                    repository = repository,
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                )

            actions.setPlainDnsServer("9.9.9.9")
            advanceUntilIdle()

            val settings = repository.snapshot()
            assertEquals(DnsModePlainUdp, settings.dnsMode)
            assertEquals("9.9.9.9", settings.dnsIp)
            assertEquals(0, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
        }

    @Test
    fun `dns save restarts running runtime with active mode`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val actions =
                createActions(
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                )

            actions.setPlainDnsServer("9.9.9.9")
            runCurrent()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())

            serviceStateStore.setStatus(AppStatus.Halted, Mode.Proxy)
            advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
        }

    private fun createActions(
        repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        serviceController: FakeServiceController = FakeServiceController(),
    ): SettingsDnsActions =
        SettingsDnsActions(
            mutations =
                SettingsMutationRunner(
                    scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
                    appSettingsRepository = repository,
                    effects =
                        MutableSharedFlow(
                            replay = 16,
                            onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        ),
                ),
            serviceStateStore = serviceStateStore,
            serviceController = serviceController,
        )
}
