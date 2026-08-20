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
import com.poyka.ripdpi.service.runtime.RuntimeModeProjectionStore
import com.poyka.ripdpi.service.runtime.control.DefaultRuntimeControlPlane
import com.poyka.ripdpi.service.runtime.control.ServiceControllerRuntimeControlActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsRuntimeCoordinatorTest {
    @Test
    fun `concurrent raw path scans resume only after the last scan finishes`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()

            val first =
                async {
                    coordinator.runRawPathScan {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
            firstEntered.await()
            val second =
                async {
                    coordinator.runRawPathScan {
                        secondEntered.complete(Unit)
                        releaseSecond.await()
                    }
                }
            secondEntered.await()

            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)

            releaseFirst.complete(Unit)
            first.await()
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
            assertEquals(0, controller.startCount)

            releaseSecond.complete(Unit)
            second.await()
            assertEquals(AppStatus.Running to Mode.VPN, stateStore.status.value)
            assertEquals(1, controller.startCount)
        }

    @Test
    fun `stop failure aborts raw path scan before block runs`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller =
                FakeServiceController(stateStore).apply {
                    stopFailure = IOException("stop failed")
                }
            val coordinator = buildCoordinator(controller, stateStore)
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
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
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
            val coordinator = buildCoordinator(controller, stateStore)

            val error =
                runCatching {
                    coordinator.runRawPathScan {}
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(error?.message?.contains("Timed out waiting for service status Halted") == true)
            assertEquals(1, controller.stopCount)
        }

    @Test
    fun `cancellation while entering raw path window resumes an accepted runtime pause`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore).apply { transitionOnStop = false }
            val settings = FakeCoordinatorSettingsRepository()
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    waitAttempts = 50,
                    waitDelayMs = 1_000,
                )

            val scan = async { coordinator.runAutomaticRawPathScan {} }
            runCurrent()
            assertEquals(1, controller.stopCount)

            scan.cancelAndJoin()

            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
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
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
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
            assertTrue(
                "Unexpected resume failure: ${error?.message}",
                error?.message?.contains("Timed out waiting for service status Running") == true,
            )
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
        }

    @Test
    fun `rejected resume start skips running wait after raw path scan block`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller =
                FakeServiceController(stateStore).apply {
                    nextStartResult =
                        ServiceStartResult.Rejected(
                            mode = Mode.VPN,
                            reason = ServiceStartRejectionReason.VpnConsentMissing,
                        )
                }
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )
            var blockRan = false

            coordinator.runRawPathScan {
                blockRan = true
            }

            assertTrue(blockRan)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `automatic raw path scan always resumes running service even when user auto resume is disabled`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(false)
                            .build(),
                    ),
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

    @Test
    fun `explicit user stop during raw path scan suppresses diagnostics resume`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {
                controller.stop()
            }

            assertEquals(2, controller.stopCount)
            assertEquals(0, controller.startCount)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `user stop racing diagnostics resume remains the final operation`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            controller.afterDiagnosticsStart = controller::stop
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {}

            assertEquals(
                listOf("diagnostics-stop", "diagnostics-start", "user-stop", "diagnostics-stop"),
                controller.operations,
            )
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `user stop immediately before diagnostics resume is compensated`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            controller.beforeDiagnosticsStart = controller::stop
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {}

            assertEquals(
                listOf("diagnostics-stop", "user-stop", "diagnostics-start", "diagnostics-stop"),
                controller.operations,
            )
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `accepted stop captured with stale running status suppresses resume`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            controller.runtimeResumeIntentTracker.recordAcceptedStop()
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {}

            assertEquals(listOf("diagnostics-stop"), controller.operations)
            assertEquals(AppStatus.Halted to Mode.Proxy, stateStore.status.value)
        }

    @Test
    fun `newer user start suppresses stale stop compensation`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val coordinator =
                buildCoordinator(
                    controller,
                    stateStore,
                    FakeCoordinatorSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setDiagnosticsAutoResumeAfterRawScan(true)
                            .build(),
                    ),
                )

            coordinator.runRawPathScan {
                controller.stop()
                controller.start(Mode.Proxy)
            }

            assertEquals(listOf("diagnostics-stop", "user-stop", "user-start"), controller.operations)
            assertEquals(AppStatus.Running to Mode.Proxy, stateStore.status.value)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `automatic raw path cleanup waits for newer user start readiness`() =
        runTest {
            val stateStore = FakeCoordinatorStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore).apply { transitionOnStart = false }
            val settings = FakeCoordinatorSettingsRepository()
            val coordinator =
                DefaultDiagnosticsRuntimeCoordinator(
                    runtimeControlPlane =
                        DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
                    runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
                    serviceStateStore = stateStore,
                    appSettingsRepository = settings,
                    runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
                    serviceController = controller,
                    serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                    waitAttempts = 50,
                    waitDelayMs = 1,
                )

            val scan =
                async {
                    coordinator.runAutomaticRawPathScan {
                        controller.start(Mode.Proxy)
                    }
                }
            runCurrent()
            val beforeReady = scan.isCompleted to stateStore.status.value.first

            stateStore.setStatus(AppStatus.Running, Mode.Proxy)
            scan.await()

            assertEquals(
                (false to AppStatus.Halted) to (true to AppStatus.Running),
                beforeReady to (scan.isCompleted to stateStore.status.value.first),
            )
        }
}

private fun buildCoordinator(
    controller: FakeServiceController,
    stateStore: FakeCoordinatorStateStore,
    settings: AppSettingsRepository = FakeCoordinatorSettingsRepository(),
): DefaultDiagnosticsRuntimeCoordinator =
    DefaultDiagnosticsRuntimeCoordinator(
        runtimeControlPlane =
            DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore)),
        runtimeModeProjectionStore = RuntimeModeProjectionStore(stateStore, settings),
        serviceStateStore = stateStore,
        appSettingsRepository = settings,
        runtimeResumeIntentTracker = controller.runtimeResumeIntentTracker,
        serviceController = controller,
        serviceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
        waitAttempts = 2,
        waitDelayMs = 0,
    )

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
    val runtimeResumeIntentTracker = RuntimeResumeIntentTracker()
    var stopFailure: Throwable? = null
    var startFailure: Throwable? = null
    var transitionOnStop: Boolean = true
    var transitionOnStart: Boolean = true
    var stopCount: Int = 0
    var startCount: Int = 0
    var nextStartResult: ServiceStartResult? = null
    var beforeDiagnosticsStart: (() -> Unit)? = null
    var afterDiagnosticsStart: (() -> Unit)? = null
    val operations = mutableListOf<String>()

    override fun start(mode: Mode): ServiceStartResult =
        runtimeResumeIntentTracker.withUserStart(
            action = { start(mode, "user-start") },
            isAccepted = { it is ServiceStartResult.Accepted },
        )

    override fun startForDiagnostics(mode: Mode): ServiceStartResult {
        beforeDiagnosticsStart?.invoke()
        val result = start(mode, "diagnostics-start")
        afterDiagnosticsStart?.invoke()
        return result
    }

    private fun start(
        mode: Mode,
        operation: String,
    ): ServiceStartResult {
        operations += operation
        startCount += 1
        startFailure?.let { throw it }
        nextStartResult?.let { return it }
        if (transitionOnStart) {
            stateStore.setStatus(AppStatus.Running, mode)
        }
        return ServiceStartResult.Accepted(mode)
    }

    override fun stop() {
        runtimeResumeIntentTracker.recordAcceptedStop()
        stop("user-stop")
    }

    override fun stopForDiagnostics() {
        stop("diagnostics-stop")
    }

    private fun stop(operation: String) {
        operations += operation
        stopCount += 1
        stopFailure?.let { throw it }
        if (transitionOnStop) {
            stateStore.setStatus(AppStatus.Halted, stateStore.status.value.second)
        }
    }
}
