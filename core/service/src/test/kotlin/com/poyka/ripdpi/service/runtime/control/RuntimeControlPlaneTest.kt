package com.poyka.ripdpi.service.runtime.control

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the runtime control plane delegates each [RuntimeControlCommand] to the
 * same runtime operation a caller would otherwise invoke directly: the lifecycle
 * commands map onto [ServiceController] calls, and [DefaultRuntimeControlPlane]
 * routes every command to the matching [RuntimeControlActions] method.
 */
class RuntimeControlPlaneTest {
    @Test
    fun `start command issues a ServiceController start for the requested mode`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val plane = DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore))

            val outcome =
                plane.execute(
                    RuntimeControlCommand.StartRuntime(Mode.VPN, RuntimeControlReason.ServiceStart),
                )

            assertEquals(RuntimeControlOutcome.Completed, outcome)
            assertEquals(1, controller.startCount)
            assertEquals(Mode.VPN, controller.lastStartMode)
            assertEquals(0, controller.stopCount)
        }

    @Test
    fun `stop command issues a ServiceController stop`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val plane = DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore))

            val outcome = plane.execute(RuntimeControlCommand.StopRuntime(RuntimeControlReason.UserStop))

            assertEquals(RuntimeControlOutcome.Completed, outcome)
            assertEquals(1, controller.stopCount)
            assertEquals(0, controller.startCount)
        }

    @Test
    fun `start command returns rejected outcome when ServiceController rejects start`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN)
            val rejected =
                ServiceStartResult.Rejected(
                    mode = Mode.VPN,
                    reason = ServiceStartRejectionReason.VpnConsentMissing,
                )
            val controller =
                FakeServiceController(stateStore).apply {
                    nextStartResult = rejected
                }
            val plane = DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore))

            val outcome =
                plane.execute(
                    RuntimeControlCommand.StartRuntime(Mode.VPN, RuntimeControlReason.ServiceStart),
                )

            assertEquals(RuntimeControlOutcome.Rejected(mode = Mode.VPN, reason = rejected.reason), outcome)
            assertEquals(AppStatus.Halted to Mode.VPN, stateStore.status.value)
        }

    @Test
    fun `restart command stops the running runtime then starts the same mode`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val controller = FakeServiceController(stateStore)
            val plane = DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore))

            val outcome = plane.execute(RuntimeControlCommand.RestartRuntime(RuntimeControlReason.SettingsChange))

            assertEquals(RuntimeControlOutcome.Completed, outcome)
            assertEquals(1, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(Mode.Proxy, controller.lastStartMode)
        }

    @Test
    fun `restart command starts directly when the runtime is already halted`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val plane = DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore))

            plane.execute(RuntimeControlCommand.RestartRuntime(RuntimeControlReason.SettingsChange))

            assertEquals(0, controller.stopCount)
            assertEquals(1, controller.startCount)
            assertEquals(Mode.VPN, controller.lastStartMode)
        }

    @Test
    fun `session-scoped commands are ignored by the process-level control plane`() =
        runTest {
            val stateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController(stateStore)
            val plane = DefaultRuntimeControlPlane(ServiceControllerRuntimeControlActions(controller, stateStore))

            val sessionScoped =
                listOf(
                    RuntimeControlCommand.ApplyNetworkSnapshot(RuntimeControlReason.NetworkHandover),
                    RuntimeControlCommand.ApplyPolicyPatch(
                        RuntimeControlReason.PolicyMemoryUpdate,
                        RuntimePolicyPatch(summary = "test-patch"),
                    ),
                    RuntimeControlCommand.RefreshRelay(RuntimeControlReason.RelayProfileChange),
                    RuntimeControlCommand.RefreshDns(RuntimeControlReason.RootHelperAvailabilityChange),
                )

            sessionScoped.forEach { command ->
                assertTrue(
                    "expected $command to be Ignored",
                    plane.execute(command) is RuntimeControlOutcome.Ignored,
                )
            }
            assertEquals(0, controller.startCount)
            assertEquals(0, controller.stopCount)
        }

    @Test
    fun `every command dispatches to its matching actions method`() =
        runTest {
            val actions = RecordingRuntimeControlActions()
            val plane = DefaultRuntimeControlPlane(actions)

            plane.execute(RuntimeControlCommand.StartRuntime(Mode.VPN, RuntimeControlReason.ServiceStart))
            plane.execute(RuntimeControlCommand.StopRuntime(RuntimeControlReason.UserStop))
            plane.execute(RuntimeControlCommand.RestartRuntime(RuntimeControlReason.NetworkHandover))
            plane.execute(RuntimeControlCommand.ApplyNetworkSnapshot(RuntimeControlReason.NetworkHandover))
            plane.execute(
                RuntimeControlCommand.ApplyPolicyPatch(
                    RuntimeControlReason.PolicyMemoryUpdate,
                    RuntimePolicyPatch(summary = "test-patch"),
                ),
            )
            plane.execute(RuntimeControlCommand.RefreshRelay(RuntimeControlReason.RelayProfileChange))
            plane.execute(RuntimeControlCommand.RefreshDns(RuntimeControlReason.SettingsChange))

            assertEquals(
                listOf(
                    "startRuntime",
                    "stopRuntime",
                    "restartRuntime",
                    "applyNetworkSnapshot",
                    "applyPolicyPatch",
                    "refreshRelay",
                    "refreshDns",
                ),
                actions.invoked,
            )
        }
}

private class FakeServiceController(
    private val stateStore: FakeServiceStateStore,
) : ServiceController {
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var lastStartMode: Mode? = null
        private set
    var nextStartResult: ServiceStartResult? = null

    override fun start(mode: Mode): ServiceStartResult {
        startCount += 1
        lastStartMode = mode
        nextStartResult?.let { return it }
        stateStore.setStatus(AppStatus.Running, mode)
        return ServiceStartResult.Accepted(mode)
    }

    override fun stop() {
        stopCount += 1
        stateStore.setStatus(AppStatus.Halted, stateStore.status.value.second)
    }
}

private class FakeServiceStateStore(
    initial: Pair<AppStatus, Mode>,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initial)
    private val eventState = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventState.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

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

private class RecordingRuntimeControlActions : RuntimeControlActions {
    val invoked = mutableListOf<String>()

    override suspend fun startRuntime(
        mode: Mode,
        reason: RuntimeControlReason,
    ): RuntimeControlOutcome {
        invoked += "startRuntime"
        return RuntimeControlOutcome.Completed
    }

    override suspend fun stopRuntime(reason: RuntimeControlReason): RuntimeControlOutcome {
        invoked += "stopRuntime"
        return RuntimeControlOutcome.Completed
    }

    override suspend fun restartRuntime(reason: RuntimeControlReason): RuntimeControlOutcome {
        invoked += "restartRuntime"
        return RuntimeControlOutcome.Completed
    }

    override suspend fun applyNetworkSnapshot(reason: RuntimeControlReason): RuntimeControlOutcome {
        invoked += "applyNetworkSnapshot"
        return RuntimeControlOutcome.Completed
    }

    override suspend fun applyPolicyPatch(
        reason: RuntimeControlReason,
        patch: RuntimePolicyPatch,
    ): RuntimeControlOutcome {
        invoked += "applyPolicyPatch"
        return RuntimeControlOutcome.Completed
    }

    override suspend fun refreshRelay(reason: RuntimeControlReason): RuntimeControlOutcome {
        invoked += "refreshRelay"
        return RuntimeControlOutcome.Completed
    }

    override suspend fun refreshDns(reason: RuntimeControlReason): RuntimeControlOutcome {
        invoked += "refreshDns"
        return RuntimeControlOutcome.Completed
    }
}
