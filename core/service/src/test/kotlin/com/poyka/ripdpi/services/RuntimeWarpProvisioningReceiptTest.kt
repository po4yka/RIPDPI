package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference
import com.poyka.ripdpi.service.warp.RuntimeWarpProvisioningPatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWarpProvisioningReceiptTest {
    @Test
    fun `provisioning patches frozen credentials preserving DNS and transport`() {
        val factory = RuntimeConfigurationIdentityFactory()
        val before = WarpCredentials(profileId = "frozen-profile", deviceId = "device", accessToken = "old")
        val after = before.copy(accessToken = "provisioned-fixture")
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setWarpProfileId(before.profileId)
                .build()
        val transport = listOf("frozen-transport")
        val dns = listOf("frozen-dns")
        val captured =
            RequestedRuntimeConfiguration(
                factory.capture(transport + requestedWarpCredentialMaterial(before), dns),
                RuntimeConfigurationSelection("native", relayKind = "warp", profileId = before.profileId),
                settings.activeDnsSettings().runtimeDnsSummary(),
                settings.strategySummary(),
                VpnTunnelConfigurationInput(settings, emptyList()),
                transport,
                dns,
                requestedWarpCredentialMaterial(before),
                RequestedWarpRuntimeReference(before.profileId, 1),
            )
        val patch = RuntimeWarpProvisioningPatch(before.profileId, before, after)
        val reference = captured.afterRuntimeProvisioning(patch, factory)!!
        assertTrue(reference.matches(factory.capture(transport + requestedWarpCredentialMaterial(after), dns)))
        assertFalse(
            reference.matches(
                factory.capture(listOf("user-edited-transport") + requestedWarpCredentialMaterial(after), dns),
            ),
        )
        assertFalse(reference.matches(captured.identity))
        assertNull(
            captured.afterRuntimeProvisioning(
                RuntimeWarpProvisioningPatch("different-profile", before, after),
                factory,
            ),
        )
        assertNull(
            captured.afterRuntimeProvisioning(
                RuntimeWarpProvisioningPatch(
                    before.profileId,
                    before.copy(accessToken = "different-before-fixture"),
                    after,
                ),
                factory,
            ),
        )
        assertFalse(patch.toString().contains("provisioned"))
    }
}
