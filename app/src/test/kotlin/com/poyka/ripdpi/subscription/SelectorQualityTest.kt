package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileQualityBudget
import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityMeasurement
import com.poyka.ripdpi.data.ProfileQualityPolicy
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProfileQualityStatus
import com.poyka.ripdpi.data.ProfileQualityStore
import com.poyka.ripdpi.data.SelectorFailover
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorQualityTest {
    private val qualityPolicy = ProfileQualityPolicy(131_076, 1_048_576, 3_600, 20, 50)
    private val policy = policy().copy(quality = qualityPolicy)
    private val group =
        selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve")))
            .copy(failover = SelectorFailover("unused", 60, 0, policy))
    private val selections = ProbeTestSelectionStore().apply { set("g", "active") }
    private val quality = ProfileQualityStore()
    private var now = 0L
    private var scope = selectorTestScope(group)
    private var deferred = false
    private val measured = mutableListOf<String>()
    private var active = measurement(duration = 1_000_000_000, loaded = 100)
    private var reserve = measurement(duration = 500_000_000, loaded = 120)
    private val prober =
        SelectorUrltestProber(
            selections,
            MemberLatencyProbe { profile, _ ->
                measured.add(profile.id)
                MemberProbeResult(10, if (profile.id == "active") active else reserve)
            },
            SelectorProbeScopeProvider { scope },
            monotonicMillis = { now },
            qualityStore = quality,
            reserveBudget = { id, value ->
                ProfileQualityBudget(
                    id,
                    if (deferred) 0 else value.reservedQualityPassBytes(),
                    131_076,
                    qualityPolicy.maxWindowBytes,
                    null,
                    if (deferred) ProfileQualityReason.BudgetExhausted else ProfileQualityReason.Verified,
                )
            },
        )

    @Test
    fun `healthy active measures reserve and sustained actual goodput replaces it`() =
        runTest {
            pass()
            assertEquals(listOf("active", "reserve"), measured)
            assertEquals("active", selections.snapshot("g").profileId)
            assertEquals(
                ProfileQualityDecisionReason.CollectingEvidence,
                quality.state.value.decisions["g"]
                    ?.reason,
            )
            pass()
            assertEquals("reserve", selections.snapshot("g").profileId)
            assertEquals(
                ProfileQualityDecisionReason.PromotedForGoodput,
                quality.state.value.decisions["g"]
                    ?.reason,
            )
        }

    @Test
    fun `loaded latency improvement can select without goodput regression`() =
        runTest {
            reserve = measurement(duration = 1_000_000_000, loaded = 70)
            pass()
            pass()
            assertEquals("reserve", selections.snapshot("g").profileId)
            assertEquals(
                ProfileQualityDecisionReason.PromotedForLoadedLatency,
                quality.state.value.decisions["g"]
                    ?.reason,
            )
        }

    @Test
    fun `partial evidence and different origins never promote and reset wins`() =
        runTest {
            pass()
            val complete = reserve
            reserve =
                reserve.copy(
                    loadedOverlap = false,
                    loadedLatencyMillis = null,
                    reason = ProfileQualityReason.LoadNotOverlapped,
                )
            pass()
            assertTrue(
                quality.state.value.observations.values
                    .any { it.status == ProfileQualityStatus.Partial },
            )
            reserve = complete
            pass()
            assertEquals("active", selections.snapshot("g").profileId)
            reserve = reserve.copy(probeIndex = 1)
            repeat(3) { pass() }
            assertEquals("active", selections.snapshot("g").profileId)
        }

    @Test
    fun `excess loaded latency and small goodput gain retain current profile`() =
        runTest {
            reserve = reserve.copy(loadedLatencyMillis = 151)
            repeat(3) { pass() }
            assertEquals("active", selections.snapshot("g").profileId)
            reserve = measurement(duration = 950_000_000, loaded = 100)
            repeat(3) { pass() }
            assertEquals("active", selections.snapshot("g").profileId)
        }

    @Test
    fun `budget denial starts no candidate and cannot extend a previous win`() =
        runTest {
            pass()
            deferred = true
            pass()
            assertEquals(2, measured.size)
            assertEquals(
                ProfileQualityDecisionReason.DeferredBudget,
                quality.state.value.decisions["g"]
                    ?.reason,
            )
            deferred = false
            pass()
            assertEquals("active", selections.snapshot("g").profileId)
            pass()
            assertEquals("reserve", selections.snapshot("g").profileId)
        }

    @Test
    fun `network change immediately revokes displayed measurements and streak but retains budget`() =
        runTest {
            pass()
            scope = scope.copy(underlayGeneration = 2)
            prober.invalidateChangedQualityScope(group)
            assertTrue(
                quality.state.value.observations.values
                    .all { it.reason == ProfileQualityReason.ScopeChanged },
            )
            assertFalse(
                quality.state.value.budgets
                    .isEmpty(),
            )
            pass()
            assertEquals("active", selections.snapshot("g").profileId)
        }

    @Test
    fun `manual same-profile selection revokes displayed facts and in-flight authority`() =
        runTest {
            pass()
            selections.select("g", "active")
            prober.invalidateChangedQualityScope(group)
            assertTrue(
                quality.state.value.observations.values
                    .all { it.reason == ProfileQualityReason.ScopeChanged },
            )
            pass()
            assertEquals("active", selections.snapshot("g").profileId)
        }

    @Test
    fun `existing cooldown prevents immediate quality reversal`() =
        runTest {
            pass()
            pass()
            active = measurement(duration = 100_000_000, loaded = 50)
            pass()
            assertEquals("reserve", selections.snapshot("g").profileId)
            assertEquals(
                ProfileQualityDecisionReason.Cooldown,
                quality.state.value.decisions["g"]
                    ?.reason,
            )
            pass()
            assertEquals("reserve", selections.snapshot("g").profileId)
            pass()
            assertEquals("active", selections.snapshot("g").profileId)
        }

    @Test
    fun `failed first reserve cannot starve a later quality or health winner`() =
        runTest {
            for (activeHealthy in listOf(true, false)) {
                val multi =
                    group.copy(
                        members =
                            listOf(
                                selectorTestMember("active"),
                                selectorTestMember("failed"),
                                selectorTestMember("reserve"),
                            ),
                    )
                val selected = ProbeTestSelectionStore().apply { set("g", "active") }
                val observed = mutableListOf<String>()
                var time = 0L
                val candidate =
                    SelectorUrltestProber(
                        selected,
                        MemberLatencyProbe { member, _ ->
                            observed.add(member.id)
                            when (member.id) {
                                "failed" -> null
                                "active" -> if (activeHealthy) MemberProbeResult(10, active) else null
                                else -> MemberProbeResult(10, reserve)
                            }
                        },
                        SelectorProbeScopeProvider { selectorTestScope(multi) },
                        monotonicMillis = { time },
                        reserveBudget = { id, value ->
                            ProfileQualityBudget(
                                id,
                                value.reservedQualityPassBytes(),
                                0,
                                qualityPolicy.maxWindowBytes,
                                null,
                                ProfileQualityReason.Verified,
                            )
                        },
                    )
                repeat(3) {
                    candidate.runProbePass(multi, "unused", 0)
                    time += 60_000
                }
                assertEquals(listOf("active", "failed", "active", "reserve", "active", "reserve"), observed)
                assertEquals("reserve", selected.snapshot("g").profileId)
            }
        }

    @Test
    fun `scope revocation between commit checks and selection CAS rejects stale promotion`() =
        runTest {
            lateinit var candidate: SelectorUrltestProber
            var time = 0L
            val delegate = ProbeTestSelectionStore().apply { set("g", "active") }
            val fenced =
                object : com.poyka.ripdpi.data.selector.SelectorSelectionStore by delegate {
                    override fun selectAutomatically(
                        groupId: String,
                        expected: com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot,
                        profileId: String,
                    ): Boolean {
                        scope = scope.copy(networkScope = "changed-network")
                        kotlinx.coroutines.runBlocking { candidate.invalidateChangedQualityScope(group) }
                        return delegate.selectAutomatically(groupId, expected, profileId)
                    }
                }
            candidate =
                SelectorUrltestProber(
                    fenced,
                    MemberLatencyProbe { profile, _ ->
                        MemberProbeResult(10, if (profile.id == "active") active else reserve)
                    },
                    SelectorProbeScopeProvider { scope },
                    monotonicMillis = { time },
                    qualityStore = quality,
                    reserveBudget = { id, value ->
                        ProfileQualityBudget(
                            id,
                            value.reservedQualityPassBytes(),
                            0,
                            qualityPolicy.maxWindowBytes,
                            null,
                            ProfileQualityReason.Verified,
                        )
                    },
                )
            candidate.runProbePass(group, "unused", 0)
            time += 60_000
            candidate.runProbePass(group, "unused", 0)
            assertEquals("active", delegate.snapshot("g").profileId)
            assertTrue(
                quality.state.value.decisions
                    .isEmpty(),
            )
            assertTrue(
                quality.state.value.observations.values
                    .all { it.reason == ProfileQualityReason.ScopeChanged },
            )
        }

    private suspend fun pass() {
        prober.runProbePass(group, "unused", 0)
        now += 60_000
    }

    private fun measurement(
        duration: Long,
        loaded: Long,
    ) = ProfileQualityMeasurement(
        0,
        32_768,
        duration,
        10,
        loaded,
        true,
        ProfileQualityReason.Verified,
    )
}
