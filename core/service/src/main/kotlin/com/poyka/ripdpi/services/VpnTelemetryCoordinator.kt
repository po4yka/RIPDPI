package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.toStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

internal interface VpnTelemetryRuntimeDependencies {
    val host: VpnCoordinatorHost
    val ioDispatcher: CoroutineDispatcher
    val mutex: Mutex
    val vpnProtectFailureMonitor: VpnProtectFailureMonitor
    val vpnTunnelRuntime: VpnTunnelRuntime
    val upstreamRelaySupervisor: UpstreamRelaySupervisor
    val warpRuntimeSupervisor: WarpRuntimeSupervisor
    val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor
    val proxyRuntimeSupervisor: ProxyRuntimeSupervisor
    val screenStateObserver: ScreenStateObserver
    val telemetryReporter: VpnRuntimeTelemetryReporter
    val xrayController: XrayProviderSessionController?
}

internal interface VpnTelemetryStateAccess {
    fun status(): ServiceStatus

    fun stopping(): Boolean

    fun runtimeSession(): VpnRuntimeSession?

    fun currentLocalProxyEndpoint(): LocalProxyEndpoint?

    fun currentNetworkHandoverState(): String?

    fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot
}

internal interface VpnTelemetryFailureCallbacks {
    fun updateStatus(
        status: ServiceStatus,
        failureReason: FailureReason?,
    )

    suspend fun stopService(guard: RuntimeStopGuard? = null)
}

internal class VpnTelemetryCoordinator(
    private val dependencies: VpnTelemetryRuntimeDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTelemetryFailureCallbacks,
) {
    private companion object {
        private const val TelemetryPollIntervalMs = 1_000L
        private const val TelemetryPollIntervalBackgroundMs = 5_000L
    }

    private val protectFailureWatcher =
        VpnProtectFailureWatcher(
            dependencies = dependencies,
            state = state,
            callbacks = callbacks,
        )
    private val failureHandler =
        VpnTelemetryFailureHandler(
            dependencies = dependencies,
            state = state,
            callbacks = callbacks,
        )
    private val activeEvidenceCollector = AtomicReference<DataPlaneEvidenceCollector?>()

    fun start(
        tunnelRefreshCoordinator: VpnTunnelRefreshCoordinator,
        replaceTelemetryJob: ((suspend CoroutineScope.() -> Unit) -> Unit),
    ) {
        protectFailureWatcher.start()
        val evidenceCollector = newEvidenceCollector()
        activeEvidenceCollector.set(evidenceCollector)
        replaceTelemetryJob {
            launch { observeBuilderAffectingSettings(tunnelRefreshCoordinator) }
            while (state.status() == ServiceStatus.Connected) {
                val session = state.runtimeSession() ?: return@replaceTelemetryJob
                tunnelRefreshCoordinator.refreshIfNeeded(session)
                if (state.status() != ServiceStatus.Connected) return@replaceTelemetryJob
                val telemetry = pollCurrentTelemetry(evidenceCollector)
                if (activeEvidenceCollector.get() !== evidenceCollector) return@replaceTelemetryJob
                if (tunnelRefreshCoordinator.recoverIfNeeded(session, telemetry)) {
                    tunnelRefreshCoordinator.refreshIfNeeded(session)
                    if (state.status() != ServiceStatus.Connected) return@replaceTelemetryJob
                }
                if (failureHandler.handle(telemetry)) return@replaceTelemetryJob
                dependencies.telemetryReporter.report(telemetry, state)
                delay(nextTelemetryPollInterval())
            }
        }
    }

    fun stopProtectFailureMonitoring() {
        protectFailureWatcher.stop()
    }

    suspend fun captureFinalTelemetry() =
        captureFinalDataPlaneEvidence(
            activeCollector = activeEvidenceCollector,
            capture = { evidenceCollector -> pollCurrentTelemetry(evidenceCollector, finalCapture = true) },
            publish = { telemetry -> dependencies.telemetryReporter.report(telemetry, state) },
        )

    private fun newEvidenceCollector(): DataPlaneEvidenceCollector =
        DataPlaneEvidenceCollector(
            mode = Mode.VPN,
            proxyEvidenceProvider = dependencies.proxyRuntimeSupervisor::pollForwardingEvidence,
            tunEvidenceProvider = dependencies.vpnTunnelRuntime::pollForwardingEvidence,
        )

    private suspend fun pollCurrentTelemetry(
        evidenceCollector: DataPlaneEvidenceCollector,
        finalCapture: Boolean = false,
    ): VpnTelemetrySnapshot {
        val routeForwardingEvidenceSink = dependencies.vpnTunnelRuntime.routeForwardingEvidenceSink
        val proxyPoll = dependencies.proxyRuntimeSupervisor.pollTelemetryAndForwardingEvidence()
        val proxyTelemetryOutcome = proxyPoll.telemetry
        val relayTelemetryOutcome = dependencies.upstreamRelaySupervisor.pollTelemetry()
        val warpTelemetryOutcome = dependencies.warpRuntimeSupervisor.pollTelemetry()
        val awgTelemetryOutcome = dependencies.amneziaWgRuntimeSupervisor.pollTelemetry()
        val tunnelPoll = dependencies.vpnTunnelRuntime.pollTelemetryAndForwardingEvidence()
        val tunnelTelemetryOutcome = tunnelPoll.telemetry
        val tunnelTelemetry = tunnelTelemetryOutcome.snapshotOrIdle(source = "tunnel")
        val snapshot =
            VpnTelemetrySnapshot(
                proxyTelemetry = proxyTelemetryOutcome.snapshotOrIdle(source = "proxy"),
                proxyTelemetryStatus = proxyTelemetryOutcome.toStatus(),
                relayTelemetry = relayTelemetryOutcome.snapshotOrIdle(source = "relay"),
                relayTelemetryStatus = relayTelemetryOutcome.toStatus(),
                warpTelemetry = warpTelemetryOutcome.snapshotOrIdle(source = "warp"),
                warpTelemetryStatus = warpTelemetryOutcome.toStatus(),
                awgTelemetry = awgTelemetryOutcome.snapshotOrIdle(source = "amneziawg"),
                awgTelemetryStatus = awgTelemetryOutcome.toStatus(),
                tunnelTelemetry = state.applyPendingNetworkHandoverClass(tunnelTelemetry),
                tunnelTelemetryStatus = tunnelTelemetryOutcome.toStatus(),
            )
        val evidenceResult =
            if (finalCapture) {
                evidenceCollector.finalizeAndEnrichWithOutcome(
                    snapshot,
                    proxyPoll.forwardingEvidence,
                    tunnelPoll.forwardingEvidence,
                    routeForwardingEvidenceSink?.generation,
                )
            } else {
                evidenceCollector.enrichWithOutcome(
                    snapshot,
                    proxyPoll.forwardingEvidence,
                    tunnelPoll.forwardingEvidence,
                    routeForwardingEvidenceSink?.generation,
                )
            }
        val forwardingOutcome = evidenceResult.outcome
        routeForwardingEvidenceSink?.record(
            outcome = forwardingOutcome.wireValue,
            terminal = forwardingOutcome.terminalFailure,
            revision = forwardingOutcome.revision,
        )
        return evidenceResult.snapshot
    }

    private fun nextTelemetryPollInterval(): Long =
        if (dependencies.screenStateObserver.isInteractive.value) {
            TelemetryPollIntervalMs
        } else {
            TelemetryPollIntervalBackgroundMs
        }

    private suspend fun observeBuilderAffectingSettings(tunnelRefreshCoordinator: VpnTunnelRefreshCoordinator) {
        dependencies.vpnTunnelRuntime.desiredInterfacePolicySignatures().collect {
            if (state.status() != ServiceStatus.Connected) return@collect
            val session = state.runtimeSession() ?: return@collect
            tunnelRefreshCoordinator.refreshIfNeeded(session, interfacePolicyChangeObserved = true)
        }
    }
}
