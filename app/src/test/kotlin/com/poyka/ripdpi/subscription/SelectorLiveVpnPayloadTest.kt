package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbePurpose
import com.poyka.ripdpi.data.ProfileQualityBudget
import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityMeasurement
import com.poyka.ripdpi.data.ProfileQualityPolicy
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProfileQualityStore
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SelectorLiveVpnPayloadTest {
    @Test
    fun `a route change during the reserve probe discards the old failure sample`() =
        runTest {
            val store = ProbeTestSelectionStore().apply { set("g", "active") }
            val group = group(policy())
            var now = 0L
            var scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtimeConfiguration("active")))
            val laterPath = vpnPath(runtimeConfiguration("active", revision = 2L))
            val prober =
                SelectorUrltestProber(
                    store,
                    { _, _ ->
                        if (now == 0L) {
                            scope = scope.copy(vpnPath = laterPath)
                        }
                        MemberProbeResult(20L)
                    },
                    { scope },
                    { now },
                    reserveBudget = ::admitTestPayloadPass,
                    measureVpnPayload = {
                        _,
                        _,
                        ->
                        MemberProbeResult(null, reason = ProfileQualityReason.TransferFailed)
                    },
                )
            prober.runProbePass(group, "unused", 0)
            now = 60_000L
            prober.runProbePass(group, "unused", 0)
            assertEquals("active", store.snapshot("g").profileId)

            now = 120_000L
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.snapshot("g").profileId)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `recovery spends no budget until the selected member is applied`() =
        runTest {
            val store = ProbeTestSelectionStore().apply { set("g", "reserve") }
            val group = group(policy())
            var scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtimeConfiguration("active")))
            val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val charged = mutableListOf<PayloadProbePurpose>()
            val prober =
                SelectorUrltestProber(
                    store,
                    { _, _ -> error("Isolated candidate is unnecessary") },
                    { scope },
                    { testScheduler.currentTime },
                    reserveBudget = { id, configured, purpose ->
                        charged += purpose
                        admitTestPayloadPass(id, configured, purpose)
                    },
                    measureVpnPayload = { _, _ -> MemberProbeResult(20L) },
                )
            backgroundScope.launch { prober.run(group, requests) }
            runCurrent()
            requests.emit(Unit)
            advanceTimeBy(6_000L)
            assertEquals(emptyList<PayloadProbePurpose>(), charged)

            scope = scope.copy(vpnPath = vpnPath(runtimeConfiguration("reserve", revision = 2L)))
            requests.emit(Unit)
            advanceTimeBy(6_000L)
            assertEquals(listOf(PayloadProbePurpose.Recovery), charged)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a live failure queues one early pass and a budget deferral adds no failure`() =
        runTest {
            val store = ProbeTestSelectionStore().apply { set("g", "active") }
            val group = group(policy())
            val scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtimeConfiguration("active")))
            val externalRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val attempts = mutableListOf<Pair<Long, PayloadProbePurpose>>()
            var recoveryAllowed = false
            var liveTransfers = 0
            val prober =
                SelectorUrltestProber(
                    store,
                    { _, _ -> MemberProbeResult(20L) },
                    { scope },
                    { testScheduler.currentTime },
                    reserveBudget = { id, configured, purpose ->
                        attempts += testScheduler.currentTime to purpose
                        val admitted = admitTestPayloadPass(id, configured, purpose)
                        if (purpose == PayloadProbePurpose.Recovery && !recoveryAllowed) {
                            admitted.copy(reservedPassBytes = 0L, reason = ProfileQualityReason.BudgetExhausted)
                        } else {
                            admitted
                        }
                    },
                    measureVpnPayload = { _, _ ->
                        liveTransfers++
                        MemberProbeResult(null, reason = ProfileQualityReason.TransferFailed)
                    },
                )
            val loop = backgroundScope.launch { prober.run(group, externalRequests) }
            advanceTimeBy(20_001L)
            assertEquals(
                listOf(15_000L to PayloadProbePurpose.Scheduled, 20_000L to PayloadProbePurpose.Recovery),
                attempts,
            )
            assertEquals(1, liveTransfers)
            assertEquals("active", store.snapshot("g").profileId)

            advanceTimeBy(30_000L)
            assertEquals(2, attempts.size)
            recoveryAllowed = true
            externalRequests.emit(Unit)
            runCurrent()
            advanceTimeBy(5_001L)
            assertEquals(55_001L to PayloadProbePurpose.Recovery, attempts.last())
            assertEquals(2, liveTransfers)
            assertEquals("reserve", store.snapshot("g").profileId)
            loop.cancel()
        }

    @Test
    fun `a stale route or network cannot publish a verified reserve`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "active")
            val policy = policy()
            val group = group(policy)
            var now = 0L
            var mayCommit = false
            var checkingWrite = false
            val guarded =
                object : SelectorSelectionStore by store {
                    override suspend fun selectAutomatically(
                        groupId: String,
                        expected: SelectorSelectionSnapshot,
                        profileId: String,
                        isCurrent: () -> Boolean,
                    ): Boolean {
                        checkingWrite = true
                        return try {
                            store.selectAutomatically(groupId, expected, profileId, isCurrent)
                        } finally {
                            checkingWrite = false
                        }
                    }
                }
            val runtime = runtimeConfiguration("active")
            val scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtime))
            var liveProbes = 0
            val prober =
                SelectorUrltestProber(
                    guarded,
                    MemberLatencyProbe { member, _ ->
                        assertEquals("reserve", member.id)
                        MemberProbeResult(20L)
                    },
                    object : SelectorProbeScopeProvider {
                        override suspend fun capture(group: ProxyGroup) = scope

                        override fun isCurrent(scope: SelectorProbeScope): Boolean = !checkingWrite || mayCommit
                    },
                    { now },
                    measureVpnPayload = { path, expected ->
                        assertEquals(runtime, path.configuration)
                        assertEquals(policy, expected)
                        liveProbes++
                        MemberProbeResult(null, reason = ProfileQualityReason.TransferFailed)
                    },
                    reserveBudget = ::admitTestPayloadPass,
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
                    reserveBudget = ::admitTestPayloadPass,
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
                    reserveBudget = ::admitTestPayloadPass,
                )
            prober.runProbePass(group, "unused", 0)
            now += 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("reserve", store.selectedProfileId("g").value)

            // The desired choice alone must not start the cooldown clock.
            now += 600_000
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

    @Test
    fun `a quality promotion is published after a full body on the applied reserve`() =
        runTest {
            val qualityPolicy = ProfileQualityPolicy(131_076, 1_048_576, 3_600, 20, 50)
            val group = group(policy().copy(quality = qualityPolicy))
            val store = ProbeTestSelectionStore().apply { set("g", "active") }
            val quality = ProfileQualityStore()
            var now = 0L
            var scope = selectorTestScope(group).copy(vpnPath = vpnPath(runtimeConfiguration("active")))
            var stalls = false
            var challengerBusy = false
            var isolatedProbes = 0
            val slow = ProfileQualityMeasurement(0, 32_768, 1_000_000_000, 10, 100, true, ProfileQualityReason.Verified)
            val fast = slow.copy(bodyDurationNanos = 500_000_000)
            val prober =
                SelectorUrltestProber(
                    store,
                    { member, _ ->
                        isolatedProbes++
                        when {
                            member.id != "active" -> MemberProbeResult(10, fast)
                            challengerBusy -> MemberProbeResult(null, reason = ProfileQualityReason.CleanupPending)
                            else -> MemberProbeResult(10, slow)
                        }
                    },
                    { scope },
                    { now },
                    qualityStore = quality,
                    reserveBudget = { id, value, _ ->
                        ProfileQualityBudget(
                            id,
                            value.reservedQualityPassBytes(),
                            0L,
                            qualityPolicy.maxWindowBytes,
                            null,
                            ProfileQualityReason.Verified,
                        )
                    },
                    measureVpnPayload = { path, _ ->
                        when {
                            stalls -> MemberProbeResult(null, reason = ProfileQualityReason.TransferFailed)
                            path.configuration.revision == 1L -> MemberProbeResult(10, slow)
                            else -> MemberProbeResult(10, fast)
                        }
                    },
                )

            suspend fun pass() {
                prober.runProbePass(group, "unused", 0)
                now += 60_000
            }

            fun assertPending() {
                val decision = quality.state.value.decisions["g"]
                assertNotEquals(ProfileQualityDecisionReason.PromotedForGoodput, decision?.reason)
            }

            pass()
            pass()
            assertEquals("reserve", store.snapshot("g").profileId)
            assertPending()

            val beforeApply = isolatedProbes
            scope = scope.copy(vpnPath = requireNotNull(scope.vpnPath).copy(lease = null, usesIsolatedProbes = true))
            pass()
            assertEquals(beforeApply, isolatedProbes)

            scope = scope.copy(vpnPath = vpnPath(runtimeConfiguration("reserve", revision = 2L)))
            stalls = true
            pass()
            assertPending()

            val appliedReserve = requireNotNull(scope.vpnPath)
            scope = scope.copy(vpnPath = appliedReserve.copy(lease = null, usesIsolatedProbes = true))
            stalls = false
            pass()
            assertPending()

            scope = scope.copy(vpnPath = appliedReserve)
            challengerBusy = true
            pass()
            val decision = requireNotNull(quality.state.value.decisions["g"])
            assertEquals(ProfileQualityDecisionReason.PromotedForGoodput, decision.reason)
            assertEquals("reserve", decision.selectedProfileId)
            assertEquals(300_000L, decision.observedAtElapsedMillis)
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
