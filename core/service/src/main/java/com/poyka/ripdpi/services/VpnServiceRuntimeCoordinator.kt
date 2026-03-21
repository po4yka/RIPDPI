package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.activeDnsSettings
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
    private val vpnHost: VpnCoordinatorHost,
    private val appSettingsRepository: AppSettingsRepository,
    connectionPolicyResolver: ConnectionPolicyResolver,
    private val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    private val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
    private val resolverOverrideStore: ResolverOverrideStore,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
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
    private companion object {
        private const val MapDnsAddress = "198.18.0.53"
    }

    private var tun2SocksBridge: Tun2SocksBridge? = null
    private var tunSession: VpnTunnelSession? = null

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
        startTun2Socks(
            session = session,
            activeDns = resolution.activeDns,
            overrideReason = resolution.resolverFallbackReason,
        )
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        stopTun2Socks()
        if (skipRuntimeShutdown) {
            proxyRuntimeSupervisor.detach()
        } else {
            proxyRuntimeSupervisor.stop()
        }
    }

    override fun startModeTelemetryUpdates() {
        replaceTelemetryJob {
            while (status == ServiceStatus.Connected) {
                refreshEffectiveResolverIfNeeded()
                val proxyTelemetry =
                    proxyRuntimeSupervisor.pollTelemetry()
                        ?: NativeRuntimeSnapshot.idle(source = "proxy")
                val tunnelTelemetryResult = runCatching { tun2SocksBridge?.telemetry() }
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
                        tunSession != null && enrichedTunnelTelemetry.state != "running"
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
                            tunnelRecoveryRetryCount = runtimeSession?.tunnelRecoveryRetryCount ?: 0L,
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
                    tunnelRecoveryRetryCount = runtimeSession?.tunnelRecoveryRetryCount ?: 0L,
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
        stopTun2Socks()
        proxyRuntimeSupervisor.stop()
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
        proxyRuntimeSupervisor.start(resolution.proxyPreferences, ::handleProxyExit)
        startTun2Socks(
            session = session,
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
            tunnelRecoveryRetryCount = runtimeSession?.tunnelRecoveryRetryCount ?: 0L,
            failureReason = failureReason,
        )
    }

    override fun classifyStartupFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    override fun classifyHandoverFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    override fun onAfterStopCleanup(session: VpnRuntimeSession?) {
        resolverOverrideStore.clear()
    }

    private suspend fun startTun2Socks(
        session: VpnRuntimeSession,
        activeDns: ActiveDnsSettings,
        overrideReason: String? = null,
    ) {
        check(tunSession == null) { "VPN field not null" }

        val settings = appSettingsRepository.snapshot()
        val port = if (settings.proxyPort > 0) settings.proxyPort else 1080
        val dns = if (activeDns.mode == DnsModeEncrypted) MapDnsAddress else activeDns.dnsIp
        val ipv6 = settings.ipv6Enable
        val config = RipDpiVpnService.buildTun2SocksConfig(activeDns, overrideReason, port, ipv6)

        val tunnelSession = vpnTunnelSessionProvider.establish(vpnHost, dns, ipv6)
        try {
            val tunnelBridge = tun2SocksBridgeFactory.create()
            tunnelBridge.start(config, tunnelSession.tunFd)
            tun2SocksBridge = tunnelBridge
            tunSession = tunnelSession
            session.currentDnsSignature = dnsSignature(activeDns, overrideReason)
            if (session.tunnelStartCount > 0) {
                session.tunnelRecoveryRetryCount += 1
            }
            session.tunnelStartCount += 1
        } catch (error: Exception) {
            tunnelSession.close()
            throw error
        }

        vpnHost.syncUnderlyingNetworksFromActiveNetwork()
    }

    private suspend fun stopTun2Socks() {
        val session = tunSession ?: return

        try {
            tun2SocksBridge?.stop()
        } finally {
            tun2SocksBridge = null
            session.close()
            tunSession = null
        }
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

    private suspend fun refreshEffectiveResolverIfNeeded() {
        val session = runtimeSession ?: return
        val resolution =
            connectionPolicyResolver.resolve(
                mode = Mode.VPN,
                resolverOverride = resolverOverrideStore.override.value,
            )
        if (resolution.settings.activeDnsSettings() == resolution.activeDns &&
            resolverOverrideStore.override.value != null
        ) {
            resolverOverrideStore.clear()
        }
        val signature = dnsSignature(resolution.activeDns, resolution.resolverFallbackReason)
        if (tunSession == null || session.currentDnsSignature == signature) {
            return
        }

        mutex.withLock {
            val activeSession = runtimeSession
            if (status != ServiceStatus.Connected || tunSession == null ||
                activeSession?.runtimeId != session.runtimeId
            ) {
                return@withLock
            }
            val latestResolution =
                connectionPolicyResolver.resolve(
                    mode = Mode.VPN,
                    resolverOverride = resolverOverrideStore.override.value,
                )
            if (latestResolution.settings.activeDnsSettings() == latestResolution.activeDns &&
                resolverOverrideStore.override.value != null
            ) {
                resolverOverrideStore.clear()
            }
            val latestSignature = dnsSignature(latestResolution.activeDns, latestResolution.resolverFallbackReason)
            if (activeSession.currentDnsSignature == latestSignature) {
                return@withLock
            }
            stopTun2Socks()
            startTun2Socks(
                session = activeSession,
                activeDns = latestResolution.activeDns,
                overrideReason = latestResolution.resolverFallbackReason,
            )
        }
    }
}

internal class VpnServiceRuntimeCoordinatorFactory
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val connectionPolicyResolver: ConnectionPolicyResolver,
        private val ripDpiProxyFactory: RipDpiProxyFactory,
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
        private val networkSnapshotFactory: NetworkSnapshotFactory,
    ) {
        fun create(host: VpnCoordinatorHost): VpnServiceRuntimeCoordinator =
            VpnServiceRuntimeCoordinator(
                vpnHost = host,
                appSettingsRepository = appSettingsRepository,
                connectionPolicyResolver = connectionPolicyResolver,
                tun2SocksBridgeFactory = tun2SocksBridgeFactory,
                vpnTunnelSessionProvider = vpnTunnelSessionProvider,
                resolverOverrideStore = resolverOverrideStore,
                serviceRuntimeRegistry = serviceRuntimeRegistry,
                rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                networkHandoverMonitor = networkHandoverMonitor,
                policyHandoverEventStore = policyHandoverEventStore,
                proxyRuntimeSupervisor =
                    ProxyRuntimeSupervisor(
                        scope = host.serviceScope,
                        dispatcher = Dispatchers.IO,
                        ripDpiProxyFactory = ripDpiProxyFactory,
                        networkSnapshotProvider = networkSnapshotFactory,
                    ),
                statusReporter =
                    ServiceStatusReporter(
                        mode = Mode.VPN,
                        sender = Sender.VPN,
                        serviceStateStore = serviceStateStore,
                        networkFingerprintProvider = networkFingerprintProvider,
                        telemetryFingerprintHasher = telemetryFingerprintHasher,
                    ),
            )
    }
