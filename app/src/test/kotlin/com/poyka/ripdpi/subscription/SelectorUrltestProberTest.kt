package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private fun member(id: String) =
        ProxyProfile.Trojan(
            id = id,
            displayName = id,
            groupId = "g",
            server = "$id.example.com",
            serverPort = 443,
            password = "pw",
        )

    private fun urltestGroup(
        members: List<ProxyProfile>,
        intervalSeconds: Int = 10,
        toleranceMs: Int = 50,
    ) = ProxyGroup(
        id = "g",
        name = "Group",
        type = ProxyGroupType.SUBSCRIPTION,
        order = 0,
        isSelector = true,
        members = members,
        failover =
            SelectorFailover(
                probeUrl = "https://probe",
                intervalSeconds = intervalSeconds,
                toleranceMs = toleranceMs,
            ),
    )

    @Test
    fun `a faster member beating the tolerance is selected`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val probe = FakeLatencyProbe(mapOf("slow" to 300L, "fast" to 100L))
            val prober = SelectorUrltestProber(store, probe)

            prober.runProbePass(urltestGroup(listOf(member("slow"), member("fast"))), "https://probe", toleranceMs = 50)

            // 100ms beats 300ms by far more than the 50ms tolerance band.
            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `a member within the tolerance band does not trigger a switch`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "current")
            // 120 vs 100: the candidate is faster, but only by 20ms — inside the 50ms band.
            val probe = FakeLatencyProbe(mapOf("current" to 120L, "candidate" to 100L))
            val prober = SelectorUrltestProber(store, probe)

            prober.runProbePass(
                urltestGroup(listOf(member("current"), member("candidate"))),
                "https://probe",
                toleranceMs = 50,
            )

            assertEquals("current", store.selectedProfileId("g").value)
        }

    @Test
    fun `an unreachable current selection falls over to a reachable member`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "down")
            val probe = FakeLatencyProbe(mapOf("down" to null, "up" to 200L))
            val prober = SelectorUrltestProber(store, probe)

            prober.runProbePass(urltestGroup(listOf(member("down"), member("up"))), "https://probe", toleranceMs = 50)

            assertEquals("up", store.selectedProfileId("g").value)
        }

    @Test
    fun `the run loop probes on each interval`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val probe = FakeLatencyProbe(mapOf("slow" to 300L, "fast" to 100L))
            val prober = SelectorUrltestProber(store, probe)
            val group = urltestGroup(listOf(member("slow"), member("fast")), intervalSeconds = 10)
            backgroundScope.launch { prober.run(group) }
            runCurrent()
            // No probe before the first interval elapses.
            assertEquals("slow", store.selectedProfileId("g").value)

            advanceTimeBy(10_001)
            runCurrent()

            assertEquals("fast", store.selectedProfileId("g").value)
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

    private class FakeLatencyProbe(
        private val latencies: Map<String, Long?>,
    ) : MemberLatencyProbe {
        override suspend fun measure(
            profile: ProxyProfile,
            probeUrl: String,
        ): Long? = latencies[profile.id]
    }

    private class FakeSelectorSelectionStore : SelectorSelectionStore {
        private val flows = HashMap<String, MutableStateFlow<String?>>()

        override fun selectedProfileId(groupId: String): StateFlow<String?> = flowFor(groupId).asStateFlow()

        override fun select(
            groupId: String,
            profileId: String,
        ) {
            flowFor(groupId).value = profileId
        }

        override fun clearSelection(groupId: String) {
            flowFor(groupId).value = null
        }

        private fun flowFor(groupId: String): MutableStateFlow<String?> =
            flows.getOrPut(groupId) { MutableStateFlow(null) }
    }
}
