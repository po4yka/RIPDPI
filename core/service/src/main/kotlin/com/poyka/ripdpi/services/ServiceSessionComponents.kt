package com.poyka.ripdpi.services

import android.net.VpnService
import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.hilt.DefineComponent
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Scope
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention.RUNTIME

@Scope
@Retention(RUNTIME)
internal annotation class ServiceSessionScope

@ServiceSessionScope
@DefineComponent(parent = SingletonComponent::class)
internal interface ProxyServiceSessionComponent

@DefineComponent.Builder
internal interface ProxyServiceSessionComponentBuilder {
    fun host(
        @BindsInstance host: ServiceCoordinatorHost,
    ): ProxyServiceSessionComponentBuilder

    fun build(): ProxyServiceSessionComponent
}

@EntryPoint
@InstallIn(ProxyServiceSessionComponent::class)
internal interface ProxyServiceSessionEntryPoint {
    fun coordinator(): ProxyServiceRuntimeCoordinator
}

@ServiceSessionScope
@DefineComponent(parent = SingletonComponent::class)
internal interface VpnServiceSessionComponent

@DefineComponent.Builder
internal interface VpnServiceSessionComponentBuilder {
    fun host(
        @BindsInstance host: VpnCoordinatorHost,
    ): VpnServiceSessionComponentBuilder

    fun vpnService(
        @BindsInstance vpnService: VpnService,
    ): VpnServiceSessionComponentBuilder

    fun build(): VpnServiceSessionComponent
}

@EntryPoint
@InstallIn(VpnServiceSessionComponent::class)
internal interface VpnServiceSessionEntryPoint {
    fun coordinator(): VpnServiceRuntimeCoordinator

    fun protectSocketServer(): VpnProtectSocketServer
}

@Scope
@Retention(RUNTIME)
internal annotation class BootstrapProxySessionScope

@BootstrapProxySessionScope
@DefineComponent(parent = SingletonComponent::class)
internal interface BootstrapProxySessionComponent

@DefineComponent.Builder
internal interface BootstrapProxySessionComponentBuilder {
    fun sessionScope(
        @BindsInstance sessionScope: CoroutineScope,
    ): BootstrapProxySessionComponentBuilder

    fun build(): BootstrapProxySessionComponent
}

@EntryPoint
@InstallIn(BootstrapProxySessionComponent::class)
internal interface BootstrapProxySessionEntryPoint {
    fun proxyRuntimeSupervisor(): ProxyRuntimeSupervisor
}

internal interface BootstrapProxyRuntimeSupervisorSessionFactory {
    fun create(scope: CoroutineScope): ProxyRuntimeSupervisor
}

@Singleton
internal class DefaultBootstrapProxyRuntimeSupervisorSessionFactory
    @Inject
    constructor(
        private val componentBuilderProvider: Provider<BootstrapProxySessionComponentBuilder>,
    ) : BootstrapProxyRuntimeSupervisorSessionFactory {
        override fun create(scope: CoroutineScope): ProxyRuntimeSupervisor {
            val component = componentBuilderProvider.get().sessionScope(scope).build()
            return EntryPoints.get(component, BootstrapProxySessionEntryPoint::class.java).proxyRuntimeSupervisor()
        }
    }

@Module
@InstallIn(ProxyServiceSessionComponent::class)
internal object ProxyServiceSessionModule {
    @Provides
    @ServiceSessionScope
    fun provideUpstreamRelaySupervisor(
        host: ServiceCoordinatorHost,
        factory: UpstreamRelaySupervisorFactory,
    ): UpstreamRelaySupervisor = factory.create(scope = host.serviceScope, dispatcher = Dispatchers.IO)

    @Provides
    @ServiceSessionScope
    fun provideWarpRuntimeSupervisor(
        host: ServiceCoordinatorHost,
        factory: WarpRuntimeSupervisorFactory,
    ): WarpRuntimeSupervisor = factory.create(scope = host.serviceScope, dispatcher = Dispatchers.IO)

    @Provides
    @ServiceSessionScope
    fun provideProxyRuntimeSupervisor(
        host: ServiceCoordinatorHost,
        factory: ProxyRuntimeSupervisorFactory,
        networkSnapshotProvider: com.poyka.ripdpi.data.NativeNetworkSnapshotProvider,
    ): ProxyRuntimeSupervisor =
        factory.create(
            scope = host.serviceScope,
            dispatcher = Dispatchers.IO,
            networkSnapshotProvider = networkSnapshotProvider,
        )

    @Provides
    @ServiceSessionScope
    fun provideProxyStatusReporter(
        serviceStateStore: com.poyka.ripdpi.data.ServiceStateStore,
        networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider,
        telemetryFingerprintHasher: TelemetryFingerprintHasher,
        factory: ServiceStatusReporterFactory,
    ): ServiceStatusReporter =
        factory.create(
            mode = com.poyka.ripdpi.data.Mode.Proxy,
            sender = com.poyka.ripdpi.data.Sender.Proxy,
            serviceStateStore = serviceStateStore,
            networkFingerprintProvider = networkFingerprintProvider,
            telemetryFingerprintHasher = telemetryFingerprintHasher,
        )

    @Provides
    @ServiceSessionScope
    fun provideProxyCoordinator(
        host: ServiceCoordinatorHost,
        connectionPolicyResolver: ConnectionPolicyResolver,
        serviceRuntimeRegistry: ServiceRuntimeRegistry,
        rememberedNetworkPolicyStore: com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore,
        networkHandoverMonitor: NetworkHandoverMonitor,
        policyHandoverEventStore: com.poyka.ripdpi.data.PolicyHandoverEventStore,
        permissionWatchdog: PermissionWatchdog,
        upstreamRelaySupervisor: UpstreamRelaySupervisor,
        warpRuntimeSupervisor: WarpRuntimeSupervisor,
        proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
        statusReporter: ServiceStatusReporter,
        screenStateObserver: ScreenStateObserver,
    ): ProxyServiceRuntimeCoordinator =
        ProxyServiceRuntimeCoordinator(
            host = host,
            connectionPolicyResolver = connectionPolicyResolver,
            serviceRuntimeRegistry = serviceRuntimeRegistry,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            networkHandoverMonitor = networkHandoverMonitor,
            policyHandoverEventStore = policyHandoverEventStore,
            permissionWatchdog = permissionWatchdog,
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
            statusReporter = statusReporter,
            screenStateObserver = screenStateObserver,
        )
}

@Module
@InstallIn(VpnServiceSessionComponent::class)
internal object VpnServiceSessionModule {
    @Provides
    @ServiceSessionScope
    fun provideVpnProtectSocketServer(vpnService: VpnService): VpnProtectSocketServer =
        VpnProtectSocketServer(
            vpnService = vpnService,
            socketPath = File(vpnService.filesDir, "protect_path").absolutePath,
        )

    @Provides
    @ServiceSessionScope
    fun provideVpnTunnelRuntime(
        host: VpnCoordinatorHost,
        dependencies: VpnServiceRuntimeRuntimeDependencies,
    ): VpnTunnelRuntime =
        VpnTunnelRuntime(
            vpnHost = host,
            appSettingsRepository = dependencies.appSettingsRepository,
            tun2SocksBridgeFactory = dependencies.tun2SocksBridgeFactory,
            vpnTunnelSessionProvider = dependencies.vpnTunnelSessionProvider,
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
            mode = com.poyka.ripdpi.data.Mode.VPN,
            sender = com.poyka.ripdpi.data.Sender.VPN,
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
        vpnTunnelRuntime: VpnTunnelRuntime,
        encryptedDnsFailoverController: VpnEncryptedDnsFailoverController,
        upstreamRelaySupervisor: UpstreamRelaySupervisor,
        warpRuntimeSupervisor: WarpRuntimeSupervisor,
        proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
        statusReporter: ServiceStatusReporter,
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
            vpnTunnelRuntime = vpnTunnelRuntime,
            resolverRefreshPlanner = runtimeDependencies.dnsDependencies.resolverRefreshPlanner,
            encryptedDnsFailoverController = encryptedDnsFailoverController,
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
            statusReporter = statusReporter,
            screenStateObserver = runtimeDependencies.screenStateObserver,
        )
}

@Module
@InstallIn(BootstrapProxySessionComponent::class)
internal object BootstrapProxySessionModule {
    @Provides
    @BootstrapProxySessionScope
    fun provideBootstrapProxyRuntimeSupervisor(
        sessionScope: CoroutineScope,
        factory: ProxyRuntimeSupervisorFactory,
        networkSnapshotProvider: com.poyka.ripdpi.data.NativeNetworkSnapshotProvider,
    ): ProxyRuntimeSupervisor =
        factory.create(
            scope = sessionScope,
            dispatcher = Dispatchers.IO,
            networkSnapshotProvider = networkSnapshotProvider,
        )
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BootstrapProxyRuntimeSupervisorSessionFactoryModule {
    @Binds
    abstract fun bindBootstrapProxyRuntimeSupervisorSessionFactory(
        factory: DefaultBootstrapProxyRuntimeSupervisorSessionFactory,
    ): BootstrapProxyRuntimeSupervisorSessionFactory
}
