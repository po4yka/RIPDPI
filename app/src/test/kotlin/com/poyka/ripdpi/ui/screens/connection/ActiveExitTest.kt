package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.ui.screens.awg.AwgPeerTestKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveExitTest {
    private val member = ProxyProfile.Hysteria2("member-2", "Home Hysteria", "group-1", "relay.example", 443, "secret")
    private val groups =
        listOf(
            ProxyGroup(
                "group-1",
                "Subscription",
                ProxyGroupType.SUBSCRIPTION,
                0,
                isSelector = true,
                members = listOf(member),
            ),
        )
    private val awg =
        SavedAwgProfile(
            "awg-1",
            "Office AWG",
            AwgActivationRequest(
                profileId = "awg-1",
                privateKey = "",
                peerPublicKey = AwgPeerTestKey,
                endpointHost = "vpn.example.com",
                endpointPort = 51820,
                interfaceAddressV4 = "10.8.0.2/32",
            ),
        )

    @Test
    fun `selector member is named by its saved name and kind`() {
        val requested = RuntimeConfigurationSelection("native", null, "hysteria2", "member-2", "group-1", "member-2")
        val effective = RuntimeConfigurationSelection("native", relayKind = "hysteria2", profileId = "member-2")

        assertEquals(
            ActiveExit("Home Hysteria", "Hysteria2"),
            applied(requested, effective).activeExit(groups, listOf(awg)),
        )
    }

    @Test
    fun `amneziawg profile is named by its saved name`() {
        val selection = RuntimeConfigurationSelection("native", "udp", "amneziawg", "awg-1")

        assertEquals(
            ActiveExit("Office AWG", "AmneziaWG"),
            applied(selection, selection).activeExit(groups, listOf(awg)),
        )
    }

    @Test
    fun `stopped runtime and a plain relay have no exit`() {
        val relay = RuntimeConfigurationSelection("native", relayKind = "vless", profileId = "relay-1")

        assertNull(RuntimeConfigurationApplication.Unknown.activeExit(groups, listOf(awg)))
        assertNull(applied(relay, relay).activeExit(groups, listOf(awg)))
    }

    private fun applied(
        requested: RuntimeConfigurationSelection,
        effective: RuntimeConfigurationSelection,
    ) = RuntimeConfigurationApplication.Applied(
        AppliedRuntimeConfiguration(
            runtimeId = "runtime",
            revision = 1,
            appliedAt = 1,
            mode = Mode.VPN,
            requestedSelection = requested,
            effectiveSelection = effective,
            dns = RuntimeConfigurationDns("plain", "system"),
            strategy = RuntimeConfigurationStrategy(false),
            reason = RuntimeConfigurationApplyReason.InitialStart,
        ),
    )
}
