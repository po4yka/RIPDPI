package com.poyka.ripdpi.activities

import android.os.SystemClock
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage

private const val RecentWarningWindowMillis = 60_000L

/**
 * Telemetry that says a stage failed at some point in this session. Native code keeps it after the problem ends, so
 * it cannot say that the problem is still present.
 *
 * @property count a running total, which restarts with the engine and reads zero in a failed poll.
 * @property marks last-seen values, which can disappear for one poll and come back without a new failure.
 */
internal data class WarningEvidence(
    val count: Long = 0L,
    val marks: Set<Any> = emptySet(),
)

internal fun telemetryWarningEvidence(
    telemetry: ServiceTelemetrySnapshot,
): Map<StatusConnectionActuatorStage, WarningEvidence> {
    val failure = telemetry.runtimeFieldTelemetry.failureClass

    fun failureMark(vararg classes: FailureClass): Set<Any> =
        failure?.takeIf { it in classes }?.let { setOf(it to telemetry.lastFailureAt) }.orEmpty()

    return mapOf(
        StatusConnectionActuatorStage.Network to WarningEvidence(marks = failureMark(FailureClass.NetworkHandover)),
        StatusConnectionActuatorStage.Dns to
            WarningEvidence(
                count = telemetry.tunnelTelemetry.dnsFailuresTotal,
                marks = failureMark(FailureClass.DnsInterference),
            ),
        StatusConnectionActuatorStage.Handshake to WarningEvidence(marks = failureMark(FailureClass.TlsInterference)),
        StatusConnectionActuatorStage.Tunnel to
            WarningEvidence(
                count = telemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount,
                marks = failureMark(FailureClass.TunnelEstablish),
            ),
        StatusConnectionActuatorStage.Route to
            WarningEvidence(
                count = telemetry.proxyTelemetry.routeChanges,
                marks = setOfNotNull(telemetry.proxyTelemetry.lastFallbackAction?.takeIf { it.isNotBlank() }),
            ),
    )
}

/**
 * Keeps a stage's warning for one minute after it gets new evidence. A count is new when it differs from the last
 * nonzero count, and a mark is new when this session has not seen it. Evidence read at the first reading, or after
 * the app was away from the screen, has an unknown age, and it does not count as new.
 */
internal class RecentWarningTracker(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var session: Long? = null
    private var absorbNextReading = true
    private val lastCounts = mutableMapOf<StatusConnectionActuatorStage, Long>()
    private val seenMarks = mutableMapOf<StatusConnectionActuatorStage, MutableSet<Any>>()
    private val newAtMs = mutableMapOf<StatusConnectionActuatorStage, Long>()

    fun rebaseline() {
        absorbNextReading = true
    }

    fun recentStages(
        sessionStartedAt: Long?,
        evidence: Map<StatusConnectionActuatorStage, WarningEvidence>,
    ): Set<StatusConnectionActuatorStage> {
        if (sessionStartedAt != session) {
            session = sessionStartedAt
            lastCounts.clear()
            seenMarks.clear()
            newAtMs.clear()
        }
        val time = now()
        evidence.forEach { (stage, current) ->
            val countIsNew = current.count != 0L && current.count != lastCounts[stage]
            val seen = seenMarks.getOrPut(stage) { mutableSetOf() }
            val markIsNew = !seen.containsAll(current.marks)
            if (current.count != 0L) lastCounts[stage] = current.count
            seen.addAll(current.marks)
            if ((countIsNew || markIsNew) && !absorbNextReading) newAtMs[stage] = time
        }
        absorbNextReading = false
        return newAtMs.filterValues { time - it < RecentWarningWindowMillis }.keys
    }
}
