package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings

/** Canonical actual resolver, proxy routing, trust roots and split-strict policy consumed by TUN. */
internal fun runtimeTunnelDnsMaterial(
    dns: ActiveDnsSettings,
    input: VpnTunnelConfigurationInput,
    forceTunnelDns: Boolean,
    splitPolicy: ValidatedSplitStrictDnsPolicy?,
): List<String> =
    connectionPolicyDnsMaterial(dns) +
        listOf(forceTunnelDns.toString()) +
        (if (dns.isEncrypted) listOf(input.settings.encryptedDnsTlsRootsPem) else emptyList()) +
        (
            splitPolicy?.let {
                listOf(
                    it.destinationRouting.canonicalDigest,
                    it.canonicalDigest,
                    it.underlayLeaseGeneration?.toString().orEmpty(),
                ) + it.directResolverCandidates + it.bootstrapPins
            } ?: emptyList()
        )

internal fun RuntimeTunnelReadyEvidence.dnsMaterial(): List<String> =
    runtimeTunnelDnsMaterial(resolverDns, configurationInput, forceTunnelDns, splitStrictDnsPolicy)
