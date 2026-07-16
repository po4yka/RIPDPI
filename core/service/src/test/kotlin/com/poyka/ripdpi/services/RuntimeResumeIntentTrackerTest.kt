package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeResumeIntentTrackerTest {
    @Test
    fun `accepting an already requested start preserves a later scan lease`() {
        val tracker = RuntimeResumeIntentTracker()
        tracker.withUserStart(action = {})
        val lease = tracker.captureResumeLease()

        tracker.recordAcceptedStart()

        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
    }

    @Test
    fun `accepted start after an intervening stop supersedes the scan lease`() {
        val tracker = RuntimeResumeIntentTracker()
        tracker.withUserStart(action = {})
        val lease = tracker.captureResumeLease()
        tracker.recordAcceptedStop()

        tracker.recordAcceptedStart()

        val ownership = tracker.ownership(lease) as ResumeLeaseOwnership.Superseded
        assertEquals(UserRuntimeIntent.Running, ownership.intent)
    }

    @Test
    fun `stale stopped generation cannot compensate after newer user start`() {
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        tracker.recordAcceptedStop()
        val stopped = tracker.ownership(lease) as ResumeLeaseOwnership.Superseded
        tracker.withUserStart(action = {})
        var stopDispatched = false

        val compensated =
            tracker.runCompensatingStopIfCurrent(stopped) {
                stopDispatched = true
            }

        assertFalse(compensated)
        assertFalse(stopDispatched)
    }

    @Test
    fun `newer user start dispatches after in-flight compensation`() {
        val tracker = RuntimeResumeIntentTracker()
        val lease = tracker.captureResumeLease()
        tracker.recordAcceptedStop()
        val stopped = tracker.ownership(lease) as ResumeLeaseOwnership.Superseded
        val compensationEntered = CountDownLatch(1)
        val releaseCompensation = CountDownLatch(1)
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)

        try {
            val compensation =
                executor.submit<Boolean> {
                    tracker.runCompensatingStopIfCurrent(stopped) {
                        operations += "diagnostics-stop"
                        compensationEntered.countDown()
                        assertTrue(releaseCompensation.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(compensationEntered.await(5, TimeUnit.SECONDS))
            val userStart =
                executor.submit<Unit> {
                    tracker.withUserStart(action = {
                        operations += "user-start"
                    })
                }

            assertFalse(userStart.isDone)
            releaseCompensation.countDown()
            assertTrue(compensation.get(5, TimeUnit.SECONDS))
            userStart.get(5, TimeUnit.SECONDS)

            assertEquals(listOf("diagnostics-stop", "user-start"), operations)
        } finally {
            executor.shutdownNow()
        }
    }
}
