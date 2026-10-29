package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
