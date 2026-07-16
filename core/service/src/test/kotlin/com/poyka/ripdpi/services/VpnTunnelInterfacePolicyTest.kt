package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VpnTunnelInterfacePolicyTest {
    @Test
    fun signatureChangesWhenEffectiveAppRoutingPlanChanges() {
        val initial = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi", "com.example.a"))
        val updated = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi", "com.example.b"))

        assertNotEquals(signature(initial), signature(updated))
    }

    @Test
    fun signatureDistinguishesAllowlistAndDenylistPlans() {
        val packages = setOf("com.example.app")

        assertNotEquals(
            signature(VpnAppRoutingPlan.AllowOnly(packages)),
            signature(VpnAppRoutingPlan.Disallow(packages)),
        )
    }

    @Test
    fun signatureCanonicalizesEffectivePackageOrder() {
        val first = VpnAppRoutingPlan.AllowOnly(linkedSetOf("com.example.b", "com.example.a"))
        val second = VpnAppRoutingPlan.AllowOnly(linkedSetOf("com.example.a", "com.example.b"))

        assertEquals(signature(first), signature(second))
    }

    @Test
    fun signatureIgnoresRoutingMetadataWhenEffectivePlanIsUnchanged() {
        val initial = AppSettingsSerializer.defaultValue
        val updated =
            initial
                .toBuilder()
                .setSplitTunnelMode(SplitTunnelMode.Exclude)
                .addSplitTunnelPackages("com.example.app")
                .setAppRoutingPolicyMode("prompt")
                .addAppRoutingEnabledPresetIds("russian-mainstream")
                .build()
        val plan = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi"))

        assertEquals(signature(plan, initial), signature(plan, updated))
    }

    @Test
    fun signatureChangesWhenDhtMitigationChanges() {
        val initial =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDhtMitigationMode("off")
                .build()
        val updated = initial.toBuilder().setDhtMitigationMode("bypass").build()
        val plan = VpnAppRoutingPlan.Disallow(emptySet())

        assertNotEquals(signature(plan, initial), signature(plan, updated))
    }

    @Test
    fun signatureChangesWhenIpv6Changes() {
        val initial =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setIpv6Enable(false)
                .build()
        val updated = initial.toBuilder().setIpv6Enable(true).build()
        val plan = VpnAppRoutingPlan.Disallow(emptySet())

        assertNotEquals(signature(plan, initial), signature(plan, updated))
    }

    @Test
    fun signatureChangesWhenHttpProxyListenerChanges() {
        val plan = VpnAppRoutingPlan.Disallow(emptySet())

        assertNotEquals(
            signature(plan, httpProxyPort = null),
            signature(plan, httpProxyPort = 2080),
        )
    }

    private fun signature(
        plan: VpnAppRoutingPlan,
        settings: com.poyka.ripdpi.proto.AppSettings = AppSettingsSerializer.defaultValue,
        httpProxyPort: Int? = null,
    ): String = vpnTunnelInterfacePolicySignature(settings, plan, httpProxyPort)
}
