package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorSelectionStoreTest : RoomProfileTest() {
    @Test
    fun `automatic selection cannot overwrite a newer manual choice after ABA`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "one")
            val before = store.snapshot("group")
            store.select("group", "two")
            store.select("group", "one")
            assertFalse(store.selectAutomatically("group", before, "probe"))
            assertEquals("one", store.selectedProfileId("group").first())
            assertTrue(store.snapshot("group").isManual)
        }

    @Test
    fun `manual and automatic origins survive database reopen`() =
        runTest {
            val fixture = initialized()
            fixture.selectorSelections.select("group", "manual")
            fixture.reopen()
            assertTrue(fixture.selectorSelections.snapshot("group").isManual)
            assertNull(fixture.selectorSelections.manualReceipt("group"))
            assertTrue(
                fixture.selectorSelections.selectAutomatically(
                    "group",
                    fixture.selectorSelections.snapshot("group"),
                    "probe",
                ),
            )
            fixture.reopen()
            assertEquals("probe", fixture.selectorSelections.snapshot("group").profileId)
            assertFalse(fixture.selectorSelections.snapshot("group").isManual)
        }

    @Test
    fun `same member manual reselection invalidates an in flight probe`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "one")
            val before = store.snapshot("group")
            store.select("group", "one")
            assertFalse(store.selectAutomatically("group", before, "probe"))
        }

    @Test
    fun `clear selection invalidates even an empty snapshot`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            val before = store.snapshot("group")
            store.clearSelection("group")
            assertFalse(store.selectAutomatically("group", before, "probe"))
            assertNull(store.snapshot("group").profileId)
            assertFalse(store.snapshot("group").isManual)
        }

    @Test
    fun `clear all invalidates an empty snapshot without creating a flow`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            val before = store.snapshot("never-stored")
            store.clearAll()
            assertFalse(store.selectAutomatically("never-stored", before, "probe"))
        }

    @Test
    fun `clear selection retires the checked manual receipt`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "one")
            assertNotNull(store.manualReceipt("group"))
            store.clearSelection("group")
            assertNull(store.manualReceipt("group"))
            assertNull(store.selectedProfileId("group").first())
        }

    @Test
    fun `clear all retires every manual receipt and clears persisted ownership`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("one", "member-a")
            store.select("two", "member-b")
            store.clearAll()
            assertNull(store.manualReceipt("one"))
            assertNull(store.manualReceipt("two"))
            assertNull(fixture.selectorChoice.activeGroupId.first())
            fixture.reopen()
            assertNull(fixture.selectorSelections.snapshot("one").profileId)
            assertNull(fixture.selectorSelections.snapshot("two").profileId)
        }

    @Test
    fun `policy invalidation rejects old probes without changing manual selection`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "manual")
            val before = store.snapshot("group")
            store.invalidatePendingSelection("group")
            assertFalse(store.selectAutomatically("group", before, "stale"))
            assertEquals("manual", store.snapshot("group").profileId)
            assertTrue(store.snapshot("group").isManual)
            assertTrue(store.selectAutomatically("group", store.snapshot("group"), "fresh"))
        }

    @Test
    fun `checked choice publishes one committed member and readback does not rewrite rows`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "old")
            val before = store.snapshot("group")
            val receipt = reserve(fixture)
            val authority = fixture.pauseAuthority.snapshotAuthority()
            store.selectedProfileId("group").test {
                assertEquals("old", awaitItem())
                fixture.selectorChoice.commitMember("group", "new", SelectorChoiceOrigin.Manual(receipt))
                assertEquals("new", awaitItem())
                val committed = fixture.snapshot()
                val after = store.snapshot("group")
                assertEquals("new", after.profileId)
                assertTrue(after.isManual)
                assertTrue(after.revision > before.revision)
                assertEquals("group", fixture.selectorChoice.activeGroupId.first())
                assertSame(receipt, store.manualReceipt("group"))
                assertEquals(authority, fixture.pauseAuthority.snapshotAuthority())
                assertEquals(committed, fixture.snapshot())
                expectNoEvents()
            }
        }

    @Test
    fun `repeated checked choice rejects stale probes when local revision is ahead`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "same")
            repeat(2) { store.invalidatePendingSelection("group") }
            val before = store.snapshot("group")
            fixture.selectorChoice.commitMember("group", "same", SelectorChoiceOrigin.Manual(reserve(fixture)))
            assertFalse(store.selectAutomatically("group", before, "stale"))
            assertEquals("same", store.snapshot("group").profileId)
            assertTrue(store.snapshot("group").revision > before.revision)
        }

    @Test
    fun `automatic compare and write cannot overwrite a concurrent checked manual commit`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "initial")
            val before = store.snapshot("group")
            val receipt = reserve(fixture)
            val barrier = ProfileWriteBarrier("selector_choices")
            fixture.room.beforeSeal = barrier::beforeSeal
            val manual =
                async { fixture.selectorChoice.commitMember("group", "manual", SelectorChoiceOrigin.Manual(receipt)) }
            try {
                barrier.awaitEntry()
                val automatic = async { store.selectAutomatically("group", before, "probe") }
                barrier.release()
                manual.await()
                assertFalse(automatic.await())
            } finally {
                barrier.release()
            }
            fixture.clearFaults()
            assertEquals("manual", store.snapshot("group").profileId)
            assertSame(receipt, store.manualReceipt("group"))
        }

    @Test
    fun `automatic choice clears the transient manual receipt`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "manual")
            assertNotNull(store.manualReceipt("group"))
            assertTrue(store.selectAutomatically("group", store.snapshot("group"), "probe"))
            assertNull(store.manualReceipt("group"))
            assertFalse(store.snapshot("group").isManual)
            assertEquals("probe", store.selectedProfileId("group").first())
        }

    @Test
    fun `absent and per-group selections are independent`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            assertNull(store.selectedProfileId("one").first())
            store.select("one", "first")
            store.select("two", "second")
            assertEquals("first", store.selectedProfileId("one").first())
            assertEquals("second", store.selectedProfileId("two").first())
            assertNull(store.selectedProfileId("three").first())
        }

    @Test
    fun `selection flow observes committed selection and deletion`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.selectedProfileId("group").test {
                assertNull(awaitItem())
                store.select("group", "one")
                assertEquals("one", awaitItem())
                store.select("group", "two")
                assertEquals("two", awaitItem())
                store.clearSelection("group")
                assertNull(awaitItem())
            }
        }

    @Test
    fun `failed checked selection does not publish a member or manual capability`() =
        runTest {
            val fixture = initialized()
            fixture.selectorSelections.select("group", "old")
            val before = fixture.snapshot()
            fixture.failWrite("selector_choices")
            fixture.selectorSelections.selectedProfileId("group").test {
                assertEquals("old", awaitItem())
                assertTrue(runCatching { fixture.selectorSelections.select("group", "new") }.isFailure)
                assertEquals(before, fixture.snapshot())
                expectNoEvents()
            }
            fixture.clearFaults()
            fixture.reopen()
            assertEquals("old", fixture.selectorSelections.snapshot("group").profileId)
            assertNull(fixture.selectorSelections.manualReceipt("group"))
        }

    @Test
    fun `delayed automatic caller cannot clear a newer manual receipt after database commit`() =
        runTest {
            val fixture = initialized()
            val store = fixture.selectorSelections
            store.select("group", "initial")
            val expected = store.snapshot("group")
            val dispatcher = DelayedReturnDispatcher()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
            try {
                val automatic =
                    scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        store.selectAutomatically("group", expected, "automatic")
                    }
                dispatcher.awaitReturn()
                assertFalse(automatic.isCompleted)
                assertEquals("automatic", store.snapshot("group").profileId)
                store.select("group", "newer-manual")
                val receipt = checkNotNull(store.manualReceipt("group"))
                dispatcher.resume()
                assertTrue(automatic.await())
                assertSame(receipt, store.manualReceipt("group"))
                assertTrue(fixture.pauseAuthority.isCurrent(receipt))
                assertEquals("newer-manual", store.snapshot("group").profileId)
                assertTrue(store.snapshot("group").isManual)
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                dispatcher.resume()
            }
        }

    private suspend fun initialized() = fixture().also { it.initialize() }

    private fun reserve(fixture: ProfileMutationTestFixture): ProfileActivationReceipt =
        (
            fixture.pauseAuthority.invalidateForMutation(
                ProfileMutationOrigin.ExplicitActivation,
                java.util.UUID
                    .randomUUID()
                    .toString(),
                fixture.pauseAuthority.reference(),
            ) as ProfileMutationOutcome.Reserved
        ).receipt as ProfileActivationReceipt
}

private class DelayedReturnDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    private val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    private val arrived = java.util.concurrent.CountDownLatch(1)

    override fun dispatch(
        context: kotlin.coroutines.CoroutineContext,
        block: Runnable,
    ) {
        queued.add(block)
        arrived.countDown()
    }

    suspend fun awaitReturn() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Transaction did not return to caller" }
        }

    fun resume() {
        while (true) (queued.poll() ?: return).run()
    }
}
