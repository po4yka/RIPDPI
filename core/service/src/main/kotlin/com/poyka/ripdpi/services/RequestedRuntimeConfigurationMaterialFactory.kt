package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson

internal class RequestedRuntimeConfigurationMaterialFactory(
    private val identities: RuntimeConfigurationIdentityFactory,
) {
    fun build(
        mode: Mode,
        settings: AppSettings,
        catalog: RuntimeConfigurationCatalogMaterial,
        tunnelInput: VpnTunnelConfigurationInput,
        groupSelection: RuntimeConfigurationSelection,
        transportPolicy: CapturedTransportPolicy,
    ): RequestedRuntimeConfiguration {
        val dns = settings.activeDnsSettings()
        val transportMaterial =
            transportPolicy.material +
                vpnConfigurationMaterial(mode, settings) + catalog.material +
                tunnelInput.packageRoutingRules.filter { mode == Mode.VPN }.map {
                    RipDpiEncodeDefaultsJson.encodeToString(PackageRoutingRule.serializer(), it)
                }
        val dnsMaterial =
            connectionPolicyDnsMaterial(dns) +
                if (dns.isEncrypted) listOf(settings.encryptedDnsTlsRootsPem) else emptyList()
        return transportPolicy.configuration(
            identity = identities.capture(transportMaterial + catalog.warpMaterial.orEmpty(), dnsMaterial),
            selection = groupSelection,
            dns = dns.runtimeDnsSummary(),
            strategy = settings.strategySummary(),
            tunnelInput = tunnelInput,
            transportMaterial = transportMaterial,
            dnsMaterial = dnsMaterial,
            warpMaterial = catalog.warpMaterial,
            warpReference = catalog.warpReference,
        )
    }
}
