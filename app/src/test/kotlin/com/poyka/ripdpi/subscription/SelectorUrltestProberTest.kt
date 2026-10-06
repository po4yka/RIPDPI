package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.activities.TestEmptyProxyGroupRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
            val store = FakeSelectorSelectionStore()
            store.select("g", "origin")
            val group =
                urltestGroup(listOf(member("edge"), member("origin")))
                    .copy(cloudflareMemberIds = setOf("edge"))
            var calls = 0
            val prober =
                prober(store) { _, _ ->
                    calls++
                    store.select("g", "edge")
                    100L
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
            {"type":"trojan","tag":"edge","server":"edge.example","server_port":443,"password":"fixture"},
            {"type":"trojan","tag":"origin","server":"origin.example","server_port":443,"password":"fixture"}
            ],"ripdpi":{"schema_version":1,"cloudflare_outbound_tags":["edge"]}}"""
            val parsed = SelectorUrltestGroupImport.import(payload, "g") as SelectorUrltestImportResult.Success
            val group = requireNotNull(parsed.group)
            val store = FakeSelectorSelectionStore()
            val prober =
                prober(
                    store,
                    FakeLatencyProbe(
                        parsed.profiles.associate { it.id to if (it.displayName == "edge") 1L else 100L },
                    ),
                )

            prober.runProbePass(group, "https://probe.example", 0)

            assertEquals(parsed.profiles.single { it.displayName == "origin" }.id, store.selectedProfileId("g").value)
        }

    private fun prober(
        store: SelectorSelectionStore,
        probe: MemberLatencyProbe,
    ) = SelectorUrltestProber(store, probe, SelectorProbeScopeProvider { group -> testScope(group) })

    private fun testScope(group: ProxyGroup) =
        SelectorProbeScope(
            group.members,
            group.failover,
            group.cloudflareMemberIds,
            "opaque-test-network",
            1L,
            1L to 1L,
            com.poyka.ripdpi.services.CandidateRelayProbeEnvironment(
                false,
                "chrome_stable",
                emptyMap(),
                false,
                false,
            ),
        )

    @Test
    fun `resolver feature flag change fences a completed candidate probe`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            var scope = testScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        if (profile.id == "fast") {
                            scope =
                                scope.copy(environment = scope.environment.copy(featureFlags = mapOf("flag" to true)))
                        }
                        if (profile.id == "fast") 1L else 100L
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `network generation change fences a completed candidate probe`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            var scope = testScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        if (profile.id == "fast") scope = scope.copy(underlayGeneration = 2L)
                        if (profile.id == "fast") 1L else 100L
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `network ABA with identical final fingerprint still fences completed probe`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            val original = testScope(group)
            var scope = original
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        if (profile.id == "fast") {
                            scope = original.copy(networkEpoch = 3L to 1L)
                        }
                        if (profile.id == "fast") 1L else 100L
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `removed or changed group fences a completed probe before selection`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            var current: SelectorProbeScope? = testScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { _, _ ->
                        current = null
                        1L
                    },
                    SelectorProbeScopeProvider { current },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `runtime environment change invalidates payload evidence`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            var scope = testScope(group)
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ ->
                        scope = scope.copy(environment = scope.environment.copy(tlsProfile = "changed"))
                        if (profile.id == "fast") 1L else 100L
                    },
                    SelectorProbeScopeProvider { scope },
                )
            prober.runProbePass(group, "https://probe", 0)
            assertEquals("slow", store.selectedProfileId("g").value)
        }

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
            val prober = prober(store, probe)

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
            val prober = prober(store, probe)

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
            val prober = prober(store, probe)

            prober.runProbePass(urltestGroup(listOf(member("down"), member("up"))), "https://probe", toleranceMs = 50)

            assertEquals("up", store.selectedProfileId("g").value)
        }

    @Test
    fun `the run loop probes on each interval`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val probe = FakeLatencyProbe(mapOf("slow" to 300L, "fast" to 100L))
            val prober = prober(store, probe)
            val group = urltestGroup(listOf(member("slow"), member("fast")), intervalSeconds = 10)
            backgroundScope.launch { prober.run(group) }
            runCurrent()
            // No probe before the first interval elapses.
            assertEquals("slow", store.selectedProfileId("g").value)

            advanceTimeBy(10_001)
            runCurrent()

            assertEquals("fast", store.selectedProfileId("g").value)
        }

    @Test
    fun `failed group watcher cancels its owned probes`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val failWatcher = CompletableDeferred<Unit>()
            val failures = mutableListOf<Throwable>()
            val group = urltestGroup(listOf(member("slow"), member("fast")), intervalSeconds = 10)
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() =
                        flow {
                            emit(listOf(group))
                            failWatcher.await()
                            error("Group source failed")
                        }
                }
            val scope =
                CoroutineScope(
                    SupervisorJob(backgroundScope.coroutineContext[Job]) + StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, failure -> failures.add(failure) },
                )
            val coordinator =
                SelectorUrltestCoordinator(
                    scope,
                    repository,
                    prober(store, FakeLatencyProbe(mapOf("fast" to 100L))),
                )
            coordinator.start(Mode.VPN)
            runCurrent()
            failWatcher.complete(Unit)
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(1, failures.size)
            assertEquals("slow", store.selectedProfileId("g").value)
            coordinator.stop(Mode.VPN)
        }

    @Test
    fun `old service stop keeps probes running for new mode owner`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.select("g", "slow")
            val group = urltestGroup(listOf(member("slow"), member("fast")), intervalSeconds = 10)
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            val coordinator =
                SelectorUrltestCoordinator(
                    backgroundScope,
                    repository,
                    prober(store, FakeLatencyProbe(mapOf("fast" to 100L))),
                )
            coordinator.start(Mode.VPN)
            runCurrent()
            coordinator.start(Mode.Proxy)
            coordinator.start(Mode.Proxy)
            coordinator.stop(Mode.VPN)
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals("fast", store.selectedProfileId("g").value)
            coordinator.stop(Mode.Proxy)
            store.select("g", "slow")
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals("slow", store.selectedProfileId("g").value)
        }

    @Test
    fun `coordinator restarts probing when refreshed classification changes`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            val prober = prober(store, FakeLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)))
            val coordinator = SelectorUrltestCoordinator(backgroundScope, repository, prober)
            coordinator.start(Mode.VPN)
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals("fast", store.selectedProfileId("g").value)

            groups.value = listOf(group.copy(cloudflareMemberIds = setOf("fast")))
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()

            assertEquals("slow", store.selectedProfileId("g").value)
            coordinator.stop(Mode.VPN)
        }

    @Test
    fun `automatic existing fallback is retained until a direct candidate is reachable`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            store.selectAutomatically("g", store.snapshot("g"), "edge")
            val group = urltestGroup(listOf(member("edge"), member("direct"))).copy(cloudflareMemberIds = setOf("edge"))
            val unavailable = prober(store, FakeLatencyProbe(emptyMap()))
            unavailable.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("edge", store.selectedProfileId("g").value)

            val recovered = prober(store, FakeLatencyProbe(mapOf("direct" to 100L)))
            recovered.runProbePass(group, "https://probe", toleranceMs = 50)
            assertEquals("direct", store.selectedProfileId("g").value)
        }

    @Test
    fun `coordinator metadata updates do not postpone the next probe`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            val prober = prober(store, FakeLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)))
            val coordinator = SelectorUrltestCoordinator(backgroundScope, repository, prober)
            coordinator.start(Mode.VPN)
            runCurrent()
            advanceTimeBy(5_000)
            groups.value = listOf(group.copy(name = "Refreshed name"))
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()

            assertEquals("fast", store.selectedProfileId("g").value)
            coordinator.stop(Mode.VPN)
        }

    @Test
    fun `policy refresh invalidates a probe already entering its selection commit`() =
        runTest {
            val store = FakeSelectorSelectionStore()
            val group = urltestGroup(listOf(member("slow"), member("fast")))
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            var updateBeforeCommit = true
            val interceptedStore =
                object : SelectorSelectionStore by store {
                    override fun selectAutomatically(
                        groupId: String,
                        expected: SelectorSelectionSnapshot,
                        profileId: String,
                    ): Boolean {
                        if (updateBeforeCommit) {
                            updateBeforeCommit = false
                            groups.value = listOf(group.copy(cloudflareMemberIds = setOf("fast")))
                            runCurrent()
                        }
                        return store.selectAutomatically(groupId, expected, profileId)
                    }
                }
            val prober =
                prober(interceptedStore, FakeLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)))
            val coordinator = SelectorUrltestCoordinator(backgroundScope, repository, prober)
            coordinator.start(Mode.VPN)
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()

            assertNull(store.selectedProfileId("g").value)
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals("slow", store.selectedProfileId("g").value)
            coordinator.stop(Mode.VPN)
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
        private val manual = mutableSetOf<String>()
        private val revisions = mutableMapOf<String, Long>()

        fun set(
            groupId: String,
            profileId: String?,
        ) = write(groupId, profileId, false)

        override fun selectedProfileId(groupId: String): StateFlow<String?> = flowFor(groupId).asStateFlow()

        override fun invalidatePendingSelection(groupId: String) {
            revisions[groupId] = (revisions[groupId] ?: 0L) + 1L
        }

        override fun snapshot(groupId: String): SelectorSelectionSnapshot =
            SelectorSelectionSnapshot(flowFor(groupId).value, groupId in manual, revisions[groupId] ?: 0L)

        override fun selectAutomatically(
            groupId: String,
            expected: SelectorSelectionSnapshot,
            profileId: String,
        ): Boolean {
            if (snapshot(groupId) != expected) return false
            write(groupId, profileId, false)
            return true
        }

        override suspend fun select(
            groupId: String,
            profileId: String,
        ) = write(groupId, profileId, true)

        override fun clearSelection(groupId: String) = write(groupId, null, false)

        private fun write(
            groupId: String,
            profileId: String?,
            isManual: Boolean,
        ) {
            if (isManual) manual.add(groupId) else manual.remove(groupId)
            revisions[groupId] = (revisions[groupId] ?: 0L) + 1L
            flowFor(groupId).value = profileId
        }

        private fun flowFor(groupId: String): MutableStateFlow<String?> =
            flows.getOrPut(groupId) { MutableStateFlow(null) }
    }
}
