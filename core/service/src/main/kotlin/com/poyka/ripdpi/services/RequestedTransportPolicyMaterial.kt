package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings

/** Captured proxy and relay policy facet used unchanged in the requested identity. */
internal fun requestedTransportPolicyMaterial(
    mode: Mode,
    settings: AppSettings,
    policy: RequestedRuntimePolicy,
    provider: String,
    relayInputs: RelayResolutionInputs,
): List<String> =
    (
        if (provider == "xray") {
            listOf(mode.preferenceValue)
        } else {
            connectionPolicyTransportMaterial(mode, policy.preferences, policy.destinationDigest) +
                listOf(settings.strategyChainYaml)
        }
    ) + relayInputs.identityMaterial()

/** Keeps the exact engine inputs paired with their canonical policy material through assembly. */
internal class CapturedTransportPolicy(
    private val inputs: RelayResolutionInputs,
    val material: List<String>,
) {
    fun configuration(
        identity: RuntimeConfigurationIdentity,
        selection: com.poyka.ripdpi.data.RuntimeConfigurationSelection,
        dns: com.poyka.ripdpi.data.RuntimeConfigurationDns,
        strategy: com.poyka.ripdpi.data.RuntimeConfigurationStrategy,
        tunnelInput: VpnTunnelConfigurationInput,
        transportMaterial: List<String>,
        dnsMaterial: List<String>,
        warpMaterial: List<String>?,
        warpReference: com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference?,
    ) = RequestedRuntimeConfiguration(
        identity,
        inputs,
        selection,
        dns,
        strategy,
        tunnelInput,
        transportMaterial,
        dnsMaterial,
        warpMaterial,
        warpReference,
    )
}
