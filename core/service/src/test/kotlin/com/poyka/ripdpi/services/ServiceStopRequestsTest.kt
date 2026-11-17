package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopRequestsTest {
    @Test
    fun `fallback stop is skipped when start id stops service`() {
        val android = FakeStartedService(lastStartId = 42)

        val deferred = android.requests.request(42)

        assertFalse(deferred)
        assertEquals(listOf(42), android.stopSelfResultIds)
        assertTrue(android.stopped)
        assertFalse(android.plainStopCalled)
    }

    @Test
    fun `newer start is preserved when start id does not stop service`() {
        val android = FakeStartedService(lastStartId = 43)

        val deferred = android.requests.request(42)

        assertTrue(deferred)
        assertEquals(listOf(42), android.stopSelfResultIds)
        assertFalse(android.stopped)
        assertFalse(android.plainStopCalled)
    }

    @Test
    fun `fallback stop runs without start id`() {
        val android = FakeStartedService(lastStartId = 42)

        val deferred = android.requests.request(null)

        assertFalse(deferred)
        assertTrue(android.stopSelfResultIds.isEmpty())
        assertTrue(android.plainStopCalled)
    }

    @Test
    fun `no settle is due without a refused stop`() {
        val android = FakeStartedService(lastStartId = 0)
        android.deliverAndHandle(1)

        assertNull(android.requests.settleStartId())
    }

    @Test
    fun `refused stop settles only after every delivered command left its service stage`() {
        val android = FakeStartedService(lastStartId = 0)
        android.deliverAndHandle(1)
        android.deliver(2)

        android.requests.request(1)

        assertNull(android.requests.settleStartId())
        android.requests.serviceStageEnded()
        assertEquals(2, android.requests.settleStartId())
    }

    @Test
    fun `settle stops a service left without a session`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.deliverAndHandle(2)
            android.requests.request(1)

            android.requests.settle(checkNotNull(android.requests.settleStartId())) { false }

            assertTrue(android.stopped)
            assertNull(android.requests.settleStartId())
        }

    @Test
    fun `settle keeps a service with a session and clears the deferred stop`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.deliverAndHandle(2)
            android.requests.request(1)

            android.requests.settle(checkNotNull(android.requests.settleStartId())) { true }

            assertFalse(android.stopped)
            assertEquals(listOf(1), android.stopSelfResultIds)
            assertNull(android.requests.settleStartId())
        }

    @Test
    fun `settle refused by a newer command leaves the stop to that command`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.deliverAndHandle(2)
            android.requests.request(1)
            val firstSettle = checkNotNull(android.requests.settleStartId())
            android.deliver(3)

            android.requests.settle(firstSettle) { false }

            assertFalse(android.stopped)
            android.requests.serviceStageEnded()
            android.requests.settle(checkNotNull(android.requests.settleStartId())) { false }
            assertTrue(android.stopped)
            assertEquals(listOf(1, 2, 3), android.stopSelfResultIds)
        }

    @Test
    fun `stop refused while a settle keeps the service is settled again`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.deliverAndHandle(2)
            android.requests.request(1)

            android.requests.settle(checkNotNull(android.requests.settleStartId())) {
                // The session ends and its stop is refused while this settle still sees it.
                android.requests.request(1)
                true
            }

            assertEquals(2, android.requests.settleStartId())
        }

    @Test
    fun `failure stop is settled behind the queued commands without a direct stop`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)

            android.requests.requestAfterQueuedCommands()

            assertTrue(android.stopSelfResultIds.isEmpty())
            android.requests.settle(checkNotNull(android.requests.settleStartId())) { true }
            assertFalse(android.stopped)
            assertNull(android.requests.settleStartId())
        }

    @Test
    fun `stop deferred during overlapping settles stays due`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.requests.requestAfterQueuedCommands()

            android.requests.settle(1) {
                // A second settle resolves the same stop, then a new failure stop is deferred.
                android.requests.settle(1) { true }
                android.requests.requestAfterQueuedCommands()
                true
            }

            assertEquals(1, android.requests.settleStartId())
        }

    @Test
    fun `settle reads the pause before the runtime session`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.requests.requestAfterQueuedCommands()

            android.requests.settle(1) {
                android.checks += "session"
                true
            }

            assertEquals(listOf("pause", "session"), android.checks)
            assertFalse(android.stopped)
        }

    @Test
    fun `paused service keeps a deferred stop without reading the session`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0, paused = true)
            android.deliverAndHandle(1)
            android.requests.requestAfterQueuedCommands()

            android.requests.settle(1) { error("a pause decides first") }

            assertFalse(android.stopped)
            assertNull(android.requests.settleStartId())
        }

    @Test
    fun `settle runs at once without a shell queue`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.requests.requestAfterQueuedCommands()

            android.requests.scheduleSettle(enqueue = null) { false }

            assertTrue(android.stopped)
        }

    @Test
    fun `settle waits for the shell queue to run it`() =
        runTest {
            val android = FakeStartedService(lastStartId = 0)
            android.deliverAndHandle(1)
            android.requests.requestAfterQueuedCommands()
            val queued = mutableListOf<suspend () -> Unit>()

            android.requests.scheduleSettle(enqueue = { queued += it }) { false }

            assertFalse(android.stopped)
            queued.single().invoke()
            assertTrue(android.stopped)
        }

    /** Models Android's start-id bookkeeping: `stopSelfResult(id)` stops only when [id] is the last delivered id. */
    private class FakeStartedService(
        private var lastStartId: Int,
        private val paused: Boolean = false,
    ) {
        val stopSelfResultIds = mutableListOf<Int>()
        val checks = mutableListOf<String>()
        var stopped = false
            private set
        var plainStopCalled = false
            private set

        val requests =
            ServiceStopRequests(
                stopSelfResult = { startId ->
                    stopSelfResultIds += startId
                    (startId == lastStartId).also { if (it) stopped = true }
                },
                stopSelf = {
                    plainStopCalled = true
                    stopped = true
                },
                isPaused = {
                    checks += "pause"
                    paused
                },
            )

        fun deliver(startId: Int) {
            lastStartId = startId
            requests.commandDelivered(startId)
        }

        fun deliverAndHandle(startId: Int) {
            deliver(startId)
            requests.serviceStageEnded()
        }
    }
}
