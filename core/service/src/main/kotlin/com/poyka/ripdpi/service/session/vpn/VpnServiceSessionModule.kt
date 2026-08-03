package com.poyka.ripdpi.service.session.vpn

import android.net.VpnService
import com.poyka.ripdpi.core.RipDpiXrayRuntime
import com.poyka.ripdpi.core.XrayNativeBridge
import com.poyka.ripdpi.core.XrayProviderOrchestrator
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.XrayConfigRenderer
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeCoordinator
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeRuntimeDependencies
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeStatusDependencies
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisor
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisorFactory
import com.poyka.ripdpi.services.DirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.FlowAttributionBridge
import com.poyka.ripdpi.services.InMemoryVpnProtectFailureMonitor
import com.poyka.ripdpi.services.InitialRelayRacePolicy
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.ProxyRuntimeSupervisor
import com.poyka.ripdpi.services.ProxyRuntimeSupervisorFactory
import com.poyka.ripdpi.services.RelayRuntimeNetworkMode
import com.poyka.ripdpi.services.RemoteDeviceRecoveryReceiptCollector
import com.poyka.ripdpi.services.RootHelperManager
import com.poyka.ripdpi.services.ServiceSessionScope
import com.poyka.ripdpi.services.ServiceStatusReporter
import com.poyka.ripdpi.services.UpstreamRelaySupervisor
import com.poyka.ripdpi.services.UpstreamRelaySupervisorFactory
import com.poyka.ripdpi.services.VpnCoordinatorHost
import com.poyka.ripdpi.services.VpnEncryptedDnsFailoverController
import com.poyka.ripdpi.services.VpnProtectFailureMonitor
import com.poyka.ripdpi.services.VpnProtectSocketServer
import com.poyka.ripdpi.services.VpnServiceSessionComponent
import com.poyka.ripdpi.services.VpnServiceXrayProtectController
import com.poyka.ripdpi.services.VpnTunnelAppliedNetworkReceiptStore
import com.poyka.ripdpi.services.VpnTunnelRuntime
import com.poyka.ripdpi.services.VpnTunnelRuntimeCallbacks
import com.poyka.ripdpi.services.WarpRuntimeSupervisor
import com.poyka.ripdpi.services.WarpRuntimeSupervisorFactory
import com.poyka.ripdpi.services.XrayManagedTunnel
import com.poyka.ripdpi.services.XrayProviderDiagnosticsProbeRunner
import com.poyka.ripdpi.services.XrayProviderRouteBuilder
import com.poyka.ripdpi.services.XrayProviderSessionController
import com.poyka.ripdpi.services.XrayProviderSnapshotDeriver
import com.poyka.ripdpi.services.XrayRenderedConfigHolder
import com.poyka.ripdpi.services.XrayTunnelStartParamsHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.io.File
import java.util.Optional

internal data class VpnCoordinatorServices(
    val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    val statusReporter: ServiceStatusReporter,
    val directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer,
    val rootHelperManager: RootHelperManager,
    val xrayProviderSessionController: XrayProviderSessionController,
)

@Module
@InstallIn(VpnServiceSessionComponent::class)
@Suppress("TooManyFunctions")
internal object VpnServiceSessionModule {
    @Provides
    @ServiceSessionScope
    fun provideVpnProtectFailureMonitor(): VpnProtectFailureMonitor = InMemoryVpnProtectFailureMonitor()

    @Provides
    @ServiceSessionScope
    fun provideVpnProtectSocketServer(
        vpnService: VpnService,
        protectFailureMonitor: VpnProtectFailureMonitor,
    ): VpnProtectSocketServer =
        VpnProtectSocketServer(
            vpnService = vpnService,
            socketPath = File(vpnService.filesDir, "protect_path").absolutePath,
            protectFailureMonitor = protectFailureMonitor,
        )

    @Provides
    @ServiceSessionScope
    fun provideVpnTunnelRuntime(
        vpnService: VpnService,
        host: VpnCoordinatorHost,
        dependencies: VpnServiceRuntimeRuntimeDependencies,
        protectSocketServer: VpnProtectSocketServer,
        rootHelperManager: RootHelperManager,
        flowAttributionBridge: FlowAttributionBridge,
        recoveryReceiptCollector: RemoteDeviceRecoveryReceiptCollector,
        appliedNetworkReceiptStore: VpnTunnelAppliedNetworkReceiptStore,
        pcapCaptureRuntimeController: PcapCaptureRuntimeController,
    ): VpnTunnelRuntime =
        createVpnTunnelRuntime(
            host = host,
            dependencies = dependencies,
            protectSocketServer = protectSocketServer,
            rootHelperManager = rootHelperManager,
            flowAttributionBridge = flowAttributionBridge,
            recoveryReceiptCollector = recoveryReceiptCollector,
            appliedNetworkReceiptStore = appliedNetworkReceiptStore,
            pcapCaptureRuntimeController = pcapCaptureRuntimeController,
            recoveryServiceInstanceId =
                (vpnService as? com.poyka.ripdpi.services.RipDpiVpnService)
                    ?.recoveryServiceInstanceId,
            recoveryGenerationProvider = {
                (vpnService as? com.poyka.ripdpi.services.RipDpiVpnService)
                    ?.activeRecoveryGeneration
            },
            geositeDbPath = resolveGeoDatabasePaths(vpnService).geositeDbPath,
        )

    internal fun createVpnTunnelRuntime(
        host: VpnCoordinatorHost,
        dependencies: VpnServiceRuntimeRuntimeDependencies,
        protectSocketServer: VpnProtectSocketServer,
        rootHelperManager: RootHelperManager,
        flowAttributionBridge: FlowAttributionBridge,
        recoveryReceiptCollector: RemoteDeviceRecoveryReceiptCollector? = null,
        recoveryServiceInstanceId: String? = null,
        recoveryGenerationProvider: () -> String? = { null },
        geositeDbPath: String? = null,
        appliedNetworkReceiptStore: VpnTunnelAppliedNetworkReceiptStore = VpnTunnelAppliedNetworkReceiptStore(),
        pcapCaptureRuntimeController: PcapCaptureRuntimeController? = null,
    ): VpnTunnelRuntime =
        VpnTunnelRuntime(
            vpnHost = host,
            appSettingsRepository = dependencies.appSettingsRepository,
            proxyGroupRepository = dependencies.proxyGroupRepository,
            tun2SocksBridgeFactory = dependencies.tun2SocksBridgeFactory,
            vpnTunnelSessionProvider = dependencies.vpnTunnelSessionProvider,
            protectPath = protectSocketServer.socketPath,
            // Jail the TUN egress strategy loader to the app's absolute lua dir
            // instead of "." — both the protect socket and the lua dir live
            // directly under <filesDir>, so derive it from the socket's parent
            // ("lua" mirrors LuaAssetManager's target directory name).
            luaScriptBaseDir = File(File(protectSocketServer.socketPath).parentFile, "lua").absolutePath,
            rootHelperSocketPathProvider = { rootHelperManager.socketPath },
            flowAttributionBridge = flowAttributionBridge,
            geositeDbPath = geositeDbPath,
            appliedNetworkReceiptStore = appliedNetworkReceiptStore,
            pcapCaptureRuntimeController = pcapCaptureRuntimeController,
            callbacks =
                VpnTunnelRuntimeCallbacks(
                    onTunnelReady = {
                        if (recoveryServiceInstanceId != null) {
                            recoveryGenerationProvider()?.let { generation ->
                                recoveryReceiptCollector?.recordTunReady(generation)
                            }
                        }
                    },
                    onTunnelTelemetry = { telemetry ->
                        if (recoveryServiceInstanceId != null) {
                            recoveryGenerationProvider()?.let { generation ->
                                recoveryReceiptCollector?.recordTunnelTelemetry(generation, telemetry)
                            }
                        }
                    },
                ),
        )

    @Provides
    @ServiceSessionScope
    fun provideVpnEncryptedDnsFailoverController(
        runtimeDependencies: VpnServiceRuntimeRuntimeDependencies,
        statusDependencies: VpnServiceRuntimeStatusDependencies,
    ): VpnEncryptedDnsFailoverController =
        VpnEncryptedDnsFailoverController(
            resolverOverrideStore = runtimeDependencies.resolverOverrideStore,
            networkDnsPathPreferenceStore = runtimeDependencies.dnsDependencies.networkDnsPathPreferenceStore,
            networkDnsBlockedPathStore = runtimeDependencies.dnsDependencies.networkDnsBlockedPathStore,
            networkFingerprintProvider = statusDependencies.networkFingerprintProvider,
        )

    @Provides
    @ServiceSessionScope
    fun provideVpnUpstreamRelaySupervisor(
        host: VpnCoordinatorHost,
        factory: UpstreamRelaySupervisorFactory,
        dispatchers: AppCoroutineDispatchers,
    ): UpstreamRelaySupervisor =
        factory.create(
            scope = host.serviceScope,
            dispatcher = dispatchers.io,
            networkMode = RelayRuntimeNetworkMode.Vpn,
        )

    @Provides
    @ServiceSessionScope
    fun provideVpnWarpRuntimeSupervisor(
        host: VpnCoordinatorHost,
        factory: WarpRuntimeSupervisorFactory,
        dispatchers: AppCoroutineDispatchers,
    ): WarpRuntimeSupervisor = factory.create(scope = host.serviceScope, dispatcher = dispatchers.io)

    @Provides
    @ServiceSessionScope
    fun provideVpnAmneziaWgRuntimeSupervisor(
        host: VpnCoordinatorHost,
        factory: AmneziaWgRuntimeSupervisorFactory,
        dispatchers: AppCoroutineDispatchers,
    ): AmneziaWgRuntimeSupervisor = factory.create(scope = host.serviceScope, dispatcher = dispatchers.io)

    @Provides
    @ServiceSessionScope
    fun provideVpnProxyRuntimeSupervisor(
        host: VpnCoordinatorHost,
        factory: ProxyRuntimeSupervisorFactory,
        dependencies: VpnServiceRuntimeRuntimeDependencies,
        dispatchers: AppCoroutineDispatchers,
    ): ProxyRuntimeSupervisor =
        factory.create(
            scope = host.serviceScope,
            dispatcher = dispatchers.io,
            networkSnapshotProvider = dependencies.networkSnapshotProvider,
        )

    @Provides
    @ServiceSessionScope
    fun provideVpnStatusReporter(dependencies: VpnServiceRuntimeStatusDependencies): ServiceStatusReporter =
        dependencies.serviceStatusReporterFactory.create(
            mode = Mode.VPN,
            sender = Sender.VPN,
            serviceStateStore = dependencies.serviceStateStore,
            networkFingerprintProvider = dependencies.networkFingerprintProvider,
            telemetryFingerprintHasher = dependencies.telemetryFingerprintHasher,
        )

    @Provides
    @ServiceSessionScope
    fun provideXrayTunnelStartParamsHolder(): XrayTunnelStartParamsHolder = XrayTunnelStartParamsHolder()

    @Provides
    @ServiceSessionScope
    fun provideXrayRenderedConfigHolder(): XrayRenderedConfigHolder = XrayRenderedConfigHolder()

    @Provides
    @ServiceSessionScope
    fun provideVpnServiceXrayProtectController(
        vpnService: VpnService,
        protectFailureMonitor: VpnProtectFailureMonitor,
    ): VpnServiceXrayProtectController =
        VpnServiceXrayProtectController(
            // Direct-JNI protect: wrap VpnService.protect(int) directly (the
            // libXray DialerController.protectFd transport), reporting denials
            // through the SAME monitor the native ProtectSocketFdProtector uses.
            // See .claude/rules/vpnservice-protect-invariant.md.
            fdProtector = vpnService::protect,
            protectFailureMonitor = protectFailureMonitor,
        )

    @Provides
    @ServiceSessionScope
    fun provideXrayProviderSessionController(
        profileMutations: ProfileMutationCoordinator,
        selectionStore: XrayProviderSelectionStore,
        profileStore: DurableXrayProfileStore,
        xrayNativeBridge: XrayNativeBridge,
        vpnTunnelRuntime: VpnTunnelRuntime,
        protectController: VpnServiceXrayProtectController,
        startParamsHolder: XrayTunnelStartParamsHolder,
        renderedConfigHolder: XrayRenderedConfigHolder,
        probeCoordinator: XrayProviderProbeCoordinator,
    ): XrayProviderSessionController {
        val orchestrator =
            XrayProviderOrchestrator(
                xrayRuntimeFactory = { cfg -> RipDpiXrayRuntime(xrayNativeBridge, cfg) },
                tunnel =
                    XrayManagedTunnel(
                        vpnTunnelRuntime = vpnTunnelRuntime,
                        startParamsProvider = startParamsHolder::require,
                    ),
                protectController = protectController,
                // Synchronous provider reads the secret-bearing config the
                // controller staged just before start; cleared right after.
                renderedConfigProvider = { renderedConfigHolder.require() },
            )
        return XrayProviderSessionController(
            selectionStore = selectionStore,
            profileStore = profileStore,
            routeBuilder = XrayProviderRouteBuilder(profileStore, XrayConfigRenderer()),
            orchestrator = orchestrator,
            snapshotDeriver = XrayProviderSnapshotDeriver(),
            probeRunner = XrayProviderDiagnosticsProbeRunner(xrayNativeBridge),
            startParamsHolder = startParamsHolder,
            bridgeVersion = { runCatching { xrayNativeBridge.version() }.getOrNull() },
            bridgeListenerReady = { runCatching { xrayNativeBridge.listenerReady() }.getOrDefault(false) },
            bridgeIsAlive = { runCatching { xrayNativeBridge.isAlive() }.getOrDefault(false) },
            renderedConfigSink = { config ->
                renderedConfigHolder.current = config
                if (config != null) {
                    protectController.clearLastFailure()
                }
            },
            lastProtectFailureDetail = { protectController.lastFailureDetail },
            recoverPendingProfileMutations = profileMutations::recover,
            probeCoordinator = probeCoordinator,
        )
    }

    @Provides
    @ServiceSessionScope
    fun provideVpnCoordinatorServices(
        upstreamRelaySupervisor: UpstreamRelaySupervisor,
        warpRuntimeSupervisor: WarpRuntimeSupervisor,
        amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
        proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
        statusReporter: ServiceStatusReporter,
        directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer,
        rootHelperManager: RootHelperManager,
        xrayProviderSessionController: XrayProviderSessionController,
    ): VpnCoordinatorServices =
        VpnCoordinatorServices(
            upstreamRelaySupervisor,
            warpRuntimeSupervisor,
            amneziaWgRuntimeSupervisor,
            proxyRuntimeSupervisor,
            statusReporter,
            directPathPolicyTelemetryConsumer,
            rootHelperManager,
            xrayProviderSessionController,
        )

    @Provides
    @ServiceSessionScope
    fun provideVpnCoordinator(
        host: VpnCoordinatorHost,
        runtimeDependencies: VpnServiceRuntimeRuntimeDependencies,
        permissionWatchdog: PermissionWatchdog,
        vpnProtectFailureMonitor: VpnProtectFailureMonitor,
        vpnTunnelRuntime: VpnTunnelRuntime,
        encryptedDnsFailoverController: VpnEncryptedDnsFailoverController,
        services: VpnCoordinatorServices,
        initialRelayRacePolicy: Optional<InitialRelayRacePolicy> = Optional.empty(),
    ): VpnServiceRuntimeCoordinator =
        VpnServiceRuntimeCoordinator(
            vpnHost = host,
            connectionPolicyResolver = runtimeDependencies.connectionPolicyResolver,
            resolverOverrideStore = runtimeDependencies.resolverOverrideStore,
            serviceRuntimeRegistry = runtimeDependencies.serviceRuntimeRegistry,
            rememberedNetworkPolicyStore = runtimeDependencies.rememberedNetworkPolicyStore,
            networkHandoverMonitor = runtimeDependencies.networkHandoverMonitor,
            policyHandoverEventStore = runtimeDependencies.policyHandoverEventStore,
            permissionWatchdog = permissionWatchdog,
            vpnProtectFailureMonitor = vpnProtectFailureMonitor,
            vpnTunnelRuntime = vpnTunnelRuntime,
            resolverRefreshPlanner = runtimeDependencies.dnsDependencies.resolverRefreshPlanner,
            encryptedDnsFailoverController = encryptedDnsFailoverController,
            upstreamRelaySupervisor = services.upstreamRelaySupervisor,
            warpRuntimeSupervisor = services.warpRuntimeSupervisor,
            amneziaWgRuntimeSupervisor = services.amneziaWgRuntimeSupervisor,
            proxyRuntimeSupervisor = services.proxyRuntimeSupervisor,
            statusReporter = services.statusReporter,
            screenStateObserver = runtimeDependencies.screenStateObserver,
            directPathPolicyTelemetryConsumer = services.directPathPolicyTelemetryConsumer,
            rootHelperManager = services.rootHelperManager,
            xrayProviderSessionController = services.xrayProviderSessionController,
            initialRelayRacePolicy = initialRelayRacePolicy.orElse(null),
        )
}
