package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@Suppress("LongParameterList")
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
    private val directPathPolicyTelemetryConsumer:
        DirectPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
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
    private var currentLocalProxyEndpoint: LocalProxyEndpoint? = null
    private val proxyRuntimeStack =
        SharedProxyRuntimeStack(
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
        )
    private val dnsPolicyCoordinator =
        VpnDnsPolicyCoordinator(
            resolverRefreshPlanner = resolverRefreshPlanner,
            encryptedDnsFailoverController = encryptedDnsFailoverController,
        )
    private val supervisorExitHandler =
        VpnSupervisorExitHandler(
            host = vpnHost,
            ioDispatcher = ioDispatcher,
            proxyRuntimeStack = proxyRuntimeStack,
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            updateStatus = ::updateStatus,
            stopService = { skipRuntimeShutdown -> stop(skipRuntimeShutdown = skipRuntimeShutdown) },
        )
    private val telemetryCoordinator =
        VpnTelemetryCoordinator(
            dependencies =
                object : VpnTelemetryRuntimeDependencies {
                    override val host: VpnCoordinatorHost = vpnHost
                    override val ioDispatcher: CoroutineDispatcher = ioDispatcher
                    override val mutex = this@VpnServiceRuntimeCoordinator.mutex
                    override val vpnProtectFailureMonitor = this@VpnServiceRuntimeCoordinator.vpnProtectFailureMonitor
                    override val vpnTunnelRuntime = this@VpnServiceRuntimeCoordinator.vpnTunnelRuntime
                    override val dnsPolicyCoordinator = this@VpnServiceRuntimeCoordinator.dnsPolicyCoordinator
                    override val upstreamRelaySupervisor = this@VpnServiceRuntimeCoordinator.upstreamRelaySupervisor
                    override val warpRuntimeSupervisor = this@VpnServiceRuntimeCoordinator.warpRuntimeSupervisor
                    override val proxyRuntimeSupervisor = this@VpnServiceRuntimeCoordinator.proxyRuntimeSupervisor
                    override val statusReporter = this@VpnServiceRuntimeCoordinator.statusReporter
                    override val screenStateObserver = this@VpnServiceRuntimeCoordinator.screenStateObserver
                    override val directPathPolicyTelemetryConsumer =
                        this@VpnServiceRuntimeCoordinator.directPathPolicyTelemetryConsumer
                },
            state =
                object : VpnTelemetryStateAccess {
                    override fun status(): ServiceStatus = status

                    override fun stopping(): Boolean = stopping

                    override fun runtimeSession(): VpnRuntimeSession? = runtimeSession

                    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? = currentLocalProxyEndpoint

                    override fun currentNetworkHandoverState(): String? =
                        this@VpnServiceRuntimeCoordinator.currentNetworkHandoverState()

                    override fun applyPendingNetworkHandoverClass(
                        snapshot: com.poyka.ripdpi.data.NativeRuntimeSnapshot,
                    ): com.poyka.ripdpi.data.NativeRuntimeSnapshot =
                        this@VpnServiceRuntimeCoordinator.applyPendingNetworkHandoverClass(snapshot)
                },
            callbacks =
                object : VpnTelemetryCallbacks {
                    override fun updateRuntimeDnsState(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) {
                        this@VpnServiceRuntimeCoordinator.updateRuntimeDnsState(session, resolution)
                    }

                    override fun updateStatus(
                        status: ServiceStatus,
                        failureReason: FailureReason?,
                    ) {
                        this@VpnServiceRuntimeCoordinator.updateStatus(status, failureReason)
                    }

                    override suspend fun stopService() {
                        stop()
                    }
                },
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
                onRelayExit = supervisorExitHandler::handleRelayExit,
                onWarpExit = supervisorExitHandler::handleWarpExit,
                onProxyExit = supervisorExitHandler::handleProxyExit,
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
        var stopFailure: Throwable? = null
        runCatching {
            vpnTunnelRuntime.stop()
        }.onFailure { failure ->
            stopFailure = failure
        }
        runCatching {
            proxyRuntimeStack.stop(skipRuntimeShutdown)
        }.onFailure { failure ->
            val previousFailure = stopFailure
            if (previousFailure == null) {
                stopFailure = failure
            } else {
                previousFailure.addSuppressed(failure)
            }
        }
        stopFailure?.let { failure ->
            val error = failure as? Exception ?: IllegalStateException("Failed to stop VPN runtime", failure)
            throw error
        }
    }

    override fun startModeTelemetryUpdates() {
        telemetryCoordinator.start(::replaceTelemetryJob)
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
                onRelayExit = supervisorExitHandler::handleRelayExit,
                onWarpExit = supervisorExitHandler::handleWarpExit,
                onProxyExit = supervisorExitHandler::handleProxyExit,
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
        telemetryCoordinator.stopProtectFailureMonitoring()
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
