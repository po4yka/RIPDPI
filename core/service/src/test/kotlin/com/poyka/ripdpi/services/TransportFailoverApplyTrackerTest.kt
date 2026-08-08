package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransportFailoverApplyTrackerTest {
    @Test
    fun `AWG policy resolves to its exact transport target`() {
        val resolution =
            sampleResolution(
                proxyPreferences =
                    RipDpiProxyUIPreferences(
                        awg =
                            AwgActivationRequest(
                                profileId = "awg-fallback",
                                privateKey = "private",
                                peerPublicKey = "public",
                                endpointHost = "192.0.2.1",
                                endpointPort = 51820,
                                interfaceAddressV4 = "10.8.0.2/32",
                            ),
                    ),
            )

        assertEquals(
            TransportFailoverTarget(TransportKindAmneziaWg, "awg-fallback"),
            resolution.transportFailoverTargetOrNull(),
        )
    }

    @Test
    fun `exact runtime receipt confirms application`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()

            assertTrue(tracker.claimApplying(requestId))
            tracker.recordApplied(requestId)

            assertEquals(TransportFailoverApplyOutcome.Applied, tracker.awaitOutcome(requestId, timeoutMillis = 1L))
            assertTrue(runCatching { tracker.begin() }.isFailure)
            tracker.releaseRuntimeOwnership(requestId)
            assertTrue(tracker.begin() > requestId)
        }

    @Test
    fun `stale runtime receipt cannot confirm newer application`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val staleRequestId = tracker.begin()
            assertTrue(runCatching { tracker.begin() }.isFailure)
            assertTrue(tracker.cancel(staleRequestId))
            val currentRequestId = tracker.begin()

            tracker.recordApplied(staleRequestId)

            assertEquals(
                TransportFailoverApplyOutcome.RollbackSafeFailure,
                tracker.awaitOutcome(currentRequestId, timeoutMillis = 1L),
            )
        }

    @Test
    fun `runtime failure does not confirm application`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()

            tracker.recordRollbackSafeFailure(requestId)

            assertEquals(
                TransportFailoverApplyOutcome.RollbackSafeFailure,
                tracker.awaitOutcome(requestId, timeoutMillis = 1L),
            )
        }

    @Test
    fun `failed runtime ownership blocks supersession until runtime releases it`() {
        val tracker = TransportFailoverApplyTracker()
        val requestId = tracker.begin()
        assertTrue(tracker.claimApplying(requestId))

        assertTrue(tracker.recordRollbackSafeFailure(requestId))
        assertTrue(runCatching { tracker.begin() }.isFailure)

        tracker.releaseRuntimeOwnership(requestId)
        assertTrue(tracker.begin() > requestId)
    }

    @Test
    fun `late runtime receipt cannot revive a cancelled application`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()

            tracker.cancel(requestId)

            assertFalse(tracker.claimApplying(requestId))
            assertFalse(tracker.recordApplied(requestId))
            assertEquals(
                TransportFailoverApplyOutcome.RollbackSafeFailure,
                tracker.awaitOutcome(requestId, timeoutMillis = 1L),
            )
        }

    @Test
    fun `timeout cannot revoke runtime after it claims application`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()
            assertTrue(tracker.claimApplying(requestId))

            val outcome = async { tracker.awaitOutcome(requestId, timeoutMillis = 10L) }
            advanceTimeBy(11L)
            runCurrent()

            assertFalse(outcome.isCompleted)
            assertTrue(tracker.recordApplied(requestId))
            assertEquals(TransportFailoverApplyOutcome.Applied, outcome.await())
        }

    @Test
    fun `claimed application becomes failed after bounded runtime window`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()
            assertTrue(tracker.claimApplying(requestId))

            val outcome = async { tracker.awaitOutcome(requestId, timeoutMillis = 10L) }
            advanceTimeBy(21L)
            runCurrent()

            assertEquals(TransportFailoverApplyOutcome.TimedOutInFlight, outcome.await())
            assertTrue(runCatching { tracker.begin() }.isFailure)
            tracker.releaseRuntimeOwnership(requestId)
            assertTrue(tracker.begin() > requestId)
        }

    @Test
    fun `cancellation settlement preserves timed out in-flight outcome`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()
            assertTrue(tracker.claimApplying(requestId))
            val timeout = async { tracker.awaitOutcome(requestId, timeoutMillis = 10L) }
            advanceTimeBy(21L)
            runCurrent()
            assertEquals(TransportFailoverApplyOutcome.TimedOutInFlight, timeout.await())

            assertEquals(
                TransportFailoverApplyOutcome.TimedOutInFlight,
                tracker.settleCancellation(requestId, timeoutMillis = 1L),
            )
        }

    @Test
    fun `late runtime success can confirm a timed out owned application`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()
            assertTrue(tracker.claimApplying(requestId))
            val timeout = async { tracker.awaitOutcome(requestId, timeoutMillis = 10L) }
            advanceTimeBy(21L)
            runCurrent()
            assertEquals(TransportFailoverApplyOutcome.TimedOutInFlight, timeout.await())

            assertTrue(tracker.recordApplied(requestId))
            assertEquals(
                TransportFailoverApplyOutcome.Applied,
                tracker.awaitOutcome(requestId, timeoutMillis = 1L),
            )
            assertTrue(runCatching { tracker.begin() }.isFailure)
            tracker.releaseRuntimeOwnership(requestId)
            assertTrue(tracker.begin() > requestId)
        }

    @Test
    fun `timeout fences a request that runtime has not claimed`() =
        runTest {
            val tracker = TransportFailoverApplyTracker()
            val requestId = tracker.begin()

            assertEquals(
                TransportFailoverApplyOutcome.RollbackSafeFailure,
                tracker.awaitOutcome(requestId, timeoutMillis = 1L),
            )

            assertFalse(tracker.claimApplying(requestId))
            assertFalse(tracker.recordApplied(requestId))
        }
}
