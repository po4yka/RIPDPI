package com.poyka.ripdpi.service.runtime.vpn

import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisorFactory
import com.poyka.ripdpi.services.ConnectionPolicyResolver
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import com.poyka.ripdpi.services.ProxyRuntimeSupervisorFactory
import com.poyka.ripdpi.services.ScreenStateObserver
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.ServiceStatusReporterFactory
import com.poyka.ripdpi.services.TelemetryFingerprintHasher
import com.poyka.ripdpi.services.UpstreamRelaySupervisorFactory
import com.poyka.ripdpi.services.VpnResolverRefreshPlanner
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.WarpRuntimeSupervisorFactory
import javax.inject.Inject

@Suppress("LongParameterList")
internal class VpnServiceRuntimeRuntimeDependencies
    @Inject
    constructor(
        val appSettingsRepository: AppSettingsRepository,
        val proxyGroupRepository: ProxyGroupRepository,
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
        val amneziaWgRuntimeSupervisorFactory: AmneziaWgRuntimeSupervisorFactory,
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
