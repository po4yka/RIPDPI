package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiDirectPathCapability
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderDnsSb
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.builtInEncryptedDnsPathCandidates
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.toActiveDnsSettings
import javax.inject.Inject
import javax.inject.Singleton

internal data class VpnDnsSelection(
    val activeDns: ActiveDnsSettings,
    val preferredPath: EncryptedDnsPathCandidate? = null,
    val rememberedVpnDnsPolicy: VpnDnsPolicyJson? = null,
)

@Singleton
internal class ConnectionPolicyDnsSelector
    @Inject
    constructor(
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val startupDnsProbe: VpnStartupDnsProbe,
    ) {
        suspend fun baselineSelection(
            mode: Mode,
            dnsResolution: EffectiveDnsResolution,
            networkScopeKey: String?,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): VpnDnsSelection =
            resolveVpnDnsSelection(
                mode = mode,
                baseDns = dnsResolution.activeDns,
                preferredPath =
                    resolvePreferredVpnDnsPath(
                        mode = mode,
                        dnsResolution = dnsResolution,
                        networkScopeKey = networkScopeKey,
                        directPathCapabilities = directPathCapabilities,
                    ),
            )

        fun rememberedSelection(
            mode: Mode,
            baselineSelection: VpnDnsSelection,
            rememberedVpnDnsPolicy: VpnDnsPolicyJson?,
            resolverOverride: TemporaryResolverOverride?,
        ): VpnDnsSelection =
            resolveVpnDnsSelection(
                mode = mode,
                baseDns = baselineSelection.activeDns,
                preferredPath = baselineSelection.preferredPath,
                rememberedVpnDnsPolicy = rememberedVpnDnsPolicy,
                resolverOverride = resolverOverride,
            )

        private suspend fun resolvePreferredVpnDnsPath(
            mode: Mode,
            dnsResolution: EffectiveDnsResolution,
            networkScopeKey: String?,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): EncryptedDnsPathCandidate? =
            if (mode == Mode.VPN && dnsResolution.override == null && networkScopeKey != null) {
                networkDnsPathPreferenceStore.getPreferredPath(networkScopeKey)
                    ?: derivePreferredVpnDnsPathFromDirectPathCapabilities(directPathCapabilities)
                    ?: startupDnsProbe.probeIfTampered(dnsResolution.activeDns.mode)
            } else {
                null
            }

        private fun derivePreferredVpnDnsPathFromDirectPathCapabilities(
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): EncryptedDnsPathCandidate? {
            val dnsModes =
                directPathCapabilities
                    .asSequence()
                    .filter { capability -> isResolvableHostnameAuthority(capability.authority) }
                    .map(RipDpiDirectPathCapability::dnsMode)
                    .filter { dnsMode -> dnsMode != DnsMode.SYSTEM }
                    .distinct()
                    .toList()
            val selectedMode = dnsModes.singleOrNull() ?: return null
            return when (selectedMode) {
                DnsMode.DOH_PRIMARY -> builtInDohCandidate(DnsProviderAdGuard)
                DnsMode.DOH_SECONDARY -> builtInDohCandidate(DnsProviderDnsSb)
                DnsMode.SYSTEM -> null
            }
        }
    }

@Suppress("ReturnCount")
internal fun resolveVpnDnsSelection(
    mode: Mode,
    baseDns: ActiveDnsSettings,
    preferredPath: EncryptedDnsPathCandidate? = null,
    rememberedVpnDnsPolicy: VpnDnsPolicyJson? = null,
    resolverOverride: TemporaryResolverOverride? = null,
): VpnDnsSelection {
    if (mode != Mode.VPN) {
        return VpnDnsSelection(activeDns = baseDns)
    }
    if (resolverOverride != null) {
        return VpnDnsSelection(activeDns = resolverOverride.toActiveDnsSettings())
    }
    if (preferredPath != null) {
        return VpnDnsSelection(
            activeDns = preferredPath.toActiveDnsSettings(),
            preferredPath = preferredPath,
        )
    }
    val rememberedDns = rememberedVpnDnsPolicy?.toActiveDnsSettings()
    return VpnDnsSelection(
        activeDns = rememberedDns ?: baseDns,
        rememberedVpnDnsPolicy = rememberedVpnDnsPolicy,
    )
}

private fun builtInDohCandidate(resolverId: String): EncryptedDnsPathCandidate? =
    builtInEncryptedDnsPathCandidates().firstOrNull { candidate ->
        candidate.resolverId == resolverId && candidate.protocol.equals("doh", ignoreCase = true)
    }

private fun isResolvableHostnameAuthority(authority: String): Boolean {
    val host =
        authority
            .substringBefore(':')
            .trim()
            .trimEnd('.')
    return host.isNotEmpty() && host.any(Char::isLetter)
}
