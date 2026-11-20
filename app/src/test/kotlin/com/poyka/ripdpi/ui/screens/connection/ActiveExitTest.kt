package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileSelections
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.data.testPauseAuthority
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

    private val nothingSaved = SavedExitSelection(Mode.VPN, ProfileSelections(), null)
    private val savedMember = SavedExitSelection(Mode.VPN, ProfileSelections(selectorGroupId = "group-1"), "member-2")
    private val memberRequested =
        RuntimeConfigurationSelection(
            "native",
            null,
            "hysteria2",
            "member-2",
            "group-1",
            "member-2",
            "Home Hysteria",
            "Hysteria2",
        )
    private val memberEffective =
        RuntimeConfigurationSelection("native", relayKind = "hysteria2", profileId = "member-2")

    @Test
    fun `running selector member is named by its saved name and kind`() {
        assertEquals(
            ActiveExit("Home Hysteria", "Hysteria2"),
            exitOf(running(applied(memberRequested, memberEffective)), nothingSaved, groups, listOf(awg)),
        )
    }

    @Test
    fun `running amneziawg profile is named by its saved name`() {
        val selection = RuntimeConfigurationSelection("native", "udp", "amneziawg", "awg-1")

        assertEquals(
            ActiveExit("Office AWG", "AmneziaWG"),
            exitOf(running(applied(selection, selection)), nothingSaved, groups, listOf(awg)),
        )
    }

    @Test
    fun `a running direct session wins over a saved member`() {
        val direct = RuntimeConfigurationSelection("native")

        assertNull(exitOf(running(applied(direct, direct)), savedMember, groups, listOf(awg)))
    }

    @Test
    fun `a new session does not show the previous session's exit`() {
        val previous = applied(memberRequested, memberEffective).configuration
        val starting =
            RuntimeConfigurationApplication.Applying(
                RuntimeConfigurationAttempt(
                    runtimeId = "next-runtime",
                    revision = 1,
                    mode = Mode.VPN,
                    requestedSelection = memberRequested,
                    reason = RuntimeConfigurationApplyReason.InitialStart,
                    originalIntent = RuntimeAppliedIntent.Activation(testPauseAuthority().reserveStart(Mode.VPN)),
                    catalogGeneration = 1,
                ),
                previous,
            )

        assertNull(exitOf(running(starting), nothingSaved, groups, listOf(awg)))
    }

    @Test
    fun `a running member removed or renamed by a refresh keeps the exit its configuration applied`() {
        val renamed = listOf(groups.single().copy(members = listOf(member.copy(displayName = "Renamed"))))
        val session = running(applied(memberRequested, memberEffective))

        assertEquals(ActiveExit("Home Hysteria", "Hysteria2"), exitOf(session, nothingSaved, emptyList(), emptyList()))
        assertEquals(ActiveExit("Home Hysteria", "Hysteria2"), exitOf(session, nothingSaved, renamed, emptyList()))
    }

    @Test
    fun `a deleted profile has no exit`() {
        val deletedAwg = SavedExitSelection(Mode.VPN, ProfileSelections(awgProfileId = "gone"), null)
        assertNull(exitOf(emptyMap(), deletedAwg, groups, listOf(awg)))
    }

    @Test
    fun `a stopped service shows the saved selection the next start uses`() {
        assertEquals(ActiveExit("Home Hysteria", "Hysteria2"), exitOf(emptyMap(), savedMember, groups, listOf(awg)))
        val savedAwg = ProfileSelections(awgProfileId = "awg-1")
        assertEquals(
            ActiveExit("Office AWG", "AmneziaWG"),
            exitOf(stopped(), SavedExitSelection(Mode.VPN, savedAwg, null), groups, listOf(awg)),
        )
        // AmneziaWG runs only as a VPN, so a proxy start uses no exit.
        assertNull(exitOf(stopped(), SavedExitSelection(Mode.Proxy, savedAwg, null), groups, listOf(awg)))
    }

    @Test
    fun `a member without a remark is named by its kind, not its host`() {
        val unnamed = member.copy(displayName = "relay.example:443")
        val unnamedGroups = listOf(groups.single().copy(members = listOf(unnamed)))

        assertEquals(ActiveExit("Hysteria2", "Hysteria2"), exitOf(emptyMap(), savedMember, unnamedGroups, emptyList()))
    }

    private fun running(application: RuntimeConfigurationApplication) = mapOf(Mode.VPN to application)

    private fun stopped() = mapOf(Mode.VPN to RuntimeConfigurationApplication.Unknown)

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
