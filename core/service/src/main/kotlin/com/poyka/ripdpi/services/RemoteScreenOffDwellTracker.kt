package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneCounters
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneDelta
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.deltaFrom

internal class RemoteScreenOffDwellTracker(
    private val minimumDwellMs: Long,
    private val evidenceRecorder: (DeviceRuntimeEvidence) -> Unit = {},
) {
    private var screenOffStartedAt: Long? = null
    private var countersBefore: DeviceRuntimeDataPlaneCounters? = null
    private var serviceStartedAtBefore: Long? = null
    private var restartCountBefore: Int? = null
    private var screenOffProbeReady = false
    private var screenOffProbePassed: Boolean? = null

    fun observe(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        running: Boolean,
        interactive: Boolean,
        telemetry: ServiceTelemetrySnapshot = ServiceTelemetrySnapshot(),
    ): RemoteScreenOffDwellObservation =
        if (interactive) {
            observeInteractive(elapsedNowMs, observedAtMillis, telemetry)
        } else {
            observeScreenOff(elapsedNowMs, observedAtMillis, running, telemetry)
        }

    private fun observeScreenOff(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        running: Boolean,
        telemetry: ServiceTelemetrySnapshot,
    ): RemoteScreenOffDwellObservation =
        when {
            !running -> {
                completeStoppedDuringScreenOff(elapsedNowMs, observedAtMillis, telemetry)
            }

            screenOffStartedAt == null -> {
                recordScreenOffStarted(elapsedNowMs, observedAtMillis, telemetry)
                RemoteScreenOffDwellObservation.None
            }

            isReadyForScreenOffProbe(elapsedNowMs) -> {
                markScreenOffProbeReady(elapsedNowMs)
            }

            else -> {
                RemoteScreenOffDwellObservation.None
            }
        }

    private fun observeInteractive(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
    ): RemoteScreenOffDwellObservation {
        val startedAt = screenOffStartedAt ?: return RemoteScreenOffDwellObservation.None
        val durationMs = elapsedNowMs - startedAt
        return if (durationMs < minimumDwellMs) {
            complete(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                countersAfter = telemetry.toRuntimeDataPlaneCounters(),
                durationMs = durationMs,
                status = RemoteDeviceAcceptanceStatus.Incomplete,
                outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                reason = DeviceRuntimeBackgroundSurvivalReason.TooShort,
                errorClass = null,
            ).asCompletedObservation()
        } else if (screenOffProbePassed != true) {
            complete(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                countersAfter = telemetry.toRuntimeDataPlaneCounters(),
                durationMs = durationMs,
                status = RemoteDeviceAcceptanceStatus.Incomplete,
                outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                reason = DeviceRuntimeBackgroundSurvivalReason.ScreenOffProbeMissing,
                errorClass = null,
            ).asCompletedObservation()
        } else {
            RemoteScreenOffDwellObservation.ReadyForAfterWakeProbe(durationMs.coerceAtLeast(0L))
        }
    }

    private fun completeStoppedDuringScreenOff(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
    ): RemoteScreenOffDwellObservation {
        val startedAt = screenOffStartedAt ?: return RemoteScreenOffDwellObservation.None
        return complete(
            elapsedNowMs = elapsedNowMs,
            observedAtMillis = observedAtMillis,
            phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
            countersAfter = telemetry.toRuntimeDataPlaneCounters(),
            durationMs = elapsedNowMs - startedAt,
            status = RemoteDeviceAcceptanceStatus.Fail,
            outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
            reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
            errorClass = ErrorScreenOffServiceStopped,
        ).asCompletedObservation()
    }

    private fun recordScreenOffStarted(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
    ) {
        screenOffStartedAt = elapsedNowMs
        countersBefore = telemetry.toRuntimeDataPlaneCounters()
        serviceStartedAtBefore = telemetry.serviceStartedAt
        restartCountBefore = telemetry.restartCount
        evidenceRecorder(
            DeviceRuntimeEvidence.BackgroundSurvival(
                mode = Mode.VPN,
                phase = DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted,
                outcome = DeviceRuntimeBackgroundSurvivalOutcome.Pending,
                countersBefore = requireNotNull(countersBefore),
                observedAtMillis = observedAtMillis,
            ),
        )
    }

    fun recordScreenOffProbe(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        screenOffProbePassed: Boolean,
    ): RemoteScreenOffDwellObservation =
        screenOffStartedAt?.let { startedAt ->
            recordScreenOffProbeForStarted(
                startedAt = startedAt,
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                telemetry = telemetry,
                screenOffProbePassed = screenOffProbePassed,
            )
        } ?: RemoteScreenOffDwellObservation.None

    private fun recordScreenOffProbeForStarted(
        startedAt: Long,
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        screenOffProbePassed: Boolean,
    ): RemoteScreenOffDwellObservation {
        val durationMs = elapsedNowMs - startedAt
        val countersAfter = telemetry.toRuntimeDataPlaneCounters()
        val before = countersBefore ?: countersAfter
        val delta = countersAfter.deltaFrom(before)
        val verdict =
            when {
                !screenOffProbePassed -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Fail,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                        reason = DeviceRuntimeBackgroundSurvivalReason.PostActionProbeFailed,
                        errorClass = ErrorPostActionProbe,
                    )
                }

                hasServiceRestarted(telemetry) -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Fail,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                        reason = DeviceRuntimeBackgroundSurvivalReason.ServiceRestarted,
                        errorClass = ErrorScreenOffServiceRestarted,
                    )
                }

                !delta.hasForwarding -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Incomplete,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                        reason = DeviceRuntimeBackgroundSurvivalReason.NoDataPlaneDelta,
                        errorClass = null,
                    )
                }

                else -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Incomplete,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Passed,
                        reason = null,
                        errorClass = null,
                    )
                }
            }
        recordEvidence(
            phase = DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe,
            observedAtMillis = observedAtMillis,
            countersBefore = before,
            countersAfter = countersAfter,
            counterDelta = delta,
            durationMs = durationMs,
            outcome = verdict.outcome,
            reason = verdict.reason,
        )
        return if (verdict.outcome == DeviceRuntimeBackgroundSurvivalOutcome.Passed) {
            this.screenOffProbePassed = true
            RemoteScreenOffDwellObservation.None
        } else {
            complete(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                countersAfter = countersAfter,
                durationMs = durationMs,
                status = verdict.status,
                outcome = verdict.outcome,
                reason = verdict.reason,
                errorClass = verdict.errorClass,
                recordEvidence = false,
            ).asCompletedObservation()
        }
    }

    private fun isReadyForScreenOffProbe(elapsedNowMs: Long): Boolean =
        !screenOffProbeReady &&
            screenOffStartedAt
                ?.let { startedAt -> elapsedNowMs - startedAt >= minimumDwellMs }
                ?: false

    private fun markScreenOffProbeReady(elapsedNowMs: Long): RemoteScreenOffDwellObservation {
        screenOffProbeReady = true
        val durationMs = screenOffStartedAt?.let { startedAt -> elapsedNowMs - startedAt } ?: 0L
        return RemoteScreenOffDwellObservation.ReadyForScreenOffProbe(durationMs.coerceAtLeast(0L))
    }

    fun completeAfterWake(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        afterWakeProbePassed: Boolean,
    ): RemoteScreenOffDwellResult {
        val startedAt = screenOffStartedAt
        val durationMs = startedAt?.let { elapsedNowMs - it } ?: 0L
        val countersAfter = telemetry.toRuntimeDataPlaneCounters()
        val before = countersBefore ?: countersAfter
        val delta = countersAfter.deltaFrom(before)
        val result =
            when {
                screenOffProbePassed != true -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Incomplete,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                        reason = DeviceRuntimeBackgroundSurvivalReason.ScreenOffProbeMissing,
                        errorClass = null,
                    )
                }

                hasServiceRestarted(telemetry) -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Fail,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                        reason = DeviceRuntimeBackgroundSurvivalReason.ServiceRestarted,
                        errorClass = ErrorScreenOffServiceRestarted,
                    )
                }

                !afterWakeProbePassed -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Fail,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                        reason = DeviceRuntimeBackgroundSurvivalReason.PostActionProbeFailed,
                        errorClass = ErrorPostActionProbe,
                    )
                }

                delta.hasForwarding -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Pass,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Passed,
                        reason = null,
                        errorClass = null,
                    )
                }

                else -> {
                    RemoteScreenOffDwellVerdict(
                        status = RemoteDeviceAcceptanceStatus.Incomplete,
                        outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                        reason = DeviceRuntimeBackgroundSurvivalReason.NoDataPlaneDelta,
                        errorClass = null,
                    )
                }
            }
        return complete(
            elapsedNowMs = elapsedNowMs,
            observedAtMillis = observedAtMillis,
            phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
            countersAfter = countersAfter,
            durationMs = durationMs,
            status = result.status,
            outcome = result.outcome,
            reason = result.reason,
            errorClass = result.errorClass,
        )
    }

    fun cancel(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
    ): RemoteScreenOffDwellResult? {
        val startedAt = screenOffStartedAt ?: return null
        return complete(
            elapsedNowMs = elapsedNowMs,
            observedAtMillis = observedAtMillis,
            phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
            countersAfter = telemetry.toRuntimeDataPlaneCounters(),
            durationMs = elapsedNowMs - startedAt,
            status = RemoteDeviceAcceptanceStatus.Incomplete,
            outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
            reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
            errorClass = null,
        )
    }

    private fun complete(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        phase: DeviceRuntimeBackgroundSurvivalPhase,
        countersAfter: DeviceRuntimeDataPlaneCounters,
        durationMs: Long,
        status: RemoteDeviceAcceptanceStatus,
        outcome: DeviceRuntimeBackgroundSurvivalOutcome,
        reason: DeviceRuntimeBackgroundSurvivalReason?,
        errorClass: String?,
        recordEvidence: Boolean = true,
    ): RemoteScreenOffDwellResult {
        val before = countersBefore ?: countersAfter
        val delta = countersAfter.deltaFrom(before)
        val normalizedDurationMs = durationMs.coerceAtLeast(0L)
        if (recordEvidence) {
            recordEvidence(
                phase = phase,
                observedAtMillis = observedAtMillis,
                countersBefore = before,
                countersAfter = countersAfter,
                counterDelta = delta,
                durationMs = normalizedDurationMs,
                outcome = outcome,
                reason = reason,
            )
        }
        clear()
        return RemoteScreenOffDwellResult(
            status = status,
            errorClass = errorClass,
            durationMs = normalizedDurationMs,
            counterDelta = delta,
            completedAtElapsedMs = elapsedNowMs,
        )
    }

    private fun recordEvidence(
        phase: DeviceRuntimeBackgroundSurvivalPhase,
        observedAtMillis: Long,
        countersBefore: DeviceRuntimeDataPlaneCounters,
        countersAfter: DeviceRuntimeDataPlaneCounters?,
        counterDelta: DeviceRuntimeDataPlaneDelta?,
        durationMs: Long?,
        outcome: DeviceRuntimeBackgroundSurvivalOutcome,
        reason: DeviceRuntimeBackgroundSurvivalReason?,
    ) {
        evidenceRecorder(
            DeviceRuntimeEvidence.BackgroundSurvival(
                mode = Mode.VPN,
                phase = phase,
                outcome = outcome,
                reason = reason,
                screenOffDurationMs = durationMs?.coerceAtLeast(0L),
                countersBefore = countersBefore,
                countersAfter = countersAfter,
                counterDelta = counterDelta,
                observedAtMillis = observedAtMillis,
            ),
        )
    }

    private fun hasServiceRestarted(telemetry: ServiceTelemetrySnapshot): Boolean =
        serviceStartedAtBefore != telemetry.serviceStartedAt ||
            restartCountBefore != telemetry.restartCount

    private fun clear() {
        screenOffStartedAt = null
        countersBefore = null
        serviceStartedAtBefore = null
        restartCountBefore = null
        screenOffProbeReady = false
        screenOffProbePassed = null
    }
}

internal sealed interface RemoteScreenOffDwellObservation {
    data object None : RemoteScreenOffDwellObservation

    data class ReadyForScreenOffProbe(
        val durationMs: Long,
    ) : RemoteScreenOffDwellObservation

    data class ReadyForAfterWakeProbe(
        val durationMs: Long,
    ) : RemoteScreenOffDwellObservation

    data class Completed(
        val result: RemoteScreenOffDwellResult,
    ) : RemoteScreenOffDwellObservation
}

internal data class RemoteScreenOffDwellResult(
    val status: RemoteDeviceAcceptanceStatus,
    val errorClass: String?,
    val durationMs: Long?,
    val counterDelta: DeviceRuntimeDataPlaneDelta,
    val completedAtElapsedMs: Long,
)

private data class RemoteScreenOffDwellVerdict(
    val status: RemoteDeviceAcceptanceStatus,
    val outcome: DeviceRuntimeBackgroundSurvivalOutcome,
    val reason: DeviceRuntimeBackgroundSurvivalReason?,
    val errorClass: String?,
)

private fun RemoteScreenOffDwellResult.asCompletedObservation(): RemoteScreenOffDwellObservation =
    RemoteScreenOffDwellObservation.Completed(this)

private fun ServiceTelemetrySnapshot.toRuntimeDataPlaneCounters(): DeviceRuntimeDataPlaneCounters {
    val nativeStats =
        listOf(proxyTelemetry, relayTelemetry, warpTelemetry, awgTelemetry, tunnelTelemetry)
            .map(NativeRuntimeSnapshot::tunnelStats)
            .fold(TunnelStats(), ::sumTunnelStats)
    return DeviceRuntimeDataPlaneCounters(
        tunnelTxPackets = tunnelStats.txPackets.coerceAtLeast(0L),
        tunnelTxBytes = tunnelStats.txBytes.coerceAtLeast(0L),
        tunnelRxPackets = tunnelStats.rxPackets.coerceAtLeast(0L),
        tunnelRxBytes = tunnelStats.rxBytes.coerceAtLeast(0L),
        nativeTxPackets = nativeStats.txPackets.coerceAtLeast(0L),
        nativeTxBytes = nativeStats.txBytes.coerceAtLeast(0L),
        nativeRxPackets = nativeStats.rxPackets.coerceAtLeast(0L),
        nativeRxBytes = nativeStats.rxBytes.coerceAtLeast(0L),
    )
}

private fun sumTunnelStats(
    left: TunnelStats,
    right: TunnelStats,
): TunnelStats =
    TunnelStats(
        txPackets = saturatedSum(left.txPackets, right.txPackets),
        txBytes = saturatedSum(left.txBytes, right.txBytes),
        rxPackets = saturatedSum(left.rxPackets, right.rxPackets),
        rxBytes = saturatedSum(left.rxBytes, right.rxBytes),
    )

private fun saturatedSum(
    left: Long,
    right: Long,
): Long =
    runCatching { Math.addExact(left.coerceAtLeast(0L), right.coerceAtLeast(0L)) }
        .getOrDefault(Long.MAX_VALUE)
