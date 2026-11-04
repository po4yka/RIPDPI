package com.poyka.ripdpi.activities

import android.os.SystemClock
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val RecentWarningWindowMillis = 60_000L

/**
 * Telemetry that says a stage failed at some point in this session. Native code keeps it after the problem ends, so
 * it cannot say that the problem is still present.
 *
 * @property counts running totals, which restart with the engine and read zero in a failed poll.
 * @property marks last-seen values, which can disappear for one poll and come back without a new failure.
 */
internal data class WarningEvidence(
    val counts: List<Long> = emptyList(),
    val marks: Set<Any> = emptySet(),
)

internal fun telemetryWarningEvidence(
    telemetry: ServiceTelemetrySnapshot,
): Map<StatusConnectionActuatorStage, WarningEvidence> {
    val failure = telemetry.runtimeFieldTelemetry.failureClass
    val proxy = telemetry.proxyTelemetry

    fun failureMark(failureClass: FailureClass): Set<Any> =
        if (failure == failureClass) setOf(failureClass to telemetry.lastFailureAt) else emptySet()

    return mapOf(
        StatusConnectionActuatorStage.Network to WarningEvidence(marks = failureMark(FailureClass.NetworkHandover)),
        StatusConnectionActuatorStage.Dns to
            WarningEvidence(
                counts = listOf(telemetry.tunnelTelemetry.dnsFailuresTotal, proxy.dnsFailureEvents),
                marks = failureMark(FailureClass.DnsInterference),
            ),
        StatusConnectionActuatorStage.Handshake to
            WarningEvidence(
                counts = listOf(proxy.tlsFailureEvents),
                marks = failureMark(FailureClass.TlsInterference),
            ),
        StatusConnectionActuatorStage.Tunnel to
            WarningEvidence(
                counts = listOf(telemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount),
                marks = failureMark(FailureClass.TunnelEstablish),
            ),
        StatusConnectionActuatorStage.Route to
            WarningEvidence(counts = listOf(proxy.routeChanges, proxy.connectionFailureEvents)),
    )
}

/**
 * Keeps a stage's warning for one minute after it gets new evidence. A count is new when it differs from its last
 * nonzero value, and a mark is new when this session has not seen it. It reads telemetry for the whole process, so a
 * failure gets its real time even while no screen is open.
 */
@Singleton
class RecentWarningTracker internal constructor(
    private val now: () -> Long,
) {
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        @ApplicationScope scope: CoroutineScope,
    ) : this(SystemClock::elapsedRealtime) {
        scope.launch {
            serviceStateStore.telemetry.collect { telemetry ->
                observe(telemetry.serviceStartedAt, telemetryWarningEvidence(telemetry))
            }
        }
    }

    private var session: Long? = null
    private val lastCounts = mutableMapOf<Pair<StatusConnectionActuatorStage, Int>, Long>()
    private val seenMarks = mutableMapOf<StatusConnectionActuatorStage, MutableSet<Any>>()
    private val newAtMs = mutableMapOf<StatusConnectionActuatorStage, Long>()

    @Synchronized
    internal fun observe(
        sessionStartedAt: Long?,
        evidence: Map<StatusConnectionActuatorStage, WarningEvidence>,
    ) {
        if (sessionStartedAt != session) {
            session = sessionStartedAt
            lastCounts.clear()
            seenMarks.clear()
            newAtMs.clear()
        }
        val time = now()
        evidence.forEach { (stage, current) ->
            var isNew = false
            current.counts.forEachIndexed { index, count ->
                if (count != 0L && lastCounts.put(stage to index, count) != count) isNew = true
            }
            if (seenMarks.getOrPut(stage) { mutableSetOf() }.addAll(current.marks)) isNew = true
            if (isNew) newAtMs[stage] = time
        }
    }

    @Synchronized
    fun recentStages(): Set<StatusConnectionActuatorStage> {
        val time = now()
        return newAtMs.filterValues { time - it < RecentWarningWindowMillis }.keys.toSet()
    }
}
