package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Dns
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Handshake
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Route
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage.Tunnel
import org.junit.Assert.assertEquals
import org.junit.Before
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
    ) = tracker.recentStages(session, mapOf(stage to WarningEvidence(count, marks)))

    @Before
    fun takeBaseline() {
        read()
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
    fun `evidence of unknown age is absorbed after the first reading and after returning to the screen`() {
        val fresh = RecentWarningTracker(now = { time })
        val evidence = mapOf(Tunnel to WarningEvidence(count = 5L))
        assertEquals(NoStages, fresh.recentStages(1L, evidence))

        fresh.rebaseline()
        assertEquals(NoStages, fresh.recentStages(1L, mapOf(Tunnel to WarningEvidence(count = 6L))))
        assertEquals(setOf(Tunnel), fresh.recentStages(1L, mapOf(Tunnel to WarningEvidence(count = 7L))))
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
            evidence(Dns, ServiceTelemetrySnapshot(tunnelTelemetry = tunnel.copy(dnsFailuresTotal = 3L))).count,
        )
        assertEquals(
            2L,
            evidence(Route, ServiceTelemetrySnapshot(proxyTelemetry = proxy.copy(routeChanges = 2L))).count,
        )
        assertEquals(
            setOf<Any>("retry"),
            evidence(Route, ServiceTelemetrySnapshot(proxyTelemetry = proxy.copy(lastFallbackAction = "retry"))).marks,
        )
        assertEquals(
            4L,
            evidence(
                Tunnel,
                ServiceTelemetrySnapshot(
                    runtimeFieldTelemetry = RuntimeFieldTelemetry(tunnelRecoveryRetryCount = 4L),
                ),
            ).count,
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
