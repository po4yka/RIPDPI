package com.poyka.ripdpi.service.runtime.vpn

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
import com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.services.BaseServiceRuntimeCoordinator
import com.poyka.ripdpi.services.ConnectionPolicyResolution
import com.poyka.ripdpi.services.ConnectionPolicyResolver
import com.poyka.ripdpi.services.DirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.LocalProxyEndpoint
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import com.poyka.ripdpi.services.NoOpDirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.PermissionChangeEvent
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.ProxyRuntimeSupervisor
import com.poyka.ripdpi.services.ProxyRuntimeSupervisorFactory
import com.poyka.ripdpi.services.RootHelperManager
import com.poyka.ripdpi.services.ScreenStateObserver
import com.poyka.ripdpi.services.ServiceClock
import com.poyka.ripdpi.services.ServiceRuntimeHandoverHooks
import com.poyka.ripdpi.services.ServiceRuntimeModeHooks
import com.poyka.ripdpi.services.ServiceRuntimePermissionHooks
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.ServiceRuntimeStartHooks
import com.poyka.ripdpi.services.ServiceRuntimeStatusHooks
import com.poyka.ripdpi.services.ServiceRuntimeStopHooks
import com.poyka.ripdpi.services.ServiceStatusReporter
import com.poyka.ripdpi.services.ServiceStatusReporterFactory
import com.poyka.ripdpi.services.SharedProxyRuntimeStack
import com.poyka.ripdpi.services.SystemServiceClock
import com.poyka.ripdpi.services.TelemetryFingerprintHasher
import com.poyka.ripdpi.services.TelemetryJobReplacer
import com.poyka.ripdpi.services.UpstreamRelaySupervisor
import com.poyka.ripdpi.services.UpstreamRelaySupervisorFactory
import com.poyka.ripdpi.services.VpnCoordinatorHost
import com.poyka.ripdpi.services.VpnDnsPolicyCoordinator
import com.poyka.ripdpi.services.VpnEncryptedDnsFailoverController
import com.poyka.ripdpi.services.VpnProtectFailureMonitor
import com.poyka.ripdpi.services.VpnResolverRefreshPlanner
import com.poyka.ripdpi.services.VpnRuntimeCompositionCoordinator
import com.poyka.ripdpi.services.VpnRuntimeSession
import com.poyka.ripdpi.services.VpnRuntimeTelemetryReporter
import com.poyka.ripdpi.services.VpnSupervisorExitHandler
import com.poyka.ripdpi.services.VpnTelemetryCoordinator
import com.poyka.ripdpi.services.VpnTelemetryFailureCallbacks
import com.poyka.ripdpi.services.VpnTelemetryRuntimeDependencies
import com.poyka.ripdpi.services.VpnTelemetryStateAccess
import com.poyka.ripdpi.services.VpnTunnelRefreshCallbacks
import com.poyka.ripdpi.services.VpnTunnelRefreshCoordinator
import com.poyka.ripdpi.services.VpnTunnelRefreshDependencies
import com.poyka.ripdpi.services.VpnTunnelRuntime
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.WarpRuntimeSupervisor
import com.poyka.ripdpi.services.WarpRuntimeSupervisorFactory
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
    private val rootHelperManager: RootHelperManager = RootHelperManager(),
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
    private val runtimeCompositionCoordinator =
        VpnRuntimeCompositionCoordinator(
            proxyRuntimeStack = proxyRuntimeStack,
            vpnTunnelRuntime = vpnTunnelRuntime,
            supervisorExitHandler = supervisorExitHandler,
            applyActiveConnectionPolicy = ::applyActiveConnectionPolicy,
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
                    override val upstreamRelaySupervisor = this@VpnServiceRuntimeCoordinator.upstreamRelaySupervisor
                    override val warpRuntimeSupervisor = this@VpnServiceRuntimeCoordinator.warpRuntimeSupervisor
                    override val proxyRuntimeSupervisor = this@VpnServiceRuntimeCoordinator.proxyRuntimeSupervisor
                    override val screenStateObserver = this@VpnServiceRuntimeCoordinator.screenStateObserver
                    override val telemetryReporter =
                        VpnRuntimeTelemetryReporter(
                            host = vpnHost,
                            statusReporter = this@VpnServiceRuntimeCoordinator.statusReporter,
                            screenStateObserver = this@VpnServiceRuntimeCoordinator.screenStateObserver,
                            directPathPolicyTelemetryConsumer =
                                this@VpnServiceRuntimeCoordinator.directPathPolicyTelemetryConsumer,
                            vpnTunnelRuntime = this@VpnServiceRuntimeCoordinator.vpnTunnelRuntime,
                        )
                },
            state =
                object : VpnTelemetryStateAccess {
                    override fun status(): ServiceStatus = status

                    override fun stopping(): Boolean = stopping

                    override fun runtimeSession(): VpnRuntimeSession? = runtimeSession

                    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? =
                        runtimeCompositionCoordinator.currentLocalProxyEndpoint

                    override fun currentNetworkHandoverState(): String? =
                        this@VpnServiceRuntimeCoordinator.currentNetworkHandoverState()

                    override fun applyPendingNetworkHandoverClass(
                        snapshot: com.poyka.ripdpi.data.NativeRuntimeSnapshot,
                    ): com.poyka.ripdpi.data.NativeRuntimeSnapshot =
                        this@VpnServiceRuntimeCoordinator.applyPendingNetworkHandoverClass(snapshot)
                },
            callbacks =
                object : VpnTelemetryFailureCallbacks {
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
    private val tunnelRefreshCoordinator =
        VpnTunnelRefreshCoordinator(
            dependencies =
                object : VpnTunnelRefreshDependencies {
                    override val mutex = this@VpnServiceRuntimeCoordinator.mutex
                    override val vpnTunnelRuntime = this@VpnServiceRuntimeCoordinator.vpnTunnelRuntime
                    override val dnsPolicyCoordinator = this@VpnServiceRuntimeCoordinator.dnsPolicyCoordinator
                },
            state =
                object : VpnTelemetryStateAccess {
                    override fun status(): ServiceStatus = status

                    override fun stopping(): Boolean = stopping

                    override fun runtimeSession(): VpnRuntimeSession? = runtimeSession

                    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? =
                        runtimeCompositionCoordinator.currentLocalProxyEndpoint

                    override fun currentNetworkHandoverState(): String? =
                        this@VpnServiceRuntimeCoordinator.currentNetworkHandoverState()

                    override fun applyPendingNetworkHandoverClass(
                        snapshot: com.poyka.ripdpi.data.NativeRuntimeSnapshot,
                    ): com.poyka.ripdpi.data.NativeRuntimeSnapshot =
                        this@VpnServiceRuntimeCoordinator.applyPendingNetworkHandoverClass(snapshot)
                },
            callbacks =
                object : VpnTunnelRefreshCallbacks {
                    override fun updateRuntimeDnsState(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) {
                        runtimeCompositionCoordinator.updateRuntimeDnsState(session, resolution)
                    }
                },
        )

    override val runtimeHooks =
        ServiceRuntimeModeHooks(
            serviceLabel = "VPN",
            startHooks =
                ServiceRuntimeStartHooks(
                    createRuntimeSession = ::createRuntimeSession,
                    resolveInitialConnectionPolicy = ::resolveInitialConnectionPolicy,
                    applyActiveConnectionPolicy = ::applyActiveConnectionPolicy,
                    startResolvedRuntime = ::startResolvedRuntime,
                    startModeTelemetryUpdates = ::startModeTelemetryUpdates,
                ),
            stopHooks =
                ServiceRuntimeStopHooks(
                    stopModeRuntime = ::stopModeRuntime,
                    onAfterStopCleanup = ::onAfterStopCleanup,
                ),
            handoverHooks =
                ServiceRuntimeHandoverHooks(
                    resolveConnectionPolicy = ::resolveHandoverConnectionPolicy,
                    restartAfterHandover = ::restartAfterHandover,
                    classifyFailure = ::classifyHandoverFailure,
                ),
            statusHooks =
                ServiceRuntimeStatusHooks(
                    updateStatus = ::updateStatus,
                    classifyStartupFailure = ::classifyStartupFailure,
                ),
            permissionHooks = ServiceRuntimePermissionHooks(::onPermissionRevoked),
        )

    private fun createRuntimeSession(): VpnRuntimeSession = VpnRuntimeSession()

    private suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.VPN,
            resolverOverride = resolverOverrideStore.override.value,
        )

    private suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.VPN,
            resolverOverride = resolverOverrideStore.override.value,
            fingerprint = fingerprint,
            handoverClassification = handoverClassification,
        )

    private fun applyActiveConnectionPolicy(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        session.updateActiveConnectionPolicy(
            resolution.toVpnActiveConnectionPolicy(
                restartReason = restartReason,
                appliedAt = appliedAt,
            ),
        )
    }

    private suspend fun startResolvedRuntime(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        runtimeCompositionCoordinator.start(session, resolution)
    }

    private suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        runtimeCompositionCoordinator.stop(skipRuntimeShutdown)
    }

    private fun startModeTelemetryUpdates(replaceTelemetryJob: TelemetryJobReplacer) {
        telemetryCoordinator.start(tunnelRefreshCoordinator, replaceTelemetryJob)
    }

    private suspend fun restartAfterHandover(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        runtimeCompositionCoordinator.restartAfterHandover(session, resolution, appliedAt)
    }

    private fun updateStatus(
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

    private fun classifyStartupFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    private fun classifyHandoverFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    private fun onPermissionRevoked(event: PermissionChangeEvent) {
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

    private fun onAfterStopCleanup(session: VpnRuntimeSession?) {
        telemetryCoordinator.stopProtectFailureMonitoring()
        resolverOverrideStore.clear()
        runtimeCompositionCoordinator.resetAfterStop(session)
        rootHelperManager.stop()
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
