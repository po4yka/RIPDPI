package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VpnTunnelInterfacePolicyTest {
    @Test
    fun signatureChangesWhenAppRoutingPresetChanges() {
        val initial =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setAppRoutingPolicyMode("off")
                .clearAppRoutingEnabledPresetIds()
                .build()
        val updated =
            initial
                .toBuilder()
                .setAppRoutingPolicyMode("prompt")
                .addAppRoutingEnabledPresetIds("russian-mainstream")
                .build()

        assertNotEquals(vpnTunnelInterfacePolicySignature(initial), vpnTunnelInterfacePolicySignature(updated))
    }

    @Test
    fun signatureChangesWhenDhtMitigationChanges() {
        val initial =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDhtMitigationMode("off")
                .build()
        val updated =
            initial
                .toBuilder()
                .setDhtMitigationMode("bypass")
                .build()

        assertNotEquals(vpnTunnelInterfacePolicySignature(initial), vpnTunnelInterfacePolicySignature(updated))
    }

    @Test
    fun signatureSortsAppRoutingPresetIds() {
        val first =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .addAppRoutingEnabledPresetIds("vk")
                .addAppRoutingEnabledPresetIds("russian-mainstream")
                .build()
        val second =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .addAppRoutingEnabledPresetIds("russian-mainstream")
                .addAppRoutingEnabledPresetIds("vk")
                .build()

        assertEquals(vpnTunnelInterfacePolicySignature(first), vpnTunnelInterfacePolicySignature(second))
    }
}
