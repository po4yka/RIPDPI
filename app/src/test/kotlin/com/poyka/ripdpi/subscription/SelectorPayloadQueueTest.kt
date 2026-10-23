package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.SelectorFailover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectorPayloadQueueTest {
    @Test
    fun `concurrent payload rounds keep queue waiting outside both reserve budgets`() =
        runTest {
            val groups = listOf(payloadGroup("first"), payloadGroup("second"))
            val store = ProbeTestSelectionStore()
            for (group in groups) store.select(group.id, "${group.id}-active")
            val candidateMutex = Mutex()
            val observed = mutableListOf<String>()
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        candidateMutex.withLock {
                            observed += member.id
                            if (member.id.endsWith("active")) {
                                delay(29_000)
                                null
                            } else {
                                delay(10_000)
                                MemberProbeResult(10_000L)
                            }
                        }
                    },
                    SelectorProbeScopeProvider { selectorTestScope(it) },
                    { testScheduler.currentTime },
                )

            repeat(2) { round ->
                val passes = groups.map { group -> launch { prober.runProbePass(group, "unused", 0) } }
                passes.forEach { it.join() }
                if (round == 0) {
                    for (group in groups) assertEquals("${group.id}-active", store.selectedProfileId(group.id).value)
                    advanceTimeBy(60_000)
                }
            }

            for (group in groups) assertEquals("${group.id}-reserve", store.selectedProfileId(group.id).value)
            val oneRound = listOf("first-active", "first-reserve", "second-active", "second-reserve")
            assertEquals(oneRound + oneRound, observed)
            assertEquals(216_000L, testScheduler.currentTime)
        }

    @Test
    fun `queued payload pass cancels without probing and recaptures manual selection before entry`() =
        runTest {
            val first = payloadGroup("first")
            val second = payloadGroup("second").copy(cloudflareMemberIds = setOf("second-reserve"))
            val store = ProbeTestSelectionStore()
            store.select(first.id, "first-active")
            store.select(second.id, "second-active")
            val release = CompletableDeferred<Unit>()
            val observed = mutableListOf<String>()
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        observed += member.id
                        if (member.id == "first-active") release.await()
                        MemberProbeResult(20L)
                    },
                    SelectorProbeScopeProvider { selectorTestScope(it) },
                    { testScheduler.currentTime },
                )
            val running = launch { prober.runProbePass(first, "unused", 0) }
            runCurrent()
            val cancelled = launch { prober.runProbePass(second, "unused", 0) }
            runCurrent()
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
            assertEquals(listOf("first-active"), observed)

            val waiting = launch { prober.runProbePass(second, "unused", 0) }
            runCurrent()
            store.select(second.id, "second-reserve")
            release.complete(Unit)
            running.join()
            waiting.join()

            assertEquals(listOf("first-active"), observed)
            assertEquals("second-reserve", store.selectedProfileId(second.id).value)
        }

    @Test
    fun `ordinary selector yields between members while a payload round keeps its reserve capacity`() =
        runTest {
            val payload = payloadGroup("payload")
            val ordinary =
                selectorTestGroup(listOf(selectorTestMember("ordinary-first"), selectorTestMember("ordinary-second")))
            val store = ProbeTestSelectionStore()
            store.select(payload.id, "payload-active")
            val observed = mutableListOf<String>()
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, _ ->
                        observed += member.id
                        delay(if (member.id.endsWith("reserve")) 10_000 else 29_000)
                        if (member.id.endsWith("active")) null else MemberProbeResult(20L)
                    },
                    SelectorProbeScopeProvider { selectorTestScope(it) },
                    { testScheduler.currentTime },
                )

            val reachability = launch { prober.runProbePass(ordinary, "https://probe.example", 0) }
            runCurrent()
            val strict = launch { prober.runProbePass(payload, "unused", 0) }
            reachability.join()
            strict.join()

            assertEquals(listOf("ordinary-first", "payload-active", "payload-reserve", "ordinary-second"), observed)
            assertEquals(97_000L, testScheduler.currentTime)
        }

    private fun payloadGroup(id: String) =
        selectorTestGroup(
            listOf(
                selectorTestMember("$id-active").copy(groupId = id),
                selectorTestMember("$id-reserve").copy(groupId = id),
            ),
        ).copy(id = id, failover = SelectorFailover("unused", 60, 0, policy()))
}
