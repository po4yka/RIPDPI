package com.poyka.ripdpi.service.session.proxy

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.service.runtime.proxy.ProxyRuntimeSupervisorBundle
import com.poyka.ripdpi.service.runtime.proxy.ProxyServiceRuntimeCoordinator
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisor
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisorFactory
import com.poyka.ripdpi.services.AutolearnActivationReceiptPublisher
import com.poyka.ripdpi.services.ConnectionPolicyResolver
import com.poyka.ripdpi.services.DirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.ProxyRuntimeSupervisor
import com.poyka.ripdpi.services.ProxyRuntimeSupervisorFactory
import com.poyka.ripdpi.services.ProxyServiceSessionComponent
import com.poyka.ripdpi.services.RelayRuntimeNetworkMode
import com.poyka.ripdpi.services.RootHelperManager
import com.poyka.ripdpi.services.ScreenStateObserver
import com.poyka.ripdpi.services.ServiceCoordinatorHost
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.ServiceSessionScope
import com.poyka.ripdpi.services.ServiceStatusReporter
import com.poyka.ripdpi.services.ServiceStatusReporterFactory
import com.poyka.ripdpi.services.TelemetryFingerprintHasher
import com.poyka.ripdpi.services.UpstreamRelaySupervisor
import com.poyka.ripdpi.services.UpstreamRelaySupervisorFactory
import com.poyka.ripdpi.services.WarpRuntimeSupervisor
import com.poyka.ripdpi.services.WarpRuntimeSupervisorFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn

@Module
@InstallIn(ProxyServiceSessionComponent::class)
internal object ProxyServiceSessionModule {
    @Provides
    @ServiceSessionScope
    fun provideUpstreamRelaySupervisor(
        host: ServiceCoordinatorHost,
        factory: UpstreamRelaySupervisorFactory,
        dispatchers: AppCoroutineDispatchers,
    ): UpstreamRelaySupervisor =
        factory.create(
            scope = host.serviceScope,
            dispatcher = dispatchers.io,
            networkMode = RelayRuntimeNetworkMode.Proxy,
        )

    @Provides
    @ServiceSessionScope
    fun provideWarpRuntimeSupervisor(
        host: ServiceCoordinatorHost,
        factory: WarpRuntimeSupervisorFactory,
        dispatchers: AppCoroutineDispatchers,
    ): WarpRuntimeSupervisor = factory.create(scope = host.serviceScope, dispatcher = dispatchers.io)

    @Provides
    @ServiceSessionScope
    fun provideProxyAmneziaWgRuntimeSupervisor(
        host: ServiceCoordinatorHost,
        factory: AmneziaWgRuntimeSupervisorFactory,
        dispatchers: AppCoroutineDispatchers,
    ): AmneziaWgRuntimeSupervisor = factory.create(scope = host.serviceScope, dispatcher = dispatchers.io)

    @Provides
    @ServiceSessionScope
    fun provideProxyRuntimeSupervisor(
        host: ServiceCoordinatorHost,
        factory: ProxyRuntimeSupervisorFactory,
        networkSnapshotProvider: NativeNetworkSnapshotProvider,
        dispatchers: AppCoroutineDispatchers,
    ): ProxyRuntimeSupervisor =
        factory.create(
            scope = host.serviceScope,
            dispatcher = dispatchers.io,
            networkSnapshotProvider = networkSnapshotProvider,
        )

    @Provides
    @ServiceSessionScope
    fun provideProxyStatusReporter(
        serviceStateStore: ServiceStateStore,
        networkFingerprintProvider: NetworkFingerprintProvider,
        telemetryFingerprintHasher: TelemetryFingerprintHasher,
        factory: ServiceStatusReporterFactory,
    ): ServiceStatusReporter =
        factory.create(
            mode = Mode.Proxy,
            sender = Sender.Proxy,
            serviceStateStore = serviceStateStore,
            networkFingerprintProvider = networkFingerprintProvider,
            telemetryFingerprintHasher = telemetryFingerprintHasher,
        )

    @Provides
    @ServiceSessionScope
    fun provideProxyRuntimeSupervisorBundle(
        upstreamRelaySupervisor: UpstreamRelaySupervisor,
        warpRuntimeSupervisor: WarpRuntimeSupervisor,
        amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
        proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    ): ProxyRuntimeSupervisorBundle =
        ProxyRuntimeSupervisorBundle(
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            amneziaWgRuntimeSupervisor = amneziaWgRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
        )

    @Provides
    @ServiceSessionScope
    fun provideProxyCoordinator(
        host: ServiceCoordinatorHost,
        connectionPolicyResolver: ConnectionPolicyResolver,
        serviceRuntimeRegistry: ServiceRuntimeRegistry,
        rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        networkHandoverMonitor: NetworkHandoverMonitor,
        policyHandoverEventStore: PolicyHandoverEventStore,
        permissionWatchdog: PermissionWatchdog,
        supervisors: ProxyRuntimeSupervisorBundle,
        autolearnActivationReceiptPublisher: AutolearnActivationReceiptPublisher,
        statusReporter: ServiceStatusReporter,
        screenStateObserver: ScreenStateObserver,
        directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer,
        rootHelperManager: RootHelperManager,
    ): ProxyServiceRuntimeCoordinator =
        ProxyServiceRuntimeCoordinator(
            host = host,
            connectionPolicyResolver = connectionPolicyResolver,
            serviceRuntimeRegistry = serviceRuntimeRegistry,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            networkHandoverMonitor = networkHandoverMonitor,
            policyHandoverEventStore = policyHandoverEventStore,
            permissionWatchdog = permissionWatchdog,
            supervisors = supervisors,
            autolearnActivationReceiptPublisher = autolearnActivationReceiptPublisher,
            statusReporter = statusReporter,
            screenStateObserver = screenStateObserver,
            directPathPolicyTelemetryConsumer = directPathPolicyTelemetryConsumer,
            rootHelperManager = rootHelperManager,
        )
}
