package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protect controller fail-closed + monitor parity (decision 1).
 *
 * The Xray protect controller wraps VpnService.protect(int) directly and MUST:
 * - return true only when protect succeeds,
 * - report a false / thrown protect through the SAME VpnProtectFailureMonitor the
 *   native path uses (so a denial -> XrayProviderFailureClass.ProtectFailure),
 * - fail-closed (never return true) on throw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnServiceXrayProtectControllerTest {
    @Test
    fun `returns true and records nothing on a successful protect`() {
        val monitor = RecordingProtectFailureMonitor()
        val controller =
            VpnServiceXrayProtectController(
                fdProtector = { true },
                protectFailureMonitor = monitor,
                clock = { 7L },
            )

        assertTrue(controller.protect(42))
        assertTrue(monitor.recorded.isEmpty())
        assertEquals(null, controller.lastFailureDetail)
    }

    @Test
    fun `returns false and reports a permission failure when protect returns false`() {
        val monitor = RecordingProtectFailureMonitor()
        val controller =
            VpnServiceXrayProtectController(
                fdProtector = { false },
                protectFailureMonitor = monitor,
                clock = { 7L },
            )

        assertFalse(controller.protect(13))
        assertEquals(1, monitor.recorded.size)
        val event = monitor.recorded.single()
        assertEquals(13, event.fd)
        assertTrue(event.reason is FailureReason.PermissionLost)
        assertEquals(7L, event.detectedAt)
        assertEquals(event.detail, controller.lastFailureDetail)
    }

    @Test
    fun `fails closed and reports native error when protect throws`() {
        val monitor = RecordingProtectFailureMonitor()
        val controller =
            VpnServiceXrayProtectController(
                fdProtector = { error("boom") },
                protectFailureMonitor = monitor,
            )

        assertFalse("must fail-closed on throw", controller.protect(99))
        assertEquals(1, monitor.recorded.size)
        assertTrue(monitor.recorded.single().reason is FailureReason.NativeError)
    }

    @Test
    fun `fails closed and reports permission lost on SecurityException`() {
        val monitor = RecordingProtectFailureMonitor()
        val controller =
            VpnServiceXrayProtectController(
                fdProtector = { throw SecurityException("denied") },
                protectFailureMonitor = monitor,
            )

        assertFalse(controller.protect(1))
        assertTrue(monitor.recorded.single().reason is FailureReason.PermissionLost)
    }

    @Test
    fun `clearLastFailure resets the recorded detail`() {
        val controller =
            VpnServiceXrayProtectController(
                fdProtector = { false },
                protectFailureMonitor = RecordingProtectFailureMonitor(),
            )
        controller.protect(5)
        assertTrue(controller.lastFailureDetail != null)
        controller.clearLastFailure()
        assertEquals(null, controller.lastFailureDetail)
    }

    @Test
    fun `monitor flow delivers the same event type the native protector emits`() =
        runTest {
            val monitor = InMemoryVpnProtectFailureMonitor()
            val collected = mutableListOf<VpnProtectFailureEvent>()
            val job = launch { monitor.events.toList(collected) }
            // Drive the collector to its subscription point BEFORE emitting:
            // the monitor's SharedFlow has replay=0, so an emit with no active
            // subscriber is dropped. runCurrent() guarantees the launched
            // collector has subscribed before protect() reports.
            runCurrent()
            val controller =
                VpnServiceXrayProtectController(
                    fdProtector = { false },
                    protectFailureMonitor = monitor,
                )
            controller.protect(3)
            // Let the now-subscribed collector observe the emission.
            runCurrent()
            job.cancel()
            assertTrue(collected.any { it.fd == 3 && it.reason is FailureReason.PermissionLost })
        }
}

internal class RecordingProtectFailureMonitor : VpnProtectFailureMonitor {
    val recorded = mutableListOf<VpnProtectFailureEvent>()
    private val flow = MutableSharedFlow<VpnProtectFailureEvent>(extraBufferCapacity = 8)

    override val events: SharedFlow<VpnProtectFailureEvent> = flow.asSharedFlow()

    override fun report(event: VpnProtectFailureEvent) {
        recorded.add(event)
        flow.tryEmit(event)
    }
}
