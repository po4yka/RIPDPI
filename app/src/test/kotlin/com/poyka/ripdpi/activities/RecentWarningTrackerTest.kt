package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Dns
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Handshake
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Route
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Tunnel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private val NoStages = emptySet<StatusConnectionActuatorStage>()

class RecentWarningTrackerTest {
    private var time = 1_000L
    private val tracker = RecentWarningTracker(now = { time })

    private fun read(
        stage: StatusConnectionActuatorStage = Dns,
        count: Long = 0L,
        marks: Set<Any> = emptySet(),
        session: Long? = 1L,
    ): Set<StatusConnectionActuatorStage> {
        tracker.observe(session, mapOf(stage to WarningEvidence(listOf(count), marks)))
        return tracker.recentStages()
    }

    @Test
    fun `a failure stops being recent after one minute without another`() {
        assertEquals(setOf(Dns), read(count = 1L))
        time += 59_999L
        assertEquals(setOf(Dns), read(count = 1L))
        time += 1L
        assertEquals(NoStages, read(count = 1L))
    }

    @Test
    fun `new evidence restarts the minute for its own stage only`() {
        read(stage = Dns, count = 1L)
        time += 30_000L
        read(stage = Route, count = 1L)
        time += 30_000L
        assertEquals(setOf(Route), read(stage = Dns, count = 1L))
        assertEquals(setOf(Dns, Route), read(stage = Dns, count = 2L))
    }

    @Test
    fun `a failure with no screen open keeps its real time`() =
        runTest {
            val store = FakeServiceStateStore()
            val observed = RecentWarningTracker(store, backgroundScope)
            runCurrent()
            store.updateTelemetry(
                ServiceTelemetrySnapshot(
                    tunnelTelemetry = NativeRuntimeSnapshot.idle(source = "tunnel").copy(dnsFailuresTotal = 1L),
                ),
            )
            runCurrent()
            assertEquals(setOf(Dns), observed.recentStages())
        }

    @Test
    fun `a repeating failure of one class restarts the minute`() {
        fun evidence(total: Long) =
            telemetryWarningEvidence(
                ServiceTelemetrySnapshot(
                    proxyTelemetry = NativeRuntimeSnapshot.idle(source = "proxy").copy(tlsFailureEvents = total),
                ),
            )

        tracker.observe(1L, evidence(1L))
        assertEquals(setOf(Handshake), tracker.recentStages())
        time += 60_000L
        tracker.observe(1L, evidence(1L))
        assertEquals(NoStages, tracker.recentStages())
        tracker.observe(1L, evidence(2L))
        assertEquals(setOf(Handshake), tracker.recentStages())
    }

    @Test
    fun `a repeating connection failure warns on the route stage`() {
        fun evidence(total: Long) =
            telemetryWarningEvidence(
                ServiceTelemetrySnapshot(
                    proxyTelemetry = NativeRuntimeSnapshot.idle(source = "proxy").copy(connectionFailureEvents = total),
                ),
            )

        tracker.observe(1L, evidence(1L))
        assertEquals(setOf(Route), tracker.recentStages())
        time += 60_000L
        tracker.observe(1L, evidence(1L))
        assertEquals(NoStages, tracker.recentStages())
        tracker.observe(1L, evidence(2L))
        assertEquals(setOf(Route), tracker.recentStages())
    }

    @Test
    fun `each DNS counter is tracked on its own`() {
        val proxy = NativeRuntimeSnapshot.idle(source = "proxy")
        val tunnel = NativeRuntimeSnapshot.idle(source = "tunnel")

        fun evidence(
            tunnelTotal: Long,
            proxyTotal: Long,
        ) = telemetryWarningEvidence(
            ServiceTelemetrySnapshot(
                tunnelTelemetry = tunnel.copy(dnsFailuresTotal = tunnelTotal),
                proxyTelemetry = proxy.copy(dnsFailureEvents = proxyTotal),
            ),
        )

        tracker.observe(1L, evidence(5L, 10L))
        time += 60_000L
        // A failed proxy poll reads zero for that source only, and the good poll after it changes nothing.
        tracker.observe(1L, evidence(5L, 0L))
        tracker.observe(1L, evidence(5L, 10L))
        assertEquals(NoStages, tracker.recentStages())
    }

    @Test
    fun `a failed poll neither ends a warning nor starts a new one`() {
        read(count = 5L)
        assertEquals(setOf(Dns), read(count = 0L))
        time += 60_000L
        assertEquals(NoStages, read(count = 0L))
        assertEquals(NoStages, read(count = 5L))
    }

    @Test
    fun `an engine restart that lowers the total counts as a new failure`() {
        read(count = 5L)
        time += 60_000L
        read(count = 0L)
        assertEquals(setOf(Dns), read(count = 1L))
    }

    @Test
    fun `a last-seen value that flaps does not restart the minute`() {
        val mark = setOf<Any>("tls")
        assertEquals(setOf(Handshake), read(stage = Handshake, marks = mark))
        time += 30_000L
        read(stage = Handshake)
        read(stage = Handshake, marks = mark)
        time += 30_000L
        assertEquals(NoStages, read(stage = Handshake, marks = mark))
    }

    @Test
    fun `a new session forgets old evidence and its warnings`() {
        read(count = 5L)
        assertEquals(NoStages, read(count = 0L, session = 2L))
        assertEquals(setOf(Dns), read(count = 5L, session = 2L))
    }

    @Test
    fun `telemetry maps to the stage it is evidence for`() {
        val proxy = NativeRuntimeSnapshot.idle(source = "proxy")
        val tunnel = NativeRuntimeSnapshot.idle(source = "tunnel")

        fun evidence(
            stage: StatusConnectionActuatorStage,
            telemetry: ServiceTelemetrySnapshot,
        ) = telemetryWarningEvidence(telemetry).getValue(stage)

        assertEquals(
            3L,
            evidence(
                Dns,
                ServiceTelemetrySnapshot(tunnelTelemetry = tunnel.copy(dnsFailuresTotal = 3L)),
            ).counts.first(),
        )
        assertEquals(
            2L,
            evidence(Route, ServiceTelemetrySnapshot(proxyTelemetry = proxy.copy(routeChanges = 2L))).counts.first(),
        )
        // Every classified failure sets an action, including an unknown one that is not a warning.
        assertEquals(
            WarningEvidence(counts = listOf(0L, 0L)),
            evidence(
                Route,
                ServiceTelemetrySnapshot(proxyTelemetry = proxy.copy(lastFallbackAction = "surface_only")),
            ),
        )
        assertEquals(
            4L,
            evidence(
                Tunnel,
                ServiceTelemetrySnapshot(
                    runtimeFieldTelemetry = RuntimeFieldTelemetry(tunnelRecoveryRetryCount = 4L),
                ),
            ).counts.first(),
        )
        assertEquals(
            setOf<Any>(FailureClass.TlsInterference to null),
            evidence(
                Handshake,
                ServiceTelemetrySnapshot(
                    runtimeFieldTelemetry = RuntimeFieldTelemetry(failureClass = FailureClass.TlsInterference),
                ),
            ).marks,
        )
    }
}
