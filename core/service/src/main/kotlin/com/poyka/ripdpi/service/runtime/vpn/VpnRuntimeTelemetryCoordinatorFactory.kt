package com.poyka.ripdpi.service.runtime.vpn

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisor
import com.poyka.ripdpi.services.DirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.LocalProxyEndpoint
import com.poyka.ripdpi.services.ProxyRuntimeSupervisor
import com.poyka.ripdpi.services.RuntimeStopGuard
import com.poyka.ripdpi.services.ScreenStateObserver
import com.poyka.ripdpi.services.ServiceStatusReporter
import com.poyka.ripdpi.services.UpstreamRelaySupervisor
import com.poyka.ripdpi.services.VpnCoordinatorHost
import com.poyka.ripdpi.services.VpnProtectFailureMonitor
import com.poyka.ripdpi.services.VpnRuntimeSession
import com.poyka.ripdpi.services.VpnRuntimeTelemetryReporter
import com.poyka.ripdpi.services.VpnTelemetryCoordinator
import com.poyka.ripdpi.services.VpnTelemetryFailureCallbacks
import com.poyka.ripdpi.services.VpnTelemetryRuntimeDependencies
import com.poyka.ripdpi.services.VpnTelemetryStateAccess
import com.poyka.ripdpi.services.VpnTunnelRuntime
import com.poyka.ripdpi.services.WarpRuntimeSupervisor
import com.poyka.ripdpi.services.XrayProviderSessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex

internal data class VpnRuntimeTelemetryRuntimePorts(
    val host: VpnCoordinatorHost,
    val ioDispatcher: CoroutineDispatcher,
    val mutex: Mutex,
    val protectFailureMonitor: VpnProtectFailureMonitor,
    val tunnelRuntime: VpnTunnelRuntime,
    val xrayController: XrayProviderSessionController?,
)

internal data class VpnRuntimeTelemetrySupervisors(
    val upstreamRelay: UpstreamRelaySupervisor,
    val warp: WarpRuntimeSupervisor,
    val amneziaWg: AmneziaWgRuntimeSupervisor,
    val proxy: ProxyRuntimeSupervisor,
)

internal data class VpnRuntimeTelemetryReporterPorts(
    val statusReporter: ServiceStatusReporter,
    val screenStateObserver: ScreenStateObserver,
    val directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer,
)

internal data class VpnRuntimeTelemetryStateBindings(
    val currentStatus: () -> ServiceStatus,
    val isStopping: () -> Boolean,
    val currentSession: () -> VpnRuntimeSession?,
    val currentLocalProxyEndpoint: () -> LocalProxyEndpoint?,
    val currentNetworkHandoverState: () -> String?,
    val applyPendingNetworkHandoverClass: (NativeRuntimeSnapshot) -> NativeRuntimeSnapshot,
)

internal data class VpnRuntimeTelemetryActions(
    val updateStatus: suspend (ServiceStatus, FailureReason?) -> Unit,
    val failAndStopService: suspend (FailureReason, RuntimeStopGuard?, suspend () -> Unit) -> Boolean,
    val stopService: suspend (RuntimeStopGuard?) -> Boolean,
)

internal fun createVpnRuntimeTelemetryCoordinator(
    runtimePorts: VpnRuntimeTelemetryRuntimePorts,
    supervisors: VpnRuntimeTelemetrySupervisors,
    reporterPorts: VpnRuntimeTelemetryReporterPorts,
    stateBindings: VpnRuntimeTelemetryStateBindings,
    actions: VpnRuntimeTelemetryActions,
): VpnTelemetryCoordinator =
    VpnTelemetryCoordinator(
        dependencies =
            VpnRuntimeTelemetryDependencies(
                runtimePorts = runtimePorts,
                supervisors = supervisors,
                reporterPorts = reporterPorts,
            ),
        state = VpnRuntimeTelemetryState(stateBindings),
        callbacks = VpnRuntimeTelemetryCallbacks(actions),
    )

private class VpnRuntimeTelemetryDependencies(
    private val runtimePorts: VpnRuntimeTelemetryRuntimePorts,
    private val supervisors: VpnRuntimeTelemetrySupervisors,
    reporterPorts: VpnRuntimeTelemetryReporterPorts,
) : VpnTelemetryRuntimeDependencies {
    override val host: VpnCoordinatorHost = runtimePorts.host
    override val ioDispatcher: CoroutineDispatcher = runtimePorts.ioDispatcher
    override val mutex: Mutex = runtimePorts.mutex
    override val vpnProtectFailureMonitor: VpnProtectFailureMonitor = runtimePorts.protectFailureMonitor
    override val vpnTunnelRuntime: VpnTunnelRuntime = runtimePorts.tunnelRuntime
    override val upstreamRelaySupervisor: UpstreamRelaySupervisor = supervisors.upstreamRelay
    override val warpRuntimeSupervisor: WarpRuntimeSupervisor = supervisors.warp
    override val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor = supervisors.amneziaWg
    override val proxyRuntimeSupervisor: ProxyRuntimeSupervisor = supervisors.proxy
    override val screenStateObserver: ScreenStateObserver = reporterPorts.screenStateObserver
    override val xrayController: XrayProviderSessionController? = runtimePorts.xrayController
    override val telemetryReporter =
        VpnRuntimeTelemetryReporter(
            host = runtimePorts.host,
            statusReporter = reporterPorts.statusReporter,
            screenStateObserver = reporterPorts.screenStateObserver,
            directPathPolicyTelemetryConsumer = reporterPorts.directPathPolicyTelemetryConsumer,
            vpnTunnelRuntime = runtimePorts.tunnelRuntime,
            xrayController = runtimePorts.xrayController,
        )
}

private class VpnRuntimeTelemetryState(
    private val bindings: VpnRuntimeTelemetryStateBindings,
) : VpnTelemetryStateAccess {
    override fun status(): ServiceStatus = bindings.currentStatus()

    override fun stopping(): Boolean = bindings.isStopping()

    override fun runtimeSession(): VpnRuntimeSession? = bindings.currentSession()

    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? = bindings.currentLocalProxyEndpoint()

    override fun currentNetworkHandoverState(): String? = bindings.currentNetworkHandoverState()

    override fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot =
        bindings.applyPendingNetworkHandoverClass(snapshot)
}

private class VpnRuntimeTelemetryCallbacks(
    private val actions: VpnRuntimeTelemetryActions,
) : VpnTelemetryFailureCallbacks {
    override suspend fun updateStatus(
        status: ServiceStatus,
        failureReason: FailureReason?,
    ) = actions.updateStatus(status, failureReason)

    override suspend fun failAndStopService(
        failureReason: FailureReason,
        guard: RuntimeStopGuard?,
        beforeFailureStatus: suspend () -> Unit,
    ): Boolean = actions.failAndStopService(failureReason, guard, beforeFailureStatus)

    override suspend fun stopService(guard: RuntimeStopGuard?): Boolean = actions.stopService(guard)
}
