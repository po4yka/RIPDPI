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
    fun `start callback and accepted stop have no tracker arbiter lock inversion`() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val tracker = RuntimeResumeIntentTracker(authority)
        val arbiter = ServiceIntentArbiter(authority)
        val receipt =
            authority.supersede(
                com.poyka.ripdpi.data.RuntimeUserCommand
                    .Start(com.poyka.ripdpi.data.Mode.Proxy),
            )
        val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
        val resumeLease = tracker.captureResumeLease()
        val callbackEntered = CountDownLatch(1)
        val stopOwnsArbiter = CountDownLatch(1)
        val startAccepted =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val failures =
            java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
        val start =
            Thread {
                try {
                    startAccepted.set(
                        tracker.withUserStart(action = {
                            callbackEntered.countDown()
                            check(stopOwnsArbiter.await(2, TimeUnit.SECONDS))
                            arbiter.serialize { arbiter.isCurrent(lease) }
                        }, isAccepted = { it }),
                    )
                } catch (failure: Throwable) {
                    failures.set(failure)
                }
            }.apply { isDaemon = true }
        val stop =
            Thread {
                try {
                    check(callbackEntered.await(2, TimeUnit.SECONDS))
                    val stopped = authority.supersede(com.poyka.ripdpi.data.RuntimeUserCommand.Stop)
                    arbiter.serialize {
                        checkNotNull(arbiter.dispatchExplicit(stopped))
                        stopOwnsArbiter.countDown()
                        tracker.recordAcceptedStop()
                    }
                } catch (failure: Throwable) {
                    failures.set(failure)
                }
            }.apply { isDaemon = true }
        start.start()
        stop.start()
        stop.join(3_000)
        start.join(3_000)
        assertFalse("Accepted stop deadlocked waiting for the tracker", stop.isAlive)
        assertFalse("Start deadlocked waiting for the arbiter", start.isAlive)
        failures.get()?.let { throw it }
        assertFalse(startAccepted.get())
        assertEquals(
            UserRuntimeIntent.Stopped,
            (tracker.ownership(resumeLease) as ResumeLeaseOwnership.Superseded).intent,
        )
        assertFalse(arbiter.isCurrent(lease))
    }

    @Test
    fun `accepting an already requested start preserves a later scan lease`() {
        val tracker =
            RuntimeResumeIntentTracker(
                com.poyka.ripdpi.data
                    .testPauseAuthority(),
            )
        tracker.withUserStart(action = {})
        val lease = tracker.captureResumeLease()

        tracker.recordAcceptedStart()

        assertEquals(ResumeLeaseOwnership.Owned, tracker.ownership(lease))
    }

    @Test
    fun `accepted start after an intervening stop supersedes the scan lease`() {
        val tracker =
            RuntimeResumeIntentTracker(
                com.poyka.ripdpi.data
                    .testPauseAuthority(),
            )
        tracker.withUserStart(action = {})
        val lease = tracker.captureResumeLease()
        tracker.recordAcceptedStop()

        tracker.recordAcceptedStart()

        val ownership = tracker.ownership(lease) as ResumeLeaseOwnership.Superseded
        assertEquals(UserRuntimeIntent.Running, ownership.intent)
    }

    @Test
    fun `stale stopped generation cannot compensate after newer user start`() {
        val tracker =
            RuntimeResumeIntentTracker(
                com.poyka.ripdpi.data
                    .testPauseAuthority(),
            )
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
        val tracker =
            RuntimeResumeIntentTracker(
                com.poyka.ripdpi.data
                    .testPauseAuthority(),
            )
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

            userStart.get(5, TimeUnit.SECONDS)
            assertTrue(userStart.isDone)
            releaseCompensation.countDown()
            assertTrue(compensation.get(5, TimeUnit.SECONDS))
            userStart.get(5, TimeUnit.SECONDS)

            assertEquals(listOf("diagnostics-stop", "user-start"), operations)
        } finally {
            executor.shutdownNow()
        }
    }
}
