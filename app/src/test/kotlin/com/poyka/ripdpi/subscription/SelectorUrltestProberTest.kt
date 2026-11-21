package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.subscription.EndpointHealth
import com.poyka.ripdpi.data.subscription.EndpointHealthStatus
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (C) The urltest failover consumer. A selector group carrying a
 * [SelectorFailover] periodically probes each member's latency and re-pins the
 * selection onto a faster member when it beats the current by more than the
 * tolerance band. The clock is `runTest`'s virtual time and the network is a fake
 * probe, so no real waiting or sockets are involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectorUrltestProberTest {
    @Test
    fun `manual Cloudflare choice survives an in-flight automatic probe`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "origin")
            val group =
                selectorTestGroup(listOf(selectorTestMember("edge"), selectorTestMember("origin")))
                    .copy(cloudflareMemberIds = setOf("edge"))
            var calls = 0
            val prober =
                selectorTestProber(store) { _, _ ->
                    calls++
                    store.select("g", "edge")
                    MemberProbeResult(100L)
                }

            prober.runProbePass(group, "https://probe", 0)
            prober.runProbePass(group, "https://probe", 0)

            assertEquals("edge", store.selectedProfileId("g").value)
            assertEquals(1, calls)
        }

    @Test
    fun `explicit Cloudflare tag cannot win automatic selection even when fastest and default`() =
        runTest {
            val payload = """{"outbounds":[
            {"type":"selector","tag":"select","outbounds":["edge","origin","auto"],"default":"edge"},
            {"type":"urltest","tag":"auto","url":"https://probe.example","interval":"10s"},
            {"type":"trojan","tls":{"enabled":true},"tag":"edge","server":"edge.example","server_port":443,"password":"fixture"},
            {"type":"trojan","tls":{"enabled":true},"tag":"origin","server":"origin.example","server_port":443,"password":"fixture"}
            ],"ripdpi":{"schema_version":3,"cloudflare_outbound_tags":["edge"]}}"""
            val parsed = SelectorUrltestGroupImport.import(payload, "g") as SelectorUrltestImportResult.Success
            val group = requireNotNull(parsed.group)
            val store = ProbeTestSelectionStore()
            val prober =
                selectorTestProber(
                    store,
                    FixedMemberLatencyProbe(
                        parsed.profiles.associate { it.id to if (it.displayName == "edge") 1L else 100L },
                    ),
                )

            prober.runProbePass(group, "https://probe.example", 0)

            assertEquals(parsed.profiles.single { it.displayName == "origin" }.id, store.selectedProfileId("g").value)
        }

    @Test
    fun `resolver feature flag change fences a completed candidate probe`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            var scope = selectorTestScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        if (profile.id == "fast") {
                            scope =
                                scope.copy(environment = scope.environment.copy(featureFlags = mapOf("flag" to true)))
                        }
                        MemberProbeResult(if (profile.id == "fast") 1L else 100L)
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `network generation change fences a completed candidate probe`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            var scope = selectorTestScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        if (profile.id == "fast") scope = scope.copy(underlayGeneration = 2L)
                        MemberProbeResult(if (profile.id == "fast") 1L else 100L)
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `network ABA with identical final fingerprint still fences completed probe`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            val original = selectorTestScope(group)
            var scope = original
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        if (profile.id == "fast") {
                            scope =
                                original.copy(networkEpoch = TestPhysicalNetworkEvidence().apply { change() }.capture())
                        }
                        MemberProbeResult(if (profile.id == "fast") 1L else 100L)
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `removed or changed group fences a completed probe before selection`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            var current: SelectorProbeScope? = selectorTestScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { _, _ ->
                        current = null
                        MemberProbeResult(1L)
                    },
                    SelectorProbeScopeProvider { current },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `runtime environment change invalidates payload evidence`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            var scope = selectorTestScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        scope = scope.copy(environment = scope.environment.copy(tlsProfile = "changed"))
                        MemberProbeResult(if (profile.id == "fast") 1L else 100L)
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `a faster member beating the tolerance is selected`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val probe = FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L))
            val prober = selectorTestProber(store, probe)

            prober.runProbePass(
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast"))),
                "https://probe",
                toleranceMs = 50,
            )

            // 100ms beats 300ms by far more than the 50ms tolerance band.
            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `a member within the tolerance band does not trigger a switch`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "current")
            // 120 vs 100: the candidate is faster, but only by 20ms — inside the 50ms band.
            val probe = FixedMemberLatencyProbe(mapOf("current" to 120L, "candidate" to 100L))
            val prober = selectorTestProber(store, probe)

            prober.runProbePass(
                selectorTestGroup(listOf(selectorTestMember("current"), selectorTestMember("candidate"))),
                "https://probe",
                toleranceMs = 50,
            )

            assertEquals("current", store.selectedProfileId("g").value)
        }

    @Test
    fun `an unreachable current selection falls over to a reachable member`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "down")
            val probe = FixedMemberLatencyProbe(mapOf("down" to null, "up" to 200L))
            val prober = selectorTestProber(store, probe)

            val group = selectorTestGroup(listOf(selectorTestMember("down"), selectorTestMember("up")))

            // One failed check on a lossy path is not an outage; the second consecutive one is.
            prober.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("down", store.selectedProfileId("g").value)
            prober.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("up", store.selectedProfileId("g").value)
        }

    @Test
    fun `a recovered check resets the unreachable count`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "flaky")
            val results = ArrayDeque(listOf<Long?>(null, 150L, null))
            val prober =
                selectorTestProber(store) { profile, _ ->
                    if (profile.id == "flaky") {
                        MemberProbeResult(results.removeFirst())
                    } else {
                        MemberProbeResult(200L)
                    }
                }
            val group = selectorTestGroup(listOf(selectorTestMember("flaky"), selectorTestMember("up")))

            repeat(3) { prober.runProbePass(group, "https://probe", toleranceMs = 50) }

            assertEquals("flaky", store.selectedProfileId("g").value)
        }

    @Test
    fun `a check that could not judge the current member decides nothing`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "current")
            val prober =
                selectorTestProber(store) { profile, _ ->
                    if (profile.id == "current") {
                        MemberProbeResult(null, reason = ProfileQualityReason.CleanupPending)
                    } else {
                        MemberProbeResult(10L)
                    }
                }
            val group = selectorTestGroup(listOf(selectorTestMember("current"), selectorTestMember("other")))

            repeat(3) { prober.runProbePass(group, "https://probe", toleranceMs = 50) }

            assertEquals("current", store.selectedProfileId("g").value)
        }

    @Test
    fun `a member picked by hand is never moved by a probe pass`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.select("g", "pinned")
            var calls = 0
            val prober =
                selectorTestProber(store) { profile, _ ->
                    calls++
                    MemberProbeResult(if (profile.id == "pinned") null else 10L)
                }
            val group = selectorTestGroup(listOf(selectorTestMember("pinned"), selectorTestMember("fast")))

            repeat(3) { prober.runProbePass(group, "https://probe", toleranceMs = 0) }

            assertEquals("pinned", store.selectedProfileId("g").value)
            assertEquals(0, calls)
        }

    @Test
    fun `stored down health excludes a member only until its ttl expires`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            var wallClock = 1_000_000L
            val group =
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast"))).copy(
                    memberHealth = mapOf("fast" to EndpointHealth(EndpointHealthStatus.DOWN, wallClock, 60)),
                )
            val prober =
                SelectorUrltestProber(
                    store,
                    FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)),
                    SelectorProbeScopeProvider { selectorTestScope(it) },
                    wallClockMillis = { wallClock },
                )

            prober.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("slow", store.selectedProfileId("g").value)

            wallClock += 60_000
            prober.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `every member down keeps failover running on all members`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val down = EndpointHealth(EndpointHealthStatus.DOWN, 1_000_000L, 60)
            val group =
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast"))).copy(
                    memberHealth = mapOf("slow" to down, "fast" to down),
                )
            val prober =
                SelectorUrltestProber(
                    store,
                    FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)),
                    SelectorProbeScopeProvider { selectorTestScope(it) },
                    wallClockMillis = { 1_000_000L },
                )

            prober.runProbePass(group, "https://probe", toleranceMs = 50)

            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `direct members all down fail open while a Cloudflare member is up`() =
        runTest {
            val store = ProbeTestSelectionStore()
            val down = EndpointHealth(EndpointHealthStatus.DOWN, 1_000_000L, 60)
            val group =
                selectorTestGroup(
                    listOf(selectorTestMember("edge"), selectorTestMember("slow"), selectorTestMember("fast")),
                ).copy(cloudflareMemberIds = setOf("edge"), memberHealth = mapOf("slow" to down, "fast" to down))
            val prober =
                SelectorUrltestProber(
                    store,
                    FixedMemberLatencyProbe(mapOf("edge" to 1L, "slow" to 300L, "fast" to 100L)),
                    SelectorProbeScopeProvider { selectorTestScope(it) },
                    wallClockMillis = { 1_000_000L },
                )

            prober.runProbePass(group, "https://probe", toleranceMs = 50)

            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `a neutral member wins a latency tie with a degraded member declared first`() =
        runTest {
            val store = ProbeTestSelectionStore()
            val group =
                selectorTestGroup(listOf(selectorTestMember("degraded"), selectorTestMember("neutral"))).copy(
                    memberHealth = mapOf("degraded" to EndpointHealth(EndpointHealthStatus.DEGRADED, 1_000_000L, 60)),
                )
            val prober =
                SelectorUrltestProber(
                    store,
                    FixedMemberLatencyProbe(mapOf("degraded" to 100L, "neutral" to 100L)),
                    SelectorProbeScopeProvider { selectorTestScope(it) },
                    wallClockMillis = { 1_000_000L },
                )

            prober.runProbePass(group, "https://probe", toleranceMs = 50)

            assertEquals("neutral", store.selectedProfileId("g").value)
        }

    @Test
    fun `the run loop probes on each interval`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val probe = FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L))
            val prober = selectorTestProber(store, probe)
            val group =
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")), intervalSeconds = 10)
            backgroundScope.launch { prober.run(group) }
            runCurrent()
            // No probe before the first interval elapses.
            assertEquals("slow", store.selectedProfileId("g").value)

            advanceTimeBy(10_001)
            runCurrent()

            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `the first pass runs 15 seconds after start, not one interval later`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val prober = selectorTestProber(store, FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)))
            val group =
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")), intervalSeconds = 180)
            backgroundScope.launch { prober.run(group) }
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals("slow", store.selectedProfileId("g").value)

            advanceTimeBy(2)
            runCurrent()

            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `automatic existing fallback is retained until a direct candidate is reachable`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.selectAutomatically("g", store.snapshot("g"), "edge")
            val group =
                selectorTestGroup(
                    listOf(selectorTestMember("edge"), selectorTestMember("direct")),
                ).copy(cloudflareMemberIds = setOf("edge"))
            val unavailable = selectorTestProber(store, FixedMemberLatencyProbe(emptyMap()))
            unavailable.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("edge", store.selectedProfileId("g").value)

            val recovered = selectorTestProber(store, FixedMemberLatencyProbe(mapOf("direct" to 100L)))
            recovered.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("direct", store.selectedProfileId("g").value)
        }

    // bestSwitchCandidate — the pure decision the loop applies.

    @Test
    fun `bestSwitchCandidate picks the lowest reachable latency when current is unset`() {
        val winner =
            bestSwitchCandidate(
                latencies =
                    listOf(
                        MemberLatency("a", 250L),
                        MemberLatency("b", 90L),
                        MemberLatency("c", null),
                    ),
                currentProfileId = null,
                toleranceMs = 30,
            )

        assertEquals("b", winner)
    }

    @Test
    fun `bestSwitchCandidate returns null when no member is reachable`() {
        val winner =
            bestSwitchCandidate(
                latencies = listOf(MemberLatency("a", null), MemberLatency("b", null)),
                currentProfileId = "a",
                toleranceMs = 30,
            )

        assertNull(winner)
    }
}
