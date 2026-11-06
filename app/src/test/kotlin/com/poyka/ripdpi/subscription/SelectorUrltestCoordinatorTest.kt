package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.activities.TestEmptyProxyGroupRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectorUrltestCoordinatorTest {
    @Test
    fun `failed startup storage is reported and explicit retry starts probes`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.set("g", "slow")
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            var readable = false
            var subscriptions = 0
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() =
                        flow {
                            subscriptions++
                            check(readable) { "Profile storage unavailable" }
                            emit(listOf(group))
                            kotlinx.coroutines.awaitCancellation()
                        }
                }
            val coordinator =
                SelectorUrltestCoordinator(
                    backgroundScope,
                    repository,
                    selectorTestProber(store, FixedMemberLatencyProbe(mapOf("fast" to 100L))),
                )
            coordinator.start(Mode.VPN)
            val failure = runCatching { coordinator.awaitSubscription() }.exceptionOrNull()
            assertEquals("Profile storage unavailable", failure?.message)
            assertEquals(1, subscriptions)
            readable = true
            coordinator.awaitSubscription()
            assertEquals(2, subscriptions)
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals("fast", store.selectedProfileId("g").value)
            coordinator.stop(Mode.VPN)
        }

    @Test
    fun `failed group watcher cancels its owned probes`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.select("g", "slow")
            val failWatcher = CompletableDeferred<Unit>()
            val storageFailure = IllegalStateException("Group source failed")
            val failures = mutableListOf<Throwable>()
            val group =
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")), intervalSeconds = 10)
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() =
                        flow {
                            if (failWatcher.isCompleted) throw storageFailure
                            emit(listOf(group))
                            failWatcher.await()
                            throw storageFailure
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
                    selectorTestProber(store, FixedMemberLatencyProbe(mapOf("fast" to 100L))),
                )
            coordinator.start(Mode.VPN)
            runCurrent()
            failWatcher.complete(Unit)
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(emptyList<Throwable>(), failures)
            val failure = runCatching { coordinator.awaitSubscription() }.exceptionOrNull()
            assertTrue(failure === storageFailure || failure?.cause === storageFailure)
            assertEquals("slow", store.selectedProfileId("g").value)
            coordinator.stop(Mode.VPN)
        }

    @Test
    fun `old service stop keeps probes running for new mode owner`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.select("g", "slow")
            val group =
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")), intervalSeconds = 10)
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            val coordinator =
                SelectorUrltestCoordinator(
                    backgroundScope,
                    repository,
                    selectorTestProber(store, FixedMemberLatencyProbe(mapOf("fast" to 100L))),
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
            val store = ProbeTestSelectionStore()
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            val prober = selectorTestProber(store, FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)))
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
    fun `coordinator metadata updates do not postpone the next probe`() =
        runTest {
            val store = ProbeTestSelectionStore()
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            val prober = selectorTestProber(store, FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)))
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
            val store = ProbeTestSelectionStore()
            val group = selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")))
            val groups = MutableStateFlow(listOf(group))
            val repository =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override fun groups() = groups
                }
            var updateBeforeCommit = true
            val interceptedStore =
                object : SelectorSelectionStore by store {
                    override suspend fun selectAutomatically(
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
                selectorTestProber(interceptedStore, FixedMemberLatencyProbe(mapOf("slow" to 300L, "fast" to 100L)))
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
}
