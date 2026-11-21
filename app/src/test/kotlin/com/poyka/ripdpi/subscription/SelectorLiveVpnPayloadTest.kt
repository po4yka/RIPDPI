package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PayloadFailoverPolicy
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
            val group = group(policy)
            var now = 0L
            var mayCommit = false
            val runtime = runtimeConfiguration("active")
            val scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtime))
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

    @Test
    fun `an unmeasurable applied route keeps the failure streak`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group = group(policy())
            var now = 0L
            val scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtimeConfiguration("active")))
            var routeMeasurable = true
            val prober =
                SelectorUrltestProber(
                    store,
                    { _, _ -> MemberProbeResult(20L) },
                    { scope },
                    { now },
                    measureVpnPayload = { _, _ ->
                        if (routeMeasurable) {
                            MemberProbeResult(
                                null,
                                reason = ProfileQualityReason.TransferFailed,
                            )
                        } else {
                            null
                        }
                    },
                )
            prober.runProbePass(group, "unused", 0)
            now += 60_000
            routeMeasurable = false
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.selectedProfileId("g").value)

            // The second failure, after the skipped pass, reaches the threshold of two.
            now += 60_000
            routeMeasurable = true
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)
        }

    @Test
    fun `the reload that applies a reserve keeps the failover cooldown`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group = group(policy().copy(cooldownSeconds = 600))
            var now = 0L
            var scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtimeConfiguration("active")))
            val prober =
                SelectorUrltestProber(
                    store,
                    { _, _ -> MemberProbeResult(20L) },
                    { scope },
                    { now },
                    measureVpnPayload = {
                        _,
                        _,
                        ->
                        MemberProbeResult(null, reason = ProfileQualityReason.TransferFailed)
                    },
                )
            prober.runProbePass(group, "unused", 0)
            now += 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)

            // The VPN reloads with the reserve, which then stalls too while the old primary recovers.
            scope = scope.copy(vpnPath = vpnPath(runtimeConfiguration("reserve", revision = 2L)))
            repeat(3) {
                now += 60_000
                prober.runProbePass(group, "unused", 0)
            }
            assertEquals("reserve", store.selectedProfileId("g").value)

            now += 600_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.selectedProfileId("g").value)
        }

    private fun group(policy: PayloadFailoverPolicy) =
        selectorTestGroup(
            listOf(selectorTestMember("active"), selectorTestMember("reserve")),
        ).copy(failover = SelectorFailover("unused", 60, 0, policy))

    private fun vpnPath(runtime: AppliedRuntimeConfiguration) =
        SelectorVpnPath(
            runtime,
            DiagnosticsInPathRouteLease(
                runtime.runtimeId,
                runtime.revision,
                1L,
                "127.0.0.1",
                1080,
                DiagnosticsProxyCredentials("user", "password"),
            ),
        )

    private fun runtimeConfiguration(
        member: String,
        revision: Long = 1L,
    ): AppliedRuntimeConfiguration {
        val selection = RuntimeConfigurationSelection("relay", selectorGroupId = "g", selectorMemberId = member)
        return AppliedRuntimeConfiguration(
            runtimeId = "runtime",
            revision = revision,
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
