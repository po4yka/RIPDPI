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

    @Test
    fun signatureChangesWhenSplitTunnelModeChanges() {
        val initial =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setSplitTunnelMode(SplitTunnelMode.Off)
                .build()
        val updated = initial.toBuilder().setSplitTunnelMode(SplitTunnelMode.Exclude).build()

        assertNotEquals(vpnTunnelInterfacePolicySignature(initial), vpnTunnelInterfacePolicySignature(updated))
    }

    @Test
    fun signatureChangesWhenSplitTunnelPackageSetChanges() {
        val initial =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setSplitTunnelMode(SplitTunnelMode.Exclude)
                .addSplitTunnelPackages("com.example.first")
                .build()
        val updated = initial.toBuilder().addSplitTunnelPackages("com.example.second").build()

        assertNotEquals(vpnTunnelInterfacePolicySignature(initial), vpnTunnelInterfacePolicySignature(updated))
    }

    @Test
    fun signatureCanonicalizesSplitTunnelSettings() {
        val first =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setSplitTunnelMode(SplitTunnelMode.Exclude)
                .addSplitTunnelPackages("com.example.second")
                .addSplitTunnelPackages("")
                .addSplitTunnelPackages("com.example.first")
                .addSplitTunnelPackages("com.example.first")
                .build()
        val second =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setSplitTunnelMode(SplitTunnelMode.Exclude)
                .addSplitTunnelPackages("com.example.first")
                .addSplitTunnelPackages("com.example.second")
                .build()

        assertEquals(vpnTunnelInterfacePolicySignature(first), vpnTunnelInterfacePolicySignature(second))
    }

    @Test
    fun signatureDistinguishesNonCanonicalSplitTunnelValues() {
        val nonCanonicalMode =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setSplitTunnelMode("EXCLUDE")
                .build()
        val canonicalMode = nonCanonicalMode.toBuilder().setSplitTunnelMode(SplitTunnelMode.Exclude).build()
        val paddedPackage =
            canonicalMode
                .toBuilder()
                .addSplitTunnelPackages(" com.example.app ")
                .build()
        val canonicalPackage =
            canonicalMode
                .toBuilder()
                .addSplitTunnelPackages("com.example.app")
                .build()

        assertNotEquals(
            vpnTunnelInterfacePolicySignature(nonCanonicalMode),
            vpnTunnelInterfacePolicySignature(canonicalMode),
        )
        assertNotEquals(
            vpnTunnelInterfacePolicySignature(paddedPackage),
            vpnTunnelInterfacePolicySignature(canonicalPackage),
        )
    }

    @Test
    fun signatureIgnoresSplitTunnelPackagesWhenModeIsOff() {
        val first =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setSplitTunnelMode(SplitTunnelMode.Off)
                .build()
        val second = first.toBuilder().addSplitTunnelPackages("com.example.app").build()

        assertEquals(vpnTunnelInterfacePolicySignature(first), vpnTunnelInterfacePolicySignature(second))
    }

    @Test
    fun signatureIgnoresSplitTunnelSettingsWhenFullTunnelWins() {
        val first =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setFullTunnelMode(true)
                .setSplitTunnelMode(SplitTunnelMode.Off)
                .build()
        val second =
            first
                .toBuilder()
                .setSplitTunnelMode(SplitTunnelMode.Include)
                .addSplitTunnelPackages("com.example.app")
                .build()

        assertEquals(vpnTunnelInterfacePolicySignature(first), vpnTunnelInterfacePolicySignature(second))
    }
}
