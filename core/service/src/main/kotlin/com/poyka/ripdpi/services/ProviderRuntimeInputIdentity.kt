package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson

/** Exact immutable provider and tunnel input; display preferences and log context are excluded. */
internal fun RuntimeConfigurationIdentityFactory.providerInput(
    rendered: String,
    profileId: String?,
    params: XrayTunnelStartParams,
): RuntimeConfigurationIdentity =
    capture(
        listOf("provider-xray", rendered, profileId.orEmpty()) +
            vpnConfigurationMaterial(Mode.VPN, params.configurationInput.settings) +
            params.configurationInput.packageRoutingRules.map {
                RipDpiEncodeDefaultsJson.encodeToString(PackageRoutingRule.serializer(), it)
            },
        runtimeTunnelDnsMaterial(
            vpnTunnelDnsPlan(params.activeDns, params.forceTunnelDns, params.splitStrictDnsPolicy).resolverDns,
            params.configurationInput,
            params.forceTunnelDns,
            params.splitStrictDnsPolicy,
        ),
    )

internal fun RuntimeConfigurationIdentityFactory.providerReadyInput(
    rendered: String,
    profileId: String?,
    ready: RuntimeTunnelReadyEvidence,
): RuntimeConfigurationIdentity =
    capture(
        listOf("provider-xray", rendered, profileId.orEmpty()) +
            vpnConfigurationMaterial(Mode.VPN, ready.configurationInput.settings) +
            listOf(ready.interfacePolicySignature) +
            ready.configurationInput.packageRoutingRules.map {
                RipDpiEncodeDefaultsJson.encodeToString(PackageRoutingRule.serializer(), it)
            },
        ready.dnsMaterial(),
    )

/** A provider no-op is valid only while its consumed payload and live tunnel facets still match. */
internal fun RuntimeConfigurationIdentityFactory.isCurrentProviderInput(
    incoming: RuntimeConfigurationIdentity,
    consumed: RuntimeConfigurationIdentity?,
    tunnelReady: () -> RuntimeTunnelReadyEvidence,
): Boolean {
    if (consumed?.matches(incoming) != true) return false
    return try {
        capture(emptyList(), tunnelReady().dnsMaterial()).dns.matches(incoming.dns)
    } catch (_: IllegalStateException) {
        false
    }
}

internal fun RuntimeConfigurationIdentityFactory.providerReadyConfiguration(
    selected: XraySelectedProfile,
    resolved: XrayProviderRouteBuilder.Result.Resolved,
    tunnel: RuntimeTunnelReadyEvidence,
): RuntimeStartEvidence.ProviderReady =
    RuntimeStartEvidence.ProviderReady(
        com.poyka.ripdpi.data.RuntimeConfigurationSelection(
            provider = "xray",
            profileId = selected.selection.activeProfileId,
            transport =
                selected.profile
                    ?.outbound
                    ?.network
                    ?.name
                    ?.lowercase(),
        ),
        providerReadyInput(resolved.renderedConfig, selected.selection.activeProfileId, tunnel),
        tunnel,
        CandidateConfigurationProofs.xray(resolved.renderedConfig),
    )
