package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.toRuntimeException
import com.poyka.ripdpi.data.toStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface VpnTelemetryRuntimeDependencies {
    val host: VpnCoordinatorHost
    val ioDispatcher: CoroutineDispatcher
    val mutex: Mutex
    val vpnProtectFailureMonitor: VpnProtectFailureMonitor
    val vpnTunnelRuntime: VpnTunnelRuntime
    val dnsPolicyCoordinator: VpnDnsPolicyCoordinator
    val upstreamRelaySupervisor: UpstreamRelaySupervisor
    val warpRuntimeSupervisor: WarpRuntimeSupervisor
    val proxyRuntimeSupervisor: ProxyRuntimeSupervisor
    val statusReporter: ServiceStatusReporter
    val screenStateObserver: ScreenStateObserver
    val directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer
}

internal interface VpnTelemetryStateAccess {
    fun status(): ServiceStatus

    fun stopping(): Boolean

    fun runtimeSession(): VpnRuntimeSession?

    fun currentLocalProxyEndpoint(): LocalProxyEndpoint?

    fun currentNetworkHandoverState(): String?

    fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot
}

internal interface VpnTelemetryCallbacks {
    fun updateRuntimeDnsState(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    )

    fun updateStatus(
        status: ServiceStatus,
        failureReason: FailureReason?,
    )

    suspend fun stopService()
}

internal class VpnTelemetryCoordinator(
    private val dependencies: VpnTelemetryRuntimeDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTelemetryCallbacks,
) {
    private companion object {
        private const val TelemetryPollIntervalMs = 1_000L
        private const val TelemetryPollIntervalBackgroundMs = 5_000L
    }

    private var vpnProtectFailureJob: Job? = null

    fun start(replaceTelemetryJob: ((suspend CoroutineScope.() -> Unit) -> Unit)) {
        startVpnProtectFailureMonitoring()
        replaceTelemetryJob {
            while (state.status() == ServiceStatus.Connected) {
                val session = state.runtimeSession() ?: return@replaceTelemetryJob
                refreshVpnTunnelIfNeeded(session)
                val telemetry = pollCurrentTelemetry()
                if (maybeRecoverEncryptedDns(session, telemetry)) {
                    refreshVpnTunnelIfNeeded(session)
                }
                if (handleTelemetryFailure(telemetry)) return@replaceTelemetryJob
                reportTelemetry(telemetry)
                delay(nextTelemetryPollInterval())
            }
        }
    }

    fun stopProtectFailureMonitoring() {
        vpnProtectFailureJob?.cancel()
        vpnProtectFailureJob = null
    }

    private fun startVpnProtectFailureMonitoring() {
        stopProtectFailureMonitoring()
        vpnProtectFailureJob =
            dependencies.host.serviceScope.launch(dependencies.ioDispatcher) {
                dependencies.vpnProtectFailureMonitor.events.collect { event ->
                    handleVpnProtectFailure(event)
                }
            }
    }

    private suspend fun handleVpnProtectFailure(event: VpnProtectFailureEvent) {
        if (state.status() != ServiceStatus.Connected || state.stopping()) {
            return
        }
        Logger.e { "VPN protect failed for fd=${event.fd}: ${event.detail}" }
        callbacks.updateStatus(ServiceStatus.Failed, event.reason)
        callbacks.stopService()
    }

    private suspend fun refreshVpnTunnelIfNeeded(session: VpnRuntimeSession) {
        val refreshPlan =
            dependencies.dnsPolicyCoordinator.planRefresh(
                currentSignature = dependencies.vpnTunnelRuntime.currentDnsSignature,
                tunnelRunning = dependencies.vpnTunnelRuntime.isRunning,
            )
        if (!refreshPlan.requiresTunnelRebuild) return
        dependencies.mutex.withLock {
            val activeSession = state.runtimeSession()
            val canRefresh =
                state.status() == ServiceStatus.Connected &&
                    dependencies.vpnTunnelRuntime.isRunning &&
                    activeSession?.runtimeId == session.runtimeId
            if (!canRefresh) return@withLock
            val refreshSession = checkNotNull(activeSession)
            val latestRefreshPlan =
                dependencies.dnsPolicyCoordinator.planRefresh(
                    currentSignature = dependencies.vpnTunnelRuntime.currentDnsSignature,
                    tunnelRunning = dependencies.vpnTunnelRuntime.isRunning,
                )
            if (!latestRefreshPlan.requiresTunnelRebuild) return@withLock
            val latestResolution =
                checkNotNull(latestRefreshPlan.connectionPolicy) {
                    "VPN resolver refresh plan missing connection policy"
                }
            dependencies.vpnTunnelRuntime.stop()
            dependencies.vpnTunnelRuntime.start(
                activeDns = latestResolution.activeDns,
                overrideReason = latestResolution.resolverFallbackReason,
                logContext = refreshSession.buildLogContext(refreshSession.currentActiveConnectionPolicy),
                localProxyEndpoint =
                    checkNotNull(state.currentLocalProxyEndpoint()) {
                        "VPN tunnel refresh requires an active local proxy endpoint"
                    },
            )
            callbacks.updateRuntimeDnsState(refreshSession, latestResolution)
        }
    }

    private suspend fun pollCurrentTelemetry(): VpnTelemetrySnapshot {
        val proxyTelemetryOutcome = dependencies.proxyRuntimeSupervisor.pollTelemetry()
        val relayTelemetryOutcome = dependencies.upstreamRelaySupervisor.pollTelemetry()
        val warpTelemetryOutcome = dependencies.warpRuntimeSupervisor.pollTelemetry()
        val tunnelTelemetryOutcome = dependencies.vpnTunnelRuntime.pollTelemetry()
        val tunnelTelemetry = tunnelTelemetryOutcome.snapshotOrIdle(source = "tunnel")
        return VpnTelemetrySnapshot(
            proxyTelemetry = proxyTelemetryOutcome.snapshotOrIdle(source = "proxy"),
            proxyTelemetryStatus = proxyTelemetryOutcome.toStatus(),
            relayTelemetry = relayTelemetryOutcome.snapshotOrIdle(source = "relay"),
            relayTelemetryStatus = relayTelemetryOutcome.toStatus(),
            warpTelemetry = warpTelemetryOutcome.snapshotOrIdle(source = "warp"),
            warpTelemetryStatus = warpTelemetryOutcome.toStatus(),
            tunnelTelemetry = state.applyPendingNetworkHandoverClass(tunnelTelemetry),
            tunnelTelemetryStatus = tunnelTelemetryOutcome.toStatus(),
        )
    }

    private suspend fun handleTelemetryFailure(telemetry: VpnTelemetrySnapshot): Boolean {
        val telemetryFailure = telemetry.failureReason()
        val tunnelStoppedUnexpectedly =
            dependencies.vpnTunnelRuntime.isRunning && telemetry.tunnelTelemetry.state != "running"
        val session = state.runtimeSession()
        val dnsFailoverPending =
            session != null &&
                !session.encryptedDnsFailoverState.exhausted &&
                session.currentDns?.isEncrypted == true

        val shouldStop =
            !state.stopping() &&
                (telemetryFailure != null || tunnelStoppedUnexpectedly) &&
                !dnsFailoverPending
        if (!shouldStop) {
            return false
        }
        val failureReason =
            telemetryFailure ?: FailureReason.NativeError(
                telemetry.tunnelTelemetry.lastError ?: "Tunnel exited unexpectedly",
            )
        dependencies.directPathPolicyTelemetryConsumer.consume(telemetry.proxyTelemetry)
        dependencies.statusReporter.reportTelemetry(
            activePolicy = state.runtimeSession()?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { state.currentNetworkHandoverState() },
            proxyTelemetry = telemetry.proxyTelemetry,
            relayTelemetry = telemetry.relayTelemetry,
            warpTelemetry = telemetry.warpTelemetry,
            tunnelTelemetry = telemetry.tunnelTelemetry,
            proxyTelemetryStatus = telemetry.proxyTelemetryStatus,
            relayTelemetryStatus = telemetry.relayTelemetryStatus,
            warpTelemetryStatus = telemetry.warpTelemetryStatus,
            tunnelTelemetryStatus = telemetry.tunnelTelemetryStatus,
            tunnelRecoveryRetryCount = dependencies.vpnTunnelRuntime.tunnelRecoveryRetryCount,
            failureReason = failureReason,
        )
        callbacks.updateStatus(ServiceStatus.Failed, failureReason)
        dependencies.host.serviceScope.launch(dependencies.ioDispatcher) { callbacks.stopService() }
        return true
    }

    private suspend fun maybeRecoverEncryptedDns(
        session: VpnRuntimeSession,
        telemetry: VpnTelemetrySnapshot,
    ): Boolean =
        dependencies.dnsPolicyCoordinator.maybeRecoverEncryptedDns(
            session = session,
            currentDnsSignature = dependencies.vpnTunnelRuntime.currentDnsSignature ?: session.currentDnsSignature,
            telemetry = telemetry.tunnelTelemetry,
        )

    private suspend fun reportTelemetry(telemetry: VpnTelemetrySnapshot) {
        dependencies.directPathPolicyTelemetryConsumer.consume(telemetry.proxyTelemetry)
        dependencies.statusReporter.reportTelemetry(
            activePolicy = state.runtimeSession()?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { state.currentNetworkHandoverState() },
            proxyTelemetry = telemetry.proxyTelemetry,
            relayTelemetry = telemetry.relayTelemetry,
            warpTelemetry = telemetry.warpTelemetry,
            tunnelTelemetry = telemetry.tunnelTelemetry,
            proxyTelemetryStatus = telemetry.proxyTelemetryStatus,
            relayTelemetryStatus = telemetry.relayTelemetryStatus,
            warpTelemetryStatus = telemetry.warpTelemetryStatus,
            tunnelTelemetryStatus = telemetry.tunnelTelemetryStatus,
            tunnelRecoveryRetryCount = dependencies.vpnTunnelRuntime.tunnelRecoveryRetryCount,
        )
        if (dependencies.statusReporter.startedAt != null && dependencies.screenStateObserver.isInteractive.value) {
            dependencies.host.updateNotification(
                tunnelStats = telemetry.tunnelTelemetry.tunnelStats,
                proxyTelemetry = telemetry.proxyTelemetry,
            )
        }
    }

    private fun nextTelemetryPollInterval(): Long =
        if (dependencies.screenStateObserver.isInteractive.value) {
            TelemetryPollIntervalMs
        } else {
            TelemetryPollIntervalBackgroundMs
        }
}

private data class VpnTelemetrySnapshot(
    val proxyTelemetry: NativeRuntimeSnapshot,
    val proxyTelemetryStatus: RuntimeTelemetryStatus,
    val relayTelemetry: NativeRuntimeSnapshot,
    val relayTelemetryStatus: RuntimeTelemetryStatus,
    val warpTelemetry: NativeRuntimeSnapshot,
    val warpTelemetryStatus: RuntimeTelemetryStatus,
    val tunnelTelemetry: NativeRuntimeSnapshot,
    val tunnelTelemetryStatus: RuntimeTelemetryStatus,
) {
    fun failureReason(): FailureReason? =
        if (tunnelTelemetryStatus.state == RuntimeTelemetryState.EngineError) {
            classifyFailureReason(tunnelTelemetryStatus.toRuntimeException(), isTunnelContext = true)
        } else {
            null
        }
}

private fun RuntimeTelemetryOutcome.snapshotOrIdle(source: String): NativeRuntimeSnapshot =
    when (this) {
        is RuntimeTelemetryOutcome.Snapshot -> snapshot

        RuntimeTelemetryOutcome.NoData,
        is RuntimeTelemetryOutcome.EngineError,
        -> NativeRuntimeSnapshot.idle(source = source)
    }
