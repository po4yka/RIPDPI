package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences

internal class AppliedNativeVpnProxy(
    private val policy: ConnectionPolicyResolution,
    val start: ProxyRuntimeStartResult,
) {
    fun matches(resolution: ConnectionPolicyResolution): Boolean =
        resolution.samePhysicalRoute &&
            policy.physicalRouteSignature == resolution.physicalRouteSignature &&
            start.consumedUpstreams.isEmpty() &&
            policy.requestedConfiguration.frozenTransportMaterial ==
            resolution.requestedConfiguration.frozenTransportMaterial &&
            policy.requestedConfiguration.selection == resolution.requestedConfiguration.selection &&
            policy.proxyPreferences.handoverConfig() == resolution.proxyPreferences.handoverConfig() &&
            policy.destinationRoutingDigest == resolution.destinationRoutingDigest
}

private fun RipDpiProxyPreferences.handoverConfig(): String {
    val preferences =
        if (this is RipDpiProxyUIPreferences && !hostAutolearn.enabled && hostAutolearn.networkScopeKey != null) {
            withSessionOverrides(hostAutolearn = hostAutolearn.copy(networkScopeKey = null))
        } else {
            this
        }
    return preferences.toNativeConfigJson()
}

internal suspend fun VpnTunnelRuntime.requiresHandoverRefresh(
    session: VpnRuntimeSession,
    resolution: ConnectionPolicyResolution,
): Boolean {
    val applied = consumedConfiguration
    val requested = resolution.requestedConfiguration.tunnelInput
    return applied.settings != requested.settings || applied.packageRoutingRules != requested.packageRoutingRules ||
        session.currentDns != resolution.activeDns ||
        currentDnsSignature != resolution.vpnDnsSignature() ||
        requiresInterfacePolicyRebuild()
}

internal fun ConnectionPolicyResolution.vpnDnsSignature(): String =
    dnsSignature(
        activeDns,
        resolverFallbackReason,
        splitStrictDnsPolicy?.canonicalDigest.orEmpty(),
        splitStrictDnsPolicy?.underlayLeaseGeneration,
    )
