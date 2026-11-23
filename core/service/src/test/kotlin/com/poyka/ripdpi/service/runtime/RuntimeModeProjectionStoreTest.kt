package com.poyka.ripdpi.service.runtime

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.fakes.FakeServiceStateStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.LocalPacketBackendRuntime
import com.poyka.ripdpi.services.PacketBackendRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [RuntimeModeProjectionStore] — the live, read-only [RuntimeModeProjection]
 * observable.
 *
 * The store only derives, never drives: these tests confirm it re-derives the
 * projection in source-change order and reports the unknown / disabled / active
 * cases the projection is designed to distinguish.
 */
class RuntimeModeProjectionStoreTest {
    @Test
    fun `unobserved layers stay unknown until a source reports them`() =
        runTest {
            val store =
                RuntimeModeProjectionStore(
                    FakeServiceStateStore(AppStatus.Halted to Mode.Proxy),
                    FakeAppSettingsRepository(),
                )

            val projection = store.projection.first()

            assertEquals(AppStatus.Halted, projection.appStatus)
            assertEquals(Mode.Proxy, projection.mode)
            assertEquals(RuntimeLayerState.Unknown, projection.diagnosticsScan)
            assertNull(projection.serviceStatus)
            assertNull(projection.lifecyclePhase)
        }

    @Test
    fun `local edition projects a disabled backend even with a saved request`() =
        runTest {
            val settings = FakeAppSettingsRepository().apply { setPacketBackendRequested(true) }
            val store =
                RuntimeModeProjectionStore(
                    FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    settings,
                )

            assertEquals(PacketBackendState.Disabled, store.projection.first().packetBackend)
        }

    @Test
    fun `selected packet backend projects enabled-unavailable while the socket is unobserved`() =
        runTest {
            val settings = FakeAppSettingsRepository().apply { setPacketBackendRequested(true) }
            val store =
                RuntimeModeProjectionStore(
                    FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    settings,
                    SelectablePacketBackend,
                )

            assertEquals(PacketBackendState.EnabledUnavailable, store.projection.first().packetBackend)
        }

    @Test
    fun `relay layer follows relay telemetry production`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val store = RuntimeModeProjectionStore(stateStore, FakeAppSettingsRepository())

            assertEquals(RuntimeLayerState.Inactive, store.projection.first().relay)

            stateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    relayTelemetryStatus = RuntimeTelemetryStatus(state = RuntimeTelemetryState.Snapshot),
                ),
            )

            assertEquals(RuntimeLayerState.Active, store.projection.first().relay)
        }

    @Test
    fun `marking a diagnostics scan flips the diagnostics layer active then inactive`() =
        runTest {
            val store =
                RuntimeModeProjectionStore(
                    FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                    FakeAppSettingsRepository(),
                )

            store.markDiagnosticsScanActive(true)
            assertEquals(RuntimeLayerState.Active, store.projection.first().diagnosticsScan)

            store.markDiagnosticsScanActive(false)
            assertEquals(RuntimeLayerState.Inactive, store.projection.first().diagnosticsScan)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `projection re-derives in source-change order`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy)
            val settings = FakeAppSettingsRepository()
            val store = RuntimeModeProjectionStore(stateStore, settings, SelectablePacketBackend)

            val emissions = mutableListOf<RuntimeModeProjection>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    store.projection.toList(emissions)
                }

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            store.markDiagnosticsScanActive(true)
            settings.setPacketBackendRequested(true)
            collector.cancel()

            assertEquals(4, emissions.size)

            assertEquals(AppStatus.Halted, emissions[0].appStatus)
            assertEquals(RuntimeLayerState.Unknown, emissions[0].diagnosticsScan)
            assertEquals(PacketBackendState.Disabled, emissions[0].packetBackend)

            assertEquals(AppStatus.Running, emissions[1].appStatus)
            assertEquals(Mode.VPN, emissions[1].mode)
            assertEquals(RuntimeLayerState.Unknown, emissions[1].diagnosticsScan)

            assertEquals(RuntimeLayerState.Active, emissions[2].diagnosticsScan)
            assertEquals(PacketBackendState.Disabled, emissions[2].packetBackend)

            assertEquals(PacketBackendState.EnabledUnavailable, emissions[3].packetBackend)
            assertEquals(RuntimeLayerState.Active, emissions[3].diagnosticsScan)
        }
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }

    fun setPacketBackendRequested(enabled: Boolean) {
        state.value =
            state.value
                .toBuilder()
                .setUiPersona(if (enabled) "advanced" else "simple")
                .build()
    }
}

private object SelectablePacketBackend : PacketBackendRuntime by LocalPacketBackendRuntime {
    override fun allowsExternalOperations(settings: AppSettings): Boolean = settings.uiPersona == "advanced"
}
