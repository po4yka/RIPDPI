package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy

/** Captured before remembered policy, endpoint bootstrap, learned DNS or initial relay racing. */
internal class RequestedRuntimeConfiguration(
    val identity: RuntimeConfigurationIdentity,
    val selection: RuntimeConfigurationSelection,
    val dns: RuntimeConfigurationDns,
    val strategy: RuntimeConfigurationStrategy,
    val tunnelInput: VpnTunnelConfigurationInput,
    val frozenTransportMaterial: List<String>,
    val frozenDnsMaterial: List<String>,
    val frozenWarpMaterial: List<String>?,
    val warpReference: com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference?,
) {
    override fun toString(): String = "RequestedRuntimeConfiguration([REDACTED])"
}

internal fun RequestedRuntimeConfiguration.afterRuntimeProvisioning(
    patch: com.poyka.ripdpi.service.warp.RuntimeWarpProvisioningPatch,
    identities: RuntimeConfigurationIdentityFactory,
): RuntimeConfigurationIdentity? {
    val captured = frozenWarpMaterial ?: return null
    val capturedProfile =
        patch.profileId == tunnelInput.settings.warpProfileId &&
            patch.before.profileId == patch.profileId && patch.after.profileId == patch.profileId
    val credentialsMatch =
        identities.capture(captured, emptyList()).transport.matches(
            identities.capture(requestedWarpCredentialMaterial(patch.before), emptyList()).transport,
        )
    return if (capturedProfile && credentialsMatch) {
        identities.capture(frozenTransportMaterial + requestedWarpCredentialMaterial(patch.after), frozenDnsMaterial)
    } else {
        null
    }
}
