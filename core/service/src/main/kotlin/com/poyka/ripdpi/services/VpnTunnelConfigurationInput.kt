package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.proto.AppSettings

/** Immutable per-attempt TUN input, captured before provider/relay start and never exported. */
internal class VpnTunnelConfigurationInput(
    val settings: AppSettings,
    packageRoutingRules: List<PackageRoutingRule>,
) {
    val packageRoutingRules: List<PackageRoutingRule> = packageRoutingRules.toList()

    fun withRequestedDns(requested: VpnTunnelConfigurationInput): VpnTunnelConfigurationInput =
        VpnTunnelConfigurationInput(
            settings.toBuilder().setEncryptedDnsTlsRootsPem(requested.settings.encryptedDnsTlsRootsPem).build(),
            packageRoutingRules,
        )

    override fun toString(): String = "VpnTunnelConfigurationInput([REDACTED])"
}

internal fun VpnTunnelRuntime.dnsOnlyConfigurationInput(
    requested: VpnTunnelConfigurationInput,
): VpnTunnelConfigurationInput = consumedConfiguration.withRequestedDns(requested)
