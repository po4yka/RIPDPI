package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DiagnosticsRuntimeCoordinatorTest {
    @Test
    fun `stop failure aborts raw path scan before block runs`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    stopFailure = IOException("stop failed")
                }
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    serviceController = controller,
                    serviceStateStore = stateStore,
                    appSettingsRepository = FakeCoordinatorSettingsRepository(),
                    waitAttempts = 2,
                    waitDelayMs = 0,
                )
            var blockRan = false

            val error =
                runCatching {
                    coordinator.runRawPathScan {
                        blockRan = true
                    }
                }.exceptionOrNull()

            assertTrue(error is IOException)
            assertFalse(blockRan)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)
        }

    @Test
    fun `resume start failure propagates after raw path scan block completes`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller =
                FakeServiceController(stateStore).apply {
                    startFailure = IOException("resume failed")
                }
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    serviceController = controller,
                    serviceStateStore = stateStore,
                    appSettingsRepository =
                        FakeCoordinatorSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setDiagnosticsAutoResumeAfterRawScan(true)
                                .build(),
                        ),
                    waitAttempts = 2,
                    waitDelayMs = 0,
                )
            var blockRan = false

            val error =
                runCatching {
                    coordinator.runRawPathScan {
                        blockRan = true
                    }
                }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(blockRan)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `timeout waiting for halted state fails raw path scan`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    transitionOnStop = false
                }
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    serviceController = controller,
                    serviceStateStore = stateStore,
                    appSettingsRepository = FakeCoordinatorSettingsRepository(),
                    waitAttempts = 2,
                    waitDelayMs = 0,
                )

            val error =
                runCatching {
                    coordinator.runRawPathScan {}
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(error?.message?.contains("Timed out waiting for service status Halted") == true)
            assertEquals(1, controller.stopCount)
        }

    @Test
    fun `timeout waiting for resumed running state fails after block`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    transitionOnStart = false
                }
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    serviceController = controller,
                    serviceStateStore = stateStore,
                    appSettingsRepository =
                        FakeCoordinatorSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setDiagnosticsAutoResumeAfterRawScan(true)
                                .build(),
                        ),
                    waitAttempts = 2,
                    waitDelayMs = 0,
                )
            var blockRan = false

            val error =
                runCatching {
                    coordinator.runRawPathScan {
                        blockRan = true
                    }
                }.exceptionOrNull()

            assertTrue(blockRan)
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message?.contains("Timed out waiting for service status Running") == true)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
        }

    @Test
    fun `automatic raw path scan always resumes running service even when user auto resume is disabled`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    serviceController = controller,
                    serviceStateStore = stateStore,
                    appSettingsRepository =
                        FakeCoordinatorSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setDiagnosticsAutoResumeAfterRawScan(false)
                                .build(),
                        ),
                    waitAttempts = 2,
                    waitDelayMs = 0,
                )
            var blockRan = false

            coordinator.runAutomaticRawPathScan {
                blockRan = true
                assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
            }

            assertTrue(blockRan)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }
}

private class FakeCoordinatorSettingsRepository(
    initial: AppSettings = AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)

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
}

private class FakeCoordinatorStateStore(
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
        sender: com.poyka.ripdpi.data.Sender,
        reason: FailureReason,
    ) = Unit

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

private class FakeServiceController(
    private val stateStore: FakeCoordinatorStateStore,
) : ServiceController {
    var stopFailure: Throwable? = null
    var startFailure: Throwable? = null
    var transitionOnStop: Boolean = true
    var transitionOnStart: Boolean = true
    var stopCount: Int = 0
    var startCount: Int = 0

    override fun start(mode: Mode) {
        startCount += 1
        startFailure?.let { throw it }
        if (transitionOnStart) {
            stateStore.setStatus(AppStatus.Running, mode)
        }
    }

    override fun stop() {
        stopCount += 1
        stopFailure?.let { throw it }
        if (transitionOnStop) {
            stateStore.setStatus(AppStatus.Halted, stateStore.status.value.second)
        }
    }
}
