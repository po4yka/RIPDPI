package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.activities.TestEmptyProxyGroupRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.PayloadProbePurpose
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectorRecoveryScheduleTest {
    @Test
    fun `a queued recovery waits for quiet after a newer event arrives`() =
        runTest {
            val store = ProbeTestSelectionStore().apply { set("g", "active") }
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve")))
                    .copy(failover = SelectorFailover("unused", 60, 0, policy()))
            val gate = CompletableDeferred<Unit>()
            val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var network = "old"
            val started = mutableListOf<Pair<Long, String>>()
            val prober =
                SelectorUrltestProber(
                    store,
                    { _, _ ->
                        started += testScheduler.currentTime to network
                        gate.await()
                        MemberProbeResult(20L)
                    },
                    { selectorTestScope(group).copy(networkScope = network) },
                    { testScheduler.currentTime },
                    reserveBudget = ::admitTestPayloadPass,
                )
            backgroundScope.launch { prober.run(group, requests) }
            advanceTimeBy(16_000L)
            requests.emit(Unit)
            advanceTimeBy(6_000L)
            network = "new"
            requests.emit(Unit)
            advanceTimeBy(1_000L)
            gate.complete(Unit)
            advanceTimeBy(3_999L)
            assertEquals(listOf(15_000L to "old"), started)

            advanceTimeBy(2L)
            assertEquals(listOf(15_000L to "old", 27_000L to "new"), started)
        }

    @Test
    fun `a recovery request inside the spacing runs when the spacing ends`() =
        runTest {
            val store = ProbeTestSelectionStore().apply { set("g", "active") }
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve")))
                    .copy(failover = SelectorFailover("unused", 60, 0, policy()))
            val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val attempts = mutableListOf<Pair<Long, PayloadProbePurpose>>()
            val prober =
                SelectorUrltestProber(
                    store,
                    { _, _ -> MemberProbeResult(20L) },
                    { selectorTestScope(group) },
                    { testScheduler.currentTime },
                    reserveBudget = { id, configured, purpose ->
                        attempts += testScheduler.currentTime to purpose
                        admitTestPayloadPass(id, configured, purpose)
                    },
                )
            backgroundScope.launch { prober.run(group, requests) }
            advanceTimeBy(16_000L)
            requests.emit(Unit)
            advanceTimeBy(14_000L)
            // The second network change arrives while the first recovery holds the 30-second spacing.
            requests.emit(Unit)
            advanceTimeBy(21_001L)

            assertEquals(
                listOf(
                    15_000L to PayloadProbePurpose.Scheduled,
                    21_000L to PayloadProbePurpose.Recovery,
                    51_000L to PayloadProbePurpose.Recovery,
                ),
                attempts,
            )
        }

    @Test
    fun `a deferred regular health pass preserves earlier observed failures`() =
        runTest {
            val store = ProbeTestSelectionStore().apply { set("g", "active") }
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve")))
                    .copy(failover = SelectorFailover("unused", 60, 0, policy()))
            var deny = false
            var measured = 0
            val prober =
                SelectorUrltestProber(
                    store,
                    { member, _ ->
                        measured++
                        if (member.id == "active") null else MemberProbeResult(20L)
                    },
                    { selectorTestScope(group) },
                    { testScheduler.currentTime },
                    reserveBudget = { id, configured, purpose ->
                        val admitted = admitTestPayloadPass(id, configured, purpose)
                        if (deny) {
                            admitted.copy(
                                reservedPassBytes = 0L,
                                reason = ProfileQualityReason.BudgetExhausted,
                            )
                        } else {
                            admitted
                        }
                    },
                )
            prober.runProbePass(group, "unused", 0)
            advanceTimeBy(60_000L)
            deny = true
            prober.runProbePass(group, "unused", 0)
            assertEquals(2, measured)
            assertEquals("active", store.snapshot("g").profileId)

            advanceTimeBy(60_000L)
            deny = false
            prober.runProbePass(group, "unused", 0)
            assertEquals(4, measured)
            assertEquals("reserve", store.snapshot("g").profileId)
        }

    @Test
    fun `network and service events coalesce without moving the scheduled pass`() =
        runTest {
            val store = ProbeTestSelectionStore().apply { set("g", "active") }
            val policy = policy()
            val group =
                selectorTestGroup(listOf(selectorTestMember("active"), selectorTestMember("reserve")))
                    .copy(failover = SelectorFailover("unused", 60, 0, policy))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = flowOf(listOf(group))
                }
            val physical = MutableStateFlow(0L)
            val failures = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
            val fakeService = FakeServiceStateStore()
            val service =
                object : ServiceStateStore by fakeService {
                    override val events = failures
                }
            var network = "initial"
            val measured = mutableListOf<String>()
            val attempts = mutableListOf<Pair<Long, PayloadProbePurpose>>()
            val prober =
                SelectorUrltestProber(
                    store,
                    { profile, _ ->
                        measured += "${profile.id}@$network"
                        MemberProbeResult(20L)
                    },
                    { selectorTestScope(group).copy(networkScope = network) },
                    { testScheduler.currentTime },
                    reserveBudget = { id, configured, purpose ->
                        attempts += testScheduler.currentTime to purpose
                        admitTestPayloadPass(id, configured, purpose)
                    },
                )
            val coordinator =
                SelectorUrltestCoordinator(
                    backgroundScope,
                    repository,
                    prober,
                    recoveryRequests = selectorRecoveryRequests(physical, service),
                )
            coordinator.start(Mode.VPN)
            runCurrent()
            failures.emit(
                ServiceEvent.Failed(Sender.VPN, FailureReason.TunnelEstablishmentFailed, AppStatus.Halted, Mode.VPN),
            )
            advanceTimeBy(6_000L)
            assertTrue(attempts.isEmpty())

            physical.value++
            advanceTimeBy(500L)
            network = "settled"
            fakeService.updateTelemetry(
                ServiceTelemetrySnapshot(networkHandoverState = NetworkHandoverStates.Revalidated),
            )
            advanceTimeBy(500L)
            failures.emit(
                ServiceEvent.Failed(Sender.VPN, FailureReason.NativeError("fixture"), AppStatus.Running, Mode.VPN),
            )
            advanceTimeBy(4_999L)
            assertTrue(attempts.isEmpty())
            advanceTimeBy(3_002L)
            assertEquals(
                listOf(12_000L to PayloadProbePurpose.Recovery, 15_000L to PayloadProbePurpose.Scheduled),
                attempts,
            )
            assertEquals(listOf("active@settled", "active@settled"), measured)

            physical.value++
            advanceTimeBy(6_000L)
            assertEquals(2, attempts.size)
            coordinator.stop(Mode.VPN)
            physical.value++
            advanceTimeBy(60_000L)
            assertEquals(2, attempts.size)
        }
}
