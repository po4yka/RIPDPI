package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.SelectorFailover
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorLiveVpnPayloadTest {
    @Test
    fun `a stale route or network cannot publish a verified reserve`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val policy = policy()
            val group =
                selectorTestGroup(
                    listOf(selectorTestMember("active"), selectorTestMember("reserve")),
                ).copy(failover = SelectorFailover("unused", 60, 0, policy))
            var now = 0L
            var mayCommit = false
            val runtime = runtimeConfiguration()
            val lease =
                DiagnosticsInPathRouteLease(
                    runtime.runtimeId,
                    1L,
                    1L,
                    "127.0.0.1",
                    1080,
                    DiagnosticsProxyCredentials("user", "password"),
                )
            val scope = selectorTestScope(group).copy(vpnPath = SelectorVpnPath(runtime, lease))
            var liveProbes = 0
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        assertEquals("reserve", member.id)
                        MemberProbeResult(20L)
                    },
                    object : SelectorProbeScopeProvider {
                        override suspend fun capture(group: ProxyGroup) = scope

                        override fun isCurrent(scope: SelectorProbeScope): Boolean = mayCommit
                    },
                    { now },
                    measureVpnPayload = { path, expected ->
                        assertEquals(runtime, path.configuration)
                        assertEquals(policy, expected)
                        liveProbes++
                        MemberProbeResult(null, reason = ProfileQualityReason.TransferFailed)
                    },
                )
            prober.runProbePass(group, "unused", 0)
            now += 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals(2, liveProbes)
            assertEquals("active", store.selectedProfileId("g").value)

            mayCommit = true
            now += 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)
        }

    private fun runtimeConfiguration(): AppliedRuntimeConfiguration {
        val selection = RuntimeConfigurationSelection("relay", selectorGroupId = "g", selectorMemberId = "active")
        return AppliedRuntimeConfiguration(
            runtimeId = "runtime",
            revision = 1L,
            appliedAt = 0L,
            mode = Mode.VPN,
            requestedSelection = selection,
            effectiveSelection = selection,
            dns = RuntimeConfigurationDns("plain_udp", "system"),
            strategy = RuntimeConfigurationStrategy(enabled = false),
            reason = RuntimeConfigurationApplyReason.InitialStart,
        )
    }
}
