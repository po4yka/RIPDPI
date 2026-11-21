package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.subscription.EndpointHealth
import com.poyka.ripdpi.data.subscription.EndpointHealthStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectorUrltestProberPayloadPolicyTest {
    @Test
    fun `payload policy drives typed controls and switches only after consecutive observations`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve"))).copy(
                    failover = SelectorFailover("unused", 60, 0, policy()),
                )
            var now = 0L
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, target ->
                        assertEquals(MemberProbeTarget.Payload(policy()), target)
                        if (member.id == "reserve") MemberProbeResult(20L) else null
                    },
                    SelectorProbeScopeProvider { selectorTestScope(group) },
                    { now },
                    reserveBudget = ::admitTestPayloadPass,
                )
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.selectedProfileId("g").value)
            now = 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)
        }

    @Test
    fun `a health-only refresh keeps the payload switch streak`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve"))).copy(
                    failover = SelectorFailover("unused", 60, 0, policy()),
                )
            var now = 0L
            var scope = selectorTestScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ -> if (member.id == "reserve") MemberProbeResult(20L) else null },
                    SelectorProbeScopeProvider { scope },
                    { now },
                    reserveBudget = ::admitTestPayloadPass,
                )
            prober.runProbePass(group, "unused", 0)
            val healthy = EndpointHealth(EndpointHealthStatus.HEALTHY, 0L, 60)
            scope = scope.copy(memberHealth = mapOf("active" to healthy, "reserve" to healthy))
            now = 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)
        }

    @Test
    fun `payload network changes discard evidence and in-flight switches`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve"))).copy(
                    failover = SelectorFailover("unused", 60, 0, policy()),
                )
            var now = 0L
            var scope = selectorTestScope(group)
            var changeDuringProbe = false
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        if (changeDuringProbe) scope = scope.copy(underlayGeneration = scope.underlayGeneration!! + 1)
                        if (member.id == "reserve") MemberProbeResult(20L) else null
                    },
                    SelectorProbeScopeProvider { scope },
                    { now },
                    reserveBudget = ::admitTestPayloadPass,
                )
            prober.runProbePass(group, "unused", 0)
            scope = scope.copy(underlayGeneration = 2)
            now = 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.selectedProfileId("g").value)
            changeDuringProbe = true
            now = 120_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.selectedProfileId("g").value)
        }

    @Test
    fun `payload outage backoff skips probes and invalidation discards prior evidence`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve"))).copy(
                    failover = SelectorFailover("unused", 60, 0, policy()),
                )
            var now = 0L
            var calls = 0
            var recovered = false
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        calls++
                        if (recovered && member.id == "reserve") MemberProbeResult(20L) else null
                    },
                    SelectorProbeScopeProvider { selectorTestScope(group) },
                    { now },
                    reserveBudget = ::admitTestPayloadPass,
                )
            prober.runProbePass(group, "unused", 0)
            now = 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals(2, calls)
            recovered = true
            now = 120_000
            prober.runProbePass(group, "unused", 0)
            prober.invalidatePendingSelection("g")
            now = 180_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.selectedProfileId("g").value)
            now = 240_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)
        }

    @Test
    fun `payload healthy reserve qualifies without probing a stalled tail`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group =
                selectorTestGroup(
                    listOf(selectorTestMember("reserve"), selectorTestMember("tail"), selectorTestMember("active")),
                ).copy(
                    failover = SelectorFailover("unused", 60, 0, policy()),
                )
            val observed = mutableListOf<String>()
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        observed.add(member.id)
                        when (member.id) {
                            "active" -> {
                                null
                            }

                            "reserve" -> {
                                MemberProbeResult(20L)
                            }

                            else -> {
                                kotlinx.coroutines.awaitCancellation()
                            }
                        }
                    },
                    SelectorProbeScopeProvider { selectorTestScope(group) },
                    { testScheduler.currentTime },
                    reserveBudget = ::admitTestPayloadPass,
                )
            prober.runProbePass(group, "unused", 0)
            advanceTimeBy(60_000)
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)
            assertEquals(listOf("active", "reserve", "active", "reserve"), observed)
            assertEquals(60_000L, testScheduler.currentTime)
        }

    @Test
    fun `payload reserve traversal rotates without backoff for unobserved members`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group =
                selectorTestGroup(
                    listOf(selectorTestMember("active"), selectorTestMember("failed"), selectorTestMember("reserve")),
                ).copy(
                    failover = SelectorFailover("unused", 60, 0, policy()),
                )
            val observed = mutableListOf<String>()
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        observed.add(member.id)
                        if (member.id == "reserve") MemberProbeResult(20L) else null
                    },
                    SelectorProbeScopeProvider { selectorTestScope(group) },
                    { testScheduler.currentTime },
                    reserveBudget = ::admitTestPayloadPass,
                )
            repeat(3) {
                prober.runProbePass(group, "unused", 0)
                if (it < 2) advanceTimeBy(60_000)
            }
            assertEquals("reserve", store.selectedProfileId("g").value)
            assertEquals(listOf("active", "failed", "active", "reserve", "active", "reserve"), observed)
            assertEquals(120_000L, testScheduler.currentTime)
        }

    @Test
    fun `payload whole-pass deadline rotates stalled reserve without counting unobserved failures`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val group =
                selectorTestGroup(
                    listOf(selectorTestMember("active"), selectorTestMember("stall"), selectorTestMember("reserve")),
                ).copy(
                    failover = SelectorFailover("unused", 60, 0, policy()),
                )
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        when (member.id) {
                            "active" -> {
                                null
                            }

                            "reserve" -> {
                                MemberProbeResult(20L)
                            }

                            else -> {
                                kotlinx.coroutines.awaitCancellation()
                            }
                        }
                    },
                    SelectorProbeScopeProvider { selectorTestScope(group) },
                    { testScheduler.currentTime },
                    reserveBudget = ::admitTestPayloadPass,
                )
            prober.runProbePass(group, "unused", 0)
            assertEquals(60_000L, testScheduler.currentTime)
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.selectedProfileId("g").value)
            advanceTimeBy(60_000)
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)
        }
}
