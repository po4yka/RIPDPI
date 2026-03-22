package com.poyka.ripdpi.services

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
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import javax.inject.Inject

internal class VpnServiceRuntimeCoordinator(
    vpnHost: VpnCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    private val resolverOverrideStore: ResolverOverrideStore,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    private val vpnTunnelRuntime: VpnTunnelRuntime,
    private val resolverRefreshPlanner: VpnResolverRefreshPlanner,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val statusReporter: ServiceStatusReporter,
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
        ioDispatcher = ioDispatcher,
        clock = clock,
    ) {
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
        proxyRuntimeSupervisor.start(resolution.proxyPreferences, ::handleProxyExit)
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
        )
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        vpnTunnelRuntime.stop()
        if (skipRuntimeShutdown) {
            proxyRuntimeSupervisor.detach()
        } else {
            proxyRuntimeSupervisor.stop()
        }
    }

    override fun startModeTelemetryUpdates() {
        replaceTelemetryJob {
            while (status == ServiceStatus.Connected) {
                val session = runtimeSession ?: return@replaceTelemetryJob
                val initialRefreshPlan =
                    resolverRefreshPlanner.plan(
                        currentSignature = vpnTunnelRuntime.currentDnsSignature,
                        tunnelRunning = vpnTunnelRuntime.isRunning,
                    )
                if (initialRefreshPlan.requiresTunnelRebuild) {
                    mutex.withLock {
                        val activeSession = runtimeSession
                        if (
                            status != ServiceStatus.Connected ||
                            !vpnTunnelRuntime.isRunning ||
                            activeSession?.runtimeId != session.runtimeId
                        ) {
                            return@withLock
                        }
                        val latestRefreshPlan =
                            resolverRefreshPlanner.plan(
                                currentSignature = vpnTunnelRuntime.currentDnsSignature,
                                tunnelRunning = vpnTunnelRuntime.isRunning,
                            )
                        if (!latestRefreshPlan.requiresTunnelRebuild) {
                            return@withLock
                        }
                        val latestResolution =
                            latestRefreshPlan.connectionPolicy
                                ?: error("VPN resolver refresh plan missing connection policy")
                        vpnTunnelRuntime.stop()
                        vpnTunnelRuntime.start(
                            activeDns = latestResolution.activeDns,
                            overrideReason = latestResolution.resolverFallbackReason,
                        )
                    }
                }

                val proxyTelemetry =
                    proxyRuntimeSupervisor.pollTelemetry()
                        ?: NativeRuntimeSnapshot.idle(source = "proxy")
                val tunnelTelemetryResult = vpnTunnelRuntime.pollTelemetry()
                val tunnelTelemetry =
                    tunnelTelemetryResult.getOrNull()
                        ?: NativeRuntimeSnapshot.idle(source = "tunnel")
                val enrichedTunnelTelemetry = applyPendingNetworkHandoverClass(tunnelTelemetry)

                if (!stopping) {
                    val telemetryFailure =
                        tunnelTelemetryResult.exceptionOrNull()?.let { throwable ->
                            val error =
                                throwable as? Exception
                                    ?: IllegalStateException("Tunnel telemetry failed", throwable)
                            classifyFailureReason(error, isTunnelContext = true)
                        }
                    val tunnelStoppedUnexpectedly =
                        vpnTunnelRuntime.isRunning && enrichedTunnelTelemetry.state != "running"
                    if (telemetryFailure != null || tunnelStoppedUnexpectedly) {
                        val failureReason =
                            telemetryFailure
                                ?: FailureReason.NativeError(
                                    enrichedTunnelTelemetry.lastError ?: "Tunnel exited unexpectedly",
                                )
                        statusReporter.reportTelemetry(
                            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
                            consumePendingNetworkHandoverClass = { null },
                            proxyTelemetry = proxyTelemetry,
                            tunnelTelemetry = enrichedTunnelTelemetry,
                            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
                            failureReason = failureReason,
                        )
                        updateStatus(ServiceStatus.Failed, failureReason)
                        requestAsyncStop()
                        return@replaceTelemetryJob
                    }
                }

                statusReporter.reportTelemetry(
                    activePolicy = runtimeSession?.currentActiveConnectionPolicy,
                    consumePendingNetworkHandoverClass = { null },
                    proxyTelemetry = proxyTelemetry,
                    tunnelTelemetry = enrichedTunnelTelemetry,
                    tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
                )
                if (statusReporter.startedAt != null) {
                    host.updateNotification(
                        tunnelStats = enrichedTunnelTelemetry.tunnelStats,
                        proxyTelemetry = proxyTelemetry,
                    )
                }
                delay(1_000L)
            }
        }
    }

    override suspend fun restartAfterHandover(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        vpnTunnelRuntime.stop()
        proxyRuntimeSupervisor.stop()
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
        proxyRuntimeSupervisor.start(resolution.proxyPreferences, ::handleProxyExit)
        vpnTunnelRuntime.start(
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
        )
    }

    override fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        logcat(LogPriority.DEBUG) { "VPN status: $status -> $newStatus" }
        status = newStatus
        statusReporter.reportStatus(
            newStatus = newStatus,
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = ::consumePendingNetworkHandoverClass,
            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
            failureReason = failureReason,
        )
    }

    override fun classifyStartupFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    override fun classifyHandoverFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    override fun onAfterStopCleanup(session: VpnRuntimeSession?) {
        resolverOverrideStore.clear()
        vpnTunnelRuntime.resetRuntimeState()
    }

    private suspend fun handleProxyExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("Proxy runtime failed", throwable)
                logcat(LogPriority.ERROR) { "Proxy failed\n${error.asLog()}" }
                classifyFailureReason(error, isTunnelContext = true)
            } ?: result
                .getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    logcat(LogPriority.ERROR) { "Proxy stopped with code $code" }
                    FailureReason.NativeError("Proxy exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        proxyRuntimeSupervisor.detach()
        requestAsyncStop(skipRuntimeShutdown = true)
    }
}

internal class VpnServiceRuntimeCoordinatorFactory
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val connectionPolicyResolver: ConnectionPolicyResolver,
        private val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
        private val serviceStateStore: ServiceStateStore,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val telemetryFingerprintHasher: TelemetryFingerprintHasher,
        private val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
        private val resolverOverrideStore: ResolverOverrideStore,
        private val serviceRuntimeRegistry: ServiceRuntimeRegistry,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val networkHandoverMonitor: NetworkHandoverMonitor,
        private val policyHandoverEventStore: PolicyHandoverEventStore,
        private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        private val resolverRefreshPlanner: VpnResolverRefreshPlanner,
        private val proxyRuntimeSupervisorFactory: ProxyRuntimeSupervisorFactory,
        private val serviceStatusReporterFactory: ServiceStatusReporterFactory,
    ) {
        fun create(host: VpnCoordinatorHost): VpnServiceRuntimeCoordinator =
            VpnServiceRuntimeCoordinator(
                vpnHost = host,
                connectionPolicyResolver = connectionPolicyResolver,
                resolverOverrideStore = resolverOverrideStore,
                serviceRuntimeRegistry = serviceRuntimeRegistry,
                rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                networkHandoverMonitor = networkHandoverMonitor,
                policyHandoverEventStore = policyHandoverEventStore,
                vpnTunnelRuntime =
                    VpnTunnelRuntime(
                        vpnHost = host,
                        appSettingsRepository = appSettingsRepository,
                        tun2SocksBridgeFactory = tun2SocksBridgeFactory,
                        vpnTunnelSessionProvider = vpnTunnelSessionProvider,
                    ),
                resolverRefreshPlanner = resolverRefreshPlanner,
                proxyRuntimeSupervisor =
                    proxyRuntimeSupervisorFactory.create(
                        scope = host.serviceScope,
                        dispatcher = Dispatchers.IO,
                        networkSnapshotProvider = networkSnapshotProvider,
                    ),
                statusReporter =
                    serviceStatusReporterFactory.create(
                        mode = Mode.VPN,
                        sender = Sender.VPN,
                        serviceStateStore = serviceStateStore,
                        networkFingerprintProvider = networkFingerprintProvider,
                        telemetryFingerprintHasher = telemetryFingerprintHasher,
                    ),
            )
    }
