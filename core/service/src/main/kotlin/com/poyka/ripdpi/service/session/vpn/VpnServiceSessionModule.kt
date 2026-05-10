package com.poyka.ripdpi.service.session.vpn

import android.net.VpnService
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeCoordinator
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeRuntimeDependencies
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeStatusDependencies
import com.poyka.ripdpi.services.DirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.InMemoryVpnProtectFailureMonitor
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.ProxyRuntimeSupervisor
import com.poyka.ripdpi.services.ProxyRuntimeSupervisorFactory
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
import com.poyka.ripdpi.services.VpnTunnelRuntime
import com.poyka.ripdpi.services.WarpRuntimeSupervisor
import com.poyka.ripdpi.services.WarpRuntimeSupervisorFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.coroutines.Dispatchers
import java.io.File

@Module
@InstallIn(VpnServiceSessionComponent::class)
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
        host: VpnCoordinatorHost,
        dependencies: VpnServiceRuntimeRuntimeDependencies,
        protectSocketServer: VpnProtectSocketServer,
        rootHelperManager: RootHelperManager,
    ): VpnTunnelRuntime =
        VpnTunnelRuntime(
            vpnHost = host,
            appSettingsRepository = dependencies.appSettingsRepository,
            tun2SocksBridgeFactory = dependencies.tun2SocksBridgeFactory,
            vpnTunnelSessionProvider = dependencies.vpnTunnelSessionProvider,
            protectPath = protectSocketServer.socketPath,
            rootHelperSocketPathProvider = { rootHelperManager.socketPath },
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
    ): UpstreamRelaySupervisor = factory.create(scope = host.serviceScope, dispatcher = Dispatchers.IO)

    @Provides
    @ServiceSessionScope
    fun provideVpnWarpRuntimeSupervisor(
        host: VpnCoordinatorHost,
        factory: WarpRuntimeSupervisorFactory,
    ): WarpRuntimeSupervisor = factory.create(scope = host.serviceScope, dispatcher = Dispatchers.IO)

    @Provides
    @ServiceSessionScope
    fun provideVpnProxyRuntimeSupervisor(
        host: VpnCoordinatorHost,
        factory: ProxyRuntimeSupervisorFactory,
        dependencies: VpnServiceRuntimeRuntimeDependencies,
    ): ProxyRuntimeSupervisor =
        factory.create(
            scope = host.serviceScope,
            dispatcher = Dispatchers.IO,
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
    fun provideVpnCoordinator(
        host: VpnCoordinatorHost,
        runtimeDependencies: VpnServiceRuntimeRuntimeDependencies,
        permissionWatchdog: PermissionWatchdog,
        vpnProtectFailureMonitor: VpnProtectFailureMonitor,
        vpnTunnelRuntime: VpnTunnelRuntime,
        encryptedDnsFailoverController: VpnEncryptedDnsFailoverController,
        upstreamRelaySupervisor: UpstreamRelaySupervisor,
        warpRuntimeSupervisor: WarpRuntimeSupervisor,
        proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
        statusReporter: ServiceStatusReporter,
        directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer,
        rootHelperManager: RootHelperManager,
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
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
            statusReporter = statusReporter,
            screenStateObserver = runtimeDependencies.screenStateObserver,
            directPathPolicyTelemetryConsumer = directPathPolicyTelemetryConsumer,
            rootHelperManager = rootHelperManager,
        )
}
