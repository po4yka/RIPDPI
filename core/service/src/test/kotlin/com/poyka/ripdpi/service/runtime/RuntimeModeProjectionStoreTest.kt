package com.poyka.ripdpi.service.runtime

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `root mode off projects the disabled root-helper state`() =
        runTest {
            val settings = FakeAppSettingsRepository().apply { setRootModeEnabled(false) }
            val store =
                RuntimeModeProjectionStore(
                    FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    settings,
                )

            assertEquals(RootHelperState.Disabled, store.projection.first().rootHelper)
        }

    @Test
    fun `root mode on projects enabled-unavailable while the socket is unobserved`() =
        runTest {
            val settings = FakeAppSettingsRepository().apply { setRootModeEnabled(true) }
            val store =
                RuntimeModeProjectionStore(
                    FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    settings,
                )

            assertEquals(RootHelperState.EnabledUnavailable, store.projection.first().rootHelper)
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
            val store = RuntimeModeProjectionStore(stateStore, settings)

            val emissions = mutableListOf<RuntimeModeProjection>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    store.projection.toList(emissions)
                }

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            store.markDiagnosticsScanActive(true)
            settings.setRootModeEnabled(true)
            collector.cancel()

            assertEquals(4, emissions.size)

            assertEquals(AppStatus.Halted, emissions[0].appStatus)
            assertEquals(RuntimeLayerState.Unknown, emissions[0].diagnosticsScan)
            assertEquals(RootHelperState.Disabled, emissions[0].rootHelper)

            assertEquals(AppStatus.Running, emissions[1].appStatus)
            assertEquals(Mode.VPN, emissions[1].mode)
            assertEquals(RuntimeLayerState.Unknown, emissions[1].diagnosticsScan)

            assertEquals(RuntimeLayerState.Active, emissions[2].diagnosticsScan)
            assertEquals(RootHelperState.Disabled, emissions[2].rootHelper)

            assertEquals(RootHelperState.EnabledUnavailable, emissions[3].rootHelper)
            assertEquals(RuntimeLayerState.Active, emissions[3].diagnosticsScan)
        }
}

private class FakeServiceStateStore(
    initial: Pair<AppStatus, Mode>,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initial)
    private val eventState = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState
    override val events: SharedFlow<ServiceEvent> = eventState
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) = Unit

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
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

    fun setRootModeEnabled(enabled: Boolean) {
        state.value =
            state.value
                .toBuilder()
                .setRootModeEnabled(enabled)
                .build()
    }
}
