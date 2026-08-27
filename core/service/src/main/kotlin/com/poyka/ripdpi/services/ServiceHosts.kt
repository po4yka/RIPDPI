package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal interface ServiceHost {
    val serviceScope: CoroutineScope
    val backgroundDispatcher: CoroutineDispatcher

    fun startForegroundService()

    fun stopService(startId: Int?)
}

internal interface ProxyServiceHost : ServiceHost {
    fun updateRunningNotification(
        startedAt: Long,
        proxyTelemetry: NativeRuntimeSnapshot,
    )
}

interface VpnTunnelBuilder {
    fun establish(): VpnTunnelSession?
}

interface VpnTunnelBuilderHost {
    suspend fun createTunnelBuilder(
        dns: String,
        ipv6: Boolean,
        appRoutingPlan: VpnAppRoutingPlan,
        interfaceSettings: AppSettings,
        httpProxyPort: Int? = null,
        networkParameters: VpnTunnelNetworkParameters = currentTunnelNetworkParameters(),
        profileInterface: VpnProfileInterface? = null,
    ): VpnTunnelBuilder

    fun currentTunnelNetworkParameters(): VpnTunnelNetworkParameters = VpnTunnelNetworkParameters()

    fun currentInstalledPackages(): Set<String> = emptySet()

    fun observeInstalledPackages(): Flow<Set<String>> = flowOf(currentInstalledPackages())

    suspend fun resolveAppRoutingPlan(
        settings: AppSettings,
        packageRoutingRules: Collection<PackageRoutingRule>,
        installedPackages: Set<String>,
    ): VpnAppRoutingPlan
}

internal interface VpnServiceHost :
    ServiceHost,
    VpnTunnelBuilderHost {
    fun updateRunningNotification(
        startedAt: Long,
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    )

    fun prepareDirectDnsUnderlay(
        candidates: List<String>,
        leaseGeneration: Long?,
    ): Long = 0L

    fun finishDirectDnsUnderlay(
        token: Long,
        action: DirectDnsUnderlayAction,
    ): Boolean = false
}
