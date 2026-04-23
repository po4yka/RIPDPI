package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.toRuntimeException
import com.poyka.ripdpi.data.toStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@Suppress("TooManyFunctions", "LongParameterList")
internal class VpnServiceRuntimeCoordinator(
    vpnHost: VpnCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    private val resolverOverrideStore: ResolverOverrideStore,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    private val vpnProtectFailureMonitor: VpnProtectFailureMonitor,
    private val vpnTunnelRuntime: VpnTunnelRuntime,
    private val resolverRefreshPlanner: VpnResolverRefreshPlanner,
    private val encryptedDnsFailoverController: VpnEncryptedDnsFailoverController,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val statusReporter: ServiceStatusReporter,
    private val screenStateObserver: ScreenStateObserver,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: ServiceClock = SystemServiceClock,
) : BaseServiceRuntimeCoordinator<VpnRuntimeSession>(
        mode = Mode.VPN,
        host = vpnHost,
        connectionPolicyResolver = connectionPolicyResolver,
        serviceRuntimeRegistry = serviceRuntimeRegistry,
        rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
        networkHandoverMonitor = networkHandoverMonitor,
        policyHandoverEventStore = policyHandoverEventStore,
        permissionWatchdog = permissionWatchdog,
        ioDispatcher = ioDispatcher,
        clock = clock,
    ) {
    private companion object {
        private const val TelemetryPollIntervalMs = 1_000L
        private const val TelemetryPollIntervalBackgroundMs = 5_000L
    }

    private var currentLocalProxyEndpoint: LocalProxyEndpoint? = null
    private var vpnProtectFailureJob: Job? = null
    private val proxyRuntimeStack =
        SharedProxyRuntimeStack(
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
        )

    override val serviceLabel: String = "VPN"

    override fun createRuntimeSession(): VpnRuntimeSession = VpnRuntimeSession()

    override suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.VPN,
            resolverOverride = resolverOverrideStore.override.value,
        )

    override suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.VPN,
            resolverOverride = resolverOverrideStore.override.value,
            fingerprint = fingerprint,
            handoverClassification = handoverClassification,
        )

    override fun applyActiveConnectionPolicy(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        val policy =
            resolution.appliedPolicy ?: run {
                session.clearActiveConnectionPolicy()
                return
            }
        session.updateActiveConnectionPolicy(
            ActiveConnectionPolicy(
                mode = Mode.VPN,
                policy = policy,
                matchedPolicy = resolution.matchedNetworkPolicy,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                rememberedPolicyAppliedByExactMatch = resolution.rememberedPolicyAppliedByExactMatch,
                fingerprintHash = resolution.fingerprintHash,
                policySignature = resolution.policySignature,
                appliedAt = appliedAt,
                restartReason = restartReason,
                handoverClassification = resolution.handoverClassification,
            ),
        )
    }

    override suspend fun startResolvedRuntime(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        val logContext = session.buildLogContext(session.currentActiveConnectionPolicy)
        val authToken =
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
        val localProxyEndpoint =
            proxyRuntimeStack.start(
                proxyPreferences =
                    resolution
                        .proxyPreferences
                        .withLogContext(logContext)
                        .withSessionLocalProxyOverrides(listenPortOverride = 0, authToken = authToken),
                onRelayExit = ::handleRelayExit,
                onWarpExit = ::handleWarpExit,
                onProxyExit = ::handleProxyExit,
            )
        currentLocalProxyEndpoint = localProxyEndpoint
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
            localProxyEndpoint = localProxyEndpoint,
        )
        updateRuntimeDnsState(session, resolution)
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        vpnTunnelRuntime.stop()
        proxyRuntimeStack.stop(skipRuntimeShutdown)
    }

    override fun startModeTelemetryUpdates() {
        startVpnProtectFailureMonitoring()
        replaceTelemetryJob {
            while (status == ServiceStatus.Connected) {
                val session = runtimeSession ?: return@replaceTelemetryJob
                refreshVpnTunnelIfNeeded(session)
                val telemetry = pollCurrentTelemetry()
                if (maybeRecoverEncryptedDns(session, telemetry)) {
                    refreshVpnTunnelIfNeeded(session)
                }
                if (handleTelemetryFailure(telemetry)) return@replaceTelemetryJob
                reportTelemetry(telemetry)
                val interval =
                    if (screenStateObserver.isInteractive.value) {
                        TelemetryPollIntervalMs
                    } else {
                        TelemetryPollIntervalBackgroundMs
                    }
                delay(interval)
            }
        }
    }

    private fun startVpnProtectFailureMonitoring() {
        vpnProtectFailureJob?.cancel()
        vpnProtectFailureJob =
            host.serviceScope.launch(ioDispatcher) {
                vpnProtectFailureMonitor.events.collect { event ->
                    handleVpnProtectFailure(event)
                }
            }
    }

    private suspend fun handleVpnProtectFailure(event: VpnProtectFailureEvent) {
        if (status != ServiceStatus.Connected || stopping) {
            return
        }
        Logger.e { "VPN protect failed for fd=${event.fd}: ${event.detail}" }
        updateStatus(ServiceStatus.Failed, event.reason)
        stop()
    }

    private suspend fun refreshVpnTunnelIfNeeded(session: VpnRuntimeSession) {
        val refreshPlan =
            resolverRefreshPlanner.plan(
                currentSignature = vpnTunnelRuntime.currentDnsSignature,
                tunnelRunning = vpnTunnelRuntime.isRunning,
            )
        if (!refreshPlan.requiresTunnelRebuild) return
        mutex.withLock {
            val activeSession = runtimeSession
            val canRefresh =
                status == ServiceStatus.Connected &&
                    vpnTunnelRuntime.isRunning &&
                    activeSession?.runtimeId == session.runtimeId
            if (!canRefresh) return@withLock
            val refreshSession = checkNotNull(activeSession)
            val latestRefreshPlan =
                resolverRefreshPlanner.plan(
                    currentSignature = vpnTunnelRuntime.currentDnsSignature,
                    tunnelRunning = vpnTunnelRuntime.isRunning,
                )
            if (!latestRefreshPlan.requiresTunnelRebuild) return@withLock
            val latestResolution =
                checkNotNull(latestRefreshPlan.connectionPolicy) {
                    "VPN resolver refresh plan missing connection policy"
                }
            vpnTunnelRuntime.stop()
            vpnTunnelRuntime.start(
                activeDns = latestResolution.activeDns,
                overrideReason = latestResolution.resolverFallbackReason,
                logContext = refreshSession.buildLogContext(refreshSession.currentActiveConnectionPolicy),
                localProxyEndpoint =
                    checkNotNull(currentLocalProxyEndpoint) {
                        "VPN tunnel refresh requires an active local proxy endpoint"
                    },
            )
            updateRuntimeDnsState(refreshSession, latestResolution)
        }
    }

    private suspend fun pollCurrentTelemetry(): VpnTelemetrySnapshot {
        val proxyTelemetryOutcome = proxyRuntimeSupervisor.pollTelemetry()
        val relayTelemetryOutcome = upstreamRelaySupervisor.pollTelemetry()
        val warpTelemetryOutcome = warpRuntimeSupervisor.pollTelemetry()
        val tunnelTelemetryOutcome = vpnTunnelRuntime.pollTelemetry()
        val proxyTelemetry = proxyTelemetryOutcome.snapshotOrIdle(source = "proxy")
        val relayTelemetry = relayTelemetryOutcome.snapshotOrIdle(source = "relay")
        val warpTelemetry = warpTelemetryOutcome.snapshotOrIdle(source = "warp")
        val tunnelTelemetry = tunnelTelemetryOutcome.snapshotOrIdle(source = "tunnel")
        return VpnTelemetrySnapshot(
            proxyTelemetry = proxyTelemetry,
            proxyTelemetryStatus = proxyTelemetryOutcome.toStatus(),
            relayTelemetry = relayTelemetry,
            relayTelemetryStatus = relayTelemetryOutcome.toStatus(),
            warpTelemetry = warpTelemetry,
            warpTelemetryStatus = warpTelemetryOutcome.toStatus(),
            tunnelTelemetry = applyPendingNetworkHandoverClass(tunnelTelemetry),
            tunnelTelemetryStatus = tunnelTelemetryOutcome.toStatus(),
        )
    }

    private suspend fun handleTelemetryFailure(telemetry: VpnTelemetrySnapshot): Boolean {
        val telemetryFailure = telemetry.failureReason()
        val tunnelStoppedUnexpectedly =
            vpnTunnelRuntime.isRunning && telemetry.tunnelTelemetry.state != "running"

        // Don't halt if DNS failover still has candidates to try — give the
        // failover controller a chance to switch resolvers and rebuild the tunnel.
        val session = runtimeSession
        val dnsFailoverPending =
            session != null &&
                !session.encryptedDnsFailoverState.exhausted &&
                session.currentDns?.isEncrypted == true

        val shouldStop =
            !stopping &&
                (telemetryFailure != null || tunnelStoppedUnexpectedly) &&
                !dnsFailoverPending
        if (!shouldStop) {
            return false
        }
        val failureReason =
            telemetryFailure ?: FailureReason.NativeError(
                telemetry.tunnelTelemetry.lastError ?: "Tunnel exited unexpectedly",
            )
        statusReporter.reportTelemetry(
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = currentNetworkHandoverState,
            proxyTelemetry = telemetry.proxyTelemetry,
            relayTelemetry = telemetry.relayTelemetry,
            warpTelemetry = telemetry.warpTelemetry,
            tunnelTelemetry = telemetry.tunnelTelemetry,
            proxyTelemetryStatus = telemetry.proxyTelemetryStatus,
            relayTelemetryStatus = telemetry.relayTelemetryStatus,
            warpTelemetryStatus = telemetry.warpTelemetryStatus,
            tunnelTelemetryStatus = telemetry.tunnelTelemetryStatus,
            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
            failureReason = failureReason,
        )
        updateStatus(ServiceStatus.Failed, failureReason)
        host.serviceScope.launch(ioDispatcher) { stop() }
        return true
    }

    private suspend fun maybeRecoverEncryptedDns(
        session: VpnRuntimeSession,
        telemetry: VpnTelemetrySnapshot,
    ): Boolean =
        encryptedDnsFailoverController.evaluate(
            state = session.encryptedDnsFailoverState,
            activeDns = session.currentDns,
            currentDnsSignature = vpnTunnelRuntime.currentDnsSignature ?: session.currentDnsSignature,
            networkScopeKey = session.currentNetworkScopeKey,
            telemetry = telemetry.tunnelTelemetry,
        )

    private fun reportTelemetry(telemetry: VpnTelemetrySnapshot) {
        statusReporter.reportTelemetry(
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = currentNetworkHandoverState,
            proxyTelemetry = telemetry.proxyTelemetry,
            relayTelemetry = telemetry.relayTelemetry,
            warpTelemetry = telemetry.warpTelemetry,
            tunnelTelemetry = telemetry.tunnelTelemetry,
            proxyTelemetryStatus = telemetry.proxyTelemetryStatus,
            relayTelemetryStatus = telemetry.relayTelemetryStatus,
            warpTelemetryStatus = telemetry.warpTelemetryStatus,
            tunnelTelemetryStatus = telemetry.tunnelTelemetryStatus,
            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
        )
        if (statusReporter.startedAt != null && screenStateObserver.isInteractive.value) {
            host.updateNotification(
                tunnelStats = telemetry.tunnelTelemetry.tunnelStats,
                proxyTelemetry = telemetry.proxyTelemetry,
            )
        }
    }

    override suspend fun restartAfterHandover(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        session.currentDns = null
        session.currentDnsSignature = null
        session.currentNetworkScopeKey = null
        session.encryptedDnsFailoverState.resetAll()
        vpnTunnelRuntime.stop()
        proxyRuntimeStack.stop(skipRuntimeShutdown = false)
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
        val logContext = session.buildLogContext(session.currentActiveConnectionPolicy)
        val authToken =
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
        val localProxyEndpoint =
            proxyRuntimeStack.start(
                proxyPreferences =
                    resolution
                        .proxyPreferences
                        .withLogContext(logContext)
                        .withSessionLocalProxyOverrides(listenPortOverride = 0, authToken = authToken),
                onRelayExit = ::handleRelayExit,
                onWarpExit = ::handleWarpExit,
                onProxyExit = ::handleProxyExit,
            )
        currentLocalProxyEndpoint = localProxyEndpoint
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
            localProxyEndpoint = localProxyEndpoint,
        )
        updateRuntimeDnsState(session, resolution)
    }

    override fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        Logger.d { "VPN status: $status -> $newStatus" }
        status = newStatus
        statusReporter.reportStatus(
            newStatus = newStatus,
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
            currentNetworkHandoverState = currentNetworkHandoverState,
            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
            failureReason = failureReason,
        )
    }

    override fun classifyStartupFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    override fun classifyHandoverFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    override fun onPermissionRevoked(event: PermissionChangeEvent) {
        when (event.kind) {
            PermissionChangeEvent.KIND_VPN_CONSENT -> {
                Logger.e { "VPN consent revoked while running" }
                updateStatus(ServiceStatus.Failed, FailureReason.PermissionLost("VPN"))
                host.serviceScope.launch(ioDispatcher) { stop() }
            }

            PermissionChangeEvent.KIND_NOTIFICATIONS -> {
                Logger.i { "Notification permission revoked while VPN running" }
            }
        }
    }

    override fun onAfterStopCleanup(session: VpnRuntimeSession?) {
        vpnProtectFailureJob?.cancel()
        vpnProtectFailureJob = null
        resolverOverrideStore.clear()
        vpnTunnelRuntime.resetRuntimeState()
        currentLocalProxyEndpoint = null
        session?.encryptedDnsFailoverState?.resetAll()
    }

    private fun updateRuntimeDnsState(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        session.currentDns = resolution.activeDns
        session.currentDnsSignature = dnsSignature(resolution.activeDns, resolution.resolverFallbackReason)
        session.currentNetworkScopeKey = resolution.networkScopeKey
    }

    private suspend fun handleProxyExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "Proxy stopped with code ${cause.code}" }
                    FailureReason.NativeError("Proxy exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "Proxy failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "Proxy runtime was cancelled unexpectedly" }
                    FailureReason.NativeError("Proxy runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        proxyRuntimeStack.detachAll()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private suspend fun handleWarpExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "WARP stopped with code ${cause.code}" }
                    FailureReason.WarpRuntimeFailed("WARP exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "WARP failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "WARP runtime was cancelled unexpectedly" }
                    FailureReason.WarpRuntimeFailed("WARP runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        warpRuntimeSupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private suspend fun handleRelayExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "Relay stopped with code ${cause.code}" }
                    FailureReason.NativeError("Relay exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "Relay failed" }
                    classifyFailureReason(error, isTunnelContext = true)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "Relay runtime was cancelled unexpectedly" }
                    FailureReason.NativeError("Relay runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        upstreamRelaySupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }
}

@Suppress("LongParameterList")
internal class VpnServiceRuntimeRuntimeDependencies
    @Inject
    constructor(
        val appSettingsRepository: AppSettingsRepository,
        val connectionPolicyResolver: ConnectionPolicyResolver,
        val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
        val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
        val resolverOverrideStore: ResolverOverrideStore,
        val serviceRuntimeRegistry: ServiceRuntimeRegistry,
        val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        val networkHandoverMonitor: NetworkHandoverMonitor,
        val policyHandoverEventStore: PolicyHandoverEventStore,
        val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        val dnsDependencies: VpnServiceRuntimeDnsDependencies,
        val upstreamRelaySupervisorFactory: UpstreamRelaySupervisorFactory,
        val warpRuntimeSupervisorFactory: WarpRuntimeSupervisorFactory,
        val proxyRuntimeSupervisorFactory: ProxyRuntimeSupervisorFactory,
        val screenStateObserver: ScreenStateObserver,
    )

internal class VpnServiceRuntimeDnsDependencies
    @Inject
    constructor(
        val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        val networkDnsBlockedPathStore: NetworkDnsBlockedPathStore,
        val resolverRefreshPlanner: VpnResolverRefreshPlanner,
    )

internal class VpnServiceRuntimeStatusDependencies
    @Inject
    constructor(
        val serviceStateStore: ServiceStateStore,
        val networkFingerprintProvider: NetworkFingerprintProvider,
        val telemetryFingerprintHasher: TelemetryFingerprintHasher,
        val serviceStatusReporterFactory: ServiceStatusReporterFactory,
    )

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
