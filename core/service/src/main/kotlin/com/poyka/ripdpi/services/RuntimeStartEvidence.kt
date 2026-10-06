package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeConfigurationSelection

/** Positive evidence returned only after provider/proxy readiness and, in VPN mode, TUN readiness. */
internal sealed interface RuntimeStartEvidence {
    open class ProxySnapshot(
        val snapshot: NativeRuntimeSnapshot,
        val effectivePreferences: RipDpiProxyPreferences,
        val consumedUpstreams: List<ConsumedUpstreamConfiguration>,
        val requestedWarpPatch: com.poyka.ripdpi.service.warp.RuntimeWarpProvisioningPatch?,
    ) : RuntimeStartEvidence {
        override fun toString(): String = "RuntimeStartEvidence.ProxySnapshot([REDACTED])"
    }

    class VpnSnapshot(
        proxy: ProxySnapshot,
        val tunnel: RuntimeTunnelReadyEvidence,
    ) : ProxySnapshot(
            proxy.snapshot,
            proxy.effectivePreferences,
            proxy.consumedUpstreams,
            proxy.requestedWarpPatch,
        ) {
        override fun toString(): String = "RuntimeStartEvidence.VpnSnapshot([REDACTED])"
    }

    class ProviderReady(
        val selection: RuntimeConfigurationSelection,
        val effectiveIdentity: RuntimeConfigurationIdentity,
        val tunnel: RuntimeTunnelReadyEvidence,
        val measurementProof: CandidateConfigurationProof?,
    ) : RuntimeStartEvidence {
        override fun toString(): String = "RuntimeStartEvidence.ProviderReady([REDACTED])"
    }
}

internal fun ProxyRuntimeStartResult.toRuntimeStartEvidence(): RuntimeStartEvidence.ProxySnapshot =
    RuntimeStartEvidence.ProxySnapshot(readySnapshot, effectivePreferences, consumedUpstreams, requestedWarpPatch)

/** Composes independently positive native proxy and TUN receipts into the VPN capability. */
internal fun readyNativeVpnEvidence(
    proxy: ProxyRuntimeStartResult,
    tunnel: RuntimeTunnelReadyEvidence,
): RuntimeStartEvidence = RuntimeStartEvidence.VpnSnapshot(proxy.toRuntimeStartEvidence(), tunnel)
