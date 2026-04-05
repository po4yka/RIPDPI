package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.warpConfigOrNull
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

internal class VpnServiceRuntimeCoordinator(
    vpnHost: VpnCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    private val resolverOverrideStore: ResolverOverrideStore,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    private val vpnTunnelRuntime: VpnTunnelRuntime,
    private val resolverRefreshPlanner: VpnResolverRefreshPlanner,
    private val encryptedDnsFailoverController: VpnEncryptedDnsFailoverController,
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
        resolution.proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
            warpRuntimeSupervisor.start(warpConfig, ::handleWarpExit)
        }
        proxyRuntimeSupervisor.start(
            resolution.proxyPreferences.withLogContext(logContext),
            ::handleProxyExit,
        )
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
        )
        updateRuntimeDnsState(session, resolution)
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        vpnTunnelRuntime.stop()
        if (skipRuntimeShutdown) {
            warpRuntimeSupervisor.detach()
            proxyRuntimeSupervisor.detach()
        } else {
            proxyRuntimeSupervisor.stop()
            warpRuntimeSupervisor.stop()
        }
    }

    override fun startModeTelemetryUpdates() {
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
            )
            updateRuntimeDnsState(refreshSession, latestResolution)
        }
    }

    private suspend fun pollCurrentTelemetry(): VpnTelemetrySnapshot {
        val proxyTelemetry = proxyRuntimeSupervisor.pollTelemetry() ?: NativeRuntimeSnapshot.idle(source = "proxy")
        val warpTelemetry = warpRuntimeSupervisor.pollTelemetry() ?: NativeRuntimeSnapshot.idle(source = "warp")
        val tunnelTelemetryResult = vpnTunnelRuntime.pollTelemetry()
        val tunnelTelemetry =
            tunnelTelemetryResult.getOrNull() ?: NativeRuntimeSnapshot.idle(source = "tunnel")
        return VpnTelemetrySnapshot(
            proxyTelemetry = proxyTelemetry,
            warpTelemetry = warpTelemetry,
            tunnelTelemetry = applyPendingNetworkHandoverClass(tunnelTelemetry),
            tunnelTelemetryResult = tunnelTelemetryResult,
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
            proxyTelemetry = telemetry.proxyTelemetry,
            warpTelemetry = telemetry.warpTelemetry,
            tunnelTelemetry = telemetry.tunnelTelemetry,
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
            proxyTelemetry = telemetry.proxyTelemetry,
            warpTelemetry = telemetry.warpTelemetry,
            tunnelTelemetry = telemetry.tunnelTelemetry,
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
        session.encryptedDnsFailoverState.resetAll()
        vpnTunnelRuntime.stop()
        proxyRuntimeSupervisor.stop()
        warpRuntimeSupervisor.stop()
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
        val logContext = session.buildLogContext(session.currentActiveConnectionPolicy)
        resolution.proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
            warpRuntimeSupervisor.start(warpConfig, ::handleWarpExit)
        }
        proxyRuntimeSupervisor.start(
            resolution.proxyPreferences.withLogContext(logContext),
            ::handleProxyExit,
        )
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
            logContext = logContext,
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
        resolverOverrideStore.clear()
        vpnTunnelRuntime.resetRuntimeState()
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

    private suspend fun handleProxyExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("Proxy runtime failed", throwable)
                Logger.e(error) { "Proxy failed" }
                classifyFailureReason(error, isTunnelContext = true)
            } ?: result
                .getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    Logger.e { "Proxy stopped with code $code" }
                    FailureReason.NativeError("Proxy exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        proxyRuntimeSupervisor.detach()
        warpRuntimeSupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private suspend fun handleWarpExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("WARP runtime failed", throwable)
                Logger.e(error) { "WARP failed" }
                classifyFailureReason(error, isTunnelContext = true)
            } ?: result
                .getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    Logger.e { "WARP stopped with code $code" }
                    FailureReason.NativeError("WARP exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        warpRuntimeSupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }
}

internal class VpnServiceRuntimeCoordinatorFactory
    @Inject
    constructor(
        private val runtimeDependencies: VpnServiceRuntimeRuntimeDependencies,
        private val statusDependencies: VpnServiceRuntimeStatusDependencies,
        private val permissionWatchdog: PermissionWatchdog,
    ) {
        fun create(host: VpnCoordinatorHost): VpnServiceRuntimeCoordinator =
            VpnServiceRuntimeCoordinator(
                vpnHost = host,
                connectionPolicyResolver = runtimeDependencies.connectionPolicyResolver,
                resolverOverrideStore = runtimeDependencies.resolverOverrideStore,
                serviceRuntimeRegistry = runtimeDependencies.serviceRuntimeRegistry,
                rememberedNetworkPolicyStore = runtimeDependencies.rememberedNetworkPolicyStore,
                networkHandoverMonitor = runtimeDependencies.networkHandoverMonitor,
                policyHandoverEventStore = runtimeDependencies.policyHandoverEventStore,
                permissionWatchdog = permissionWatchdog,
                vpnTunnelRuntime =
                    VpnTunnelRuntime(
                        vpnHost = host,
                        appSettingsRepository = runtimeDependencies.appSettingsRepository,
                        tun2SocksBridgeFactory = runtimeDependencies.tun2SocksBridgeFactory,
                        vpnTunnelSessionProvider = runtimeDependencies.vpnTunnelSessionProvider,
                    ),
                resolverRefreshPlanner = runtimeDependencies.dnsDependencies.resolverRefreshPlanner,
                encryptedDnsFailoverController =
                    VpnEncryptedDnsFailoverController(
                        resolverOverrideStore = runtimeDependencies.resolverOverrideStore,
                        networkDnsPathPreferenceStore =
                            runtimeDependencies.dnsDependencies.networkDnsPathPreferenceStore,
                        networkDnsBlockedPathStore =
                            runtimeDependencies.dnsDependencies.networkDnsBlockedPathStore,
                        networkFingerprintProvider = statusDependencies.networkFingerprintProvider,
                    ),
                warpRuntimeSupervisor =
                    runtimeDependencies.warpRuntimeSupervisorFactory.create(
                        scope = host.serviceScope,
                        dispatcher = Dispatchers.IO,
                    ),
                proxyRuntimeSupervisor =
                    runtimeDependencies.proxyRuntimeSupervisorFactory.create(
                        scope = host.serviceScope,
                        dispatcher = Dispatchers.IO,
                        networkSnapshotProvider = runtimeDependencies.networkSnapshotProvider,
                    ),
                statusReporter =
                    statusDependencies.serviceStatusReporterFactory.create(
                        mode = Mode.VPN,
                        sender = Sender.VPN,
                        serviceStateStore = statusDependencies.serviceStateStore,
                        networkFingerprintProvider = statusDependencies.networkFingerprintProvider,
                        telemetryFingerprintHasher = statusDependencies.telemetryFingerprintHasher,
                    ),
                screenStateObserver = runtimeDependencies.screenStateObserver,
            )
    }

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
    val warpTelemetry: NativeRuntimeSnapshot,
    val tunnelTelemetry: NativeRuntimeSnapshot,
    val tunnelTelemetryResult: Result<NativeRuntimeSnapshot?>,
) {
    fun failureReason(): FailureReason? =
        tunnelTelemetryResult.exceptionOrNull()?.let { throwable ->
            val error = throwable as? Exception ?: IllegalStateException("Tunnel telemetry failed", throwable)
            classifyFailureReason(error, isTunnelContext = true)
        }
}
