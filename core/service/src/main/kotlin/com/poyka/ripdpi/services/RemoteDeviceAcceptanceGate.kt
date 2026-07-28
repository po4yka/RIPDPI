package com.poyka.ripdpi.services

import android.os.SystemClock
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneCounters
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.DeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultRemoteDeviceAcceptanceGate
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val screenStateObserver: ScreenStateObserver,
        private val baselineProbe: RemoteDeviceAcceptanceBaselineProbe,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val deviceRuntimeEvidenceStore: DeviceRuntimeEvidenceStore,
        private val evidenceWriter: RemoteDeviceAcceptanceEvidenceWriter,
    ) : RemoteDeviceAcceptanceGate {
        private val _report = MutableStateFlow(RemoteDeviceAcceptanceReport())
        override val report: StateFlow<RemoteDeviceAcceptanceReport> = _report.asStateFlow()
        private var runJob: Job? = null
        private var activeRunOrdinal = 0L

        override fun start(scope: CoroutineScope) {
            val previousRun = runJob
            activeRunOrdinal += 1L
            val run =
                RemoteAcceptanceRun(
                    ordinal = activeRunOrdinal,
                    generation = UUID.randomUUID().toString(),
                )
            runJob =
                scope.launch {
                    previousRun?.cancelAndJoin()
                    if (!isCurrent(run)) return@launch
                    evidenceWriter.beginRun(run.generation, System.currentTimeMillis())
                    if (!isCurrent(run)) return@launch
                    runAcceptance(run)
                }
        }

        override fun renderRedactedReport(): String = renderRemoteDeviceAcceptanceReport(_report.value)

        /** Cancel-safe: an in-flight screen-off check records a terminal cancellation event. */
        private suspend fun runAcceptance(run: RemoteAcceptanceRun) {
            val startedAt = SystemClock.elapsedRealtime()
            val initialSnapshot = serviceStateStore.telemetry.value
            _report.value =
                RemoteDeviceAcceptanceReport(
                    status = RemoteDeviceAcceptanceStatus.Running,
                    device = captureRemoteDeviceAcceptanceDevice(),
                    transportKind = sanitizeTransportKind(initialSnapshot.relayTelemetry.protocolKind),
                )
            val baseline = baselineProbe.capture(initialSnapshot)
            if (!isCurrent(run)) return
            _report.value = baseline
            observeGuidedSteps(run, startedAt, initialSnapshot)
        }

        private suspend fun observeGuidedSteps(
            run: RemoteAcceptanceRun,
            startedAt: Long,
            initialSnapshot: ServiceTelemetrySnapshot,
        ) {
            val state =
                GuidedRunState(
                    baselineUnderlay = captureUnderlayTransport(),
                    lastHandoverState = initialSnapshot.networkHandoverState,
                    screenOffDwellTracker =
                        RemoteScreenOffDwellTracker(
                            minimumDwellMs = RemoteAcceptanceScreenOffDwellMs,
                            evidenceRecorder = { event -> recordBackgroundEvidence(run, event) },
                        ),
                )

            try {
                combine(
                    serviceStateStore.status,
                    serviceStateStore.telemetry,
                    screenStateObserver.isInteractive,
                ) { status, telemetry, interactive ->
                    GuidedObservation(
                        status.first,
                        status.second,
                        telemetry,
                        interactive,
                        captureUnderlayTransport(),
                    )
                }.collect { observation ->
                    if (isCurrent(run)) {
                        handleGuidedObservation(run, startedAt, state, observation)
                    }
                }
            } catch (cancelled: CancellationException) {
                state.cancelScreenOff(serviceStateStore.telemetry.value)
                throw cancelled
            }
        }

        private suspend fun handleGuidedObservation(
            run: RemoteAcceptanceRun,
            startedAt: Long,
            state: GuidedRunState,
            observation: GuidedObservation,
        ) {
            val running = observation.isVpnRunning
            val screenOffObservation =
                state.recordLifecycle(
                    running = running,
                    interactive = observation.interactive,
                    elapsedNowMs = SystemClock.elapsedRealtime(),
                    observedAtMillis = System.currentTimeMillis(),
                    telemetry = observation.telemetry,
                )
            recordDirectFallback(startedAt, observation)
            when (screenOffObservation) {
                is RemoteScreenOffDwellObservation.Completed -> {
                    applyScreenOffResult(run, startedAt, screenOffObservation.result)
                }

                is RemoteScreenOffDwellObservation.ReadyForScreenOffProbe -> {
                    handleScreenOffProbe(run, startedAt, state)
                }

                is RemoteScreenOffDwellObservation.ReadyForAfterWakeProbe -> {
                    handleAfterWakeProbe(run, startedAt, state)
                }

                RemoteScreenOffDwellObservation.None -> {
                }
            }

            val triggers = guidedTriggers(state, observation, running)
            state.lastHandoverState = observation.telemetry.networkHandoverState
            if (triggers.any) {
                val result =
                    guidedDataPlaneResult(
                        baselineProbe.capture(observation.telemetry).acceptanceDataPlaneStatus(),
                    ).takeIf { isCurrent(run) }
                val latestObservation = currentGuidedObservation()
                if (result != null && isCurrent(run) && latestObservation.isVpnRunning) {
                    applyGuidedResult(startedAt, state, observation, triggers, result.status, result.errorClass)
                }
            }
        }

        private suspend fun handleScreenOffProbe(
            run: RemoteAcceptanceRun,
            startedAt: Long,
            state: GuidedRunState,
        ) {
            val beforeProbeTelemetry = serviceStateStore.telemetry.value
            val countersBefore = beforeProbeTelemetry.toRuntimeDataPlaneCounters()
            val verified = baselineProbe.captureDataPlanePassed(beforeProbeTelemetry).takeIf { isCurrent(run) }
            val afterBaseline = currentGuidedObservation()
            if (verified != null && isCurrent(run)) {
                when {
                    !afterBaseline.isVpnRunning -> {
                        state
                            .interruptServiceStoppedProbe(afterBaseline.telemetry, countersBefore)
                            ?.let { result -> applyScreenOffResult(run, startedAt, result) }
                    }

                    afterBaseline.interactive -> {
                        state
                            .interruptScreenChangedProbe(afterBaseline.telemetry, countersBefore)
                            ?.let { result -> applyScreenOffResult(run, startedAt, result) }
                    }

                    else -> {
                        recordCompletedScreenOffProbe(
                            run = run,
                            startedAt = startedAt,
                            state = state,
                            beforeProbeTelemetry = beforeProbeTelemetry,
                            countersBefore = countersBefore,
                            verified = verified,
                        )
                    }
                }
            }
        }

        private suspend fun handleAfterWakeProbe(
            run: RemoteAcceptanceRun,
            startedAt: Long,
            state: GuidedRunState,
        ) {
            val beforeProbeTelemetry = serviceStateStore.telemetry.value
            val countersBefore = beforeProbeTelemetry.toRuntimeDataPlaneCounters()
            val verified = baselineProbe.captureDataPlanePassed(beforeProbeTelemetry).takeIf { isCurrent(run) }
            val afterBaseline = currentGuidedObservation()
            if (verified != null && isCurrent(run)) {
                if (!afterBaseline.isVpnRunning || !afterBaseline.interactive) return
                val telemetrySample = awaitFreshTelemetryAfter(beforeProbeTelemetry.updatedAt)
                val latestObservation = currentGuidedObservation()
                if (isCurrent(run)) {
                    val result =
                        state.completeScreenOffAfterWake(
                            elapsedNowMs = SystemClock.elapsedRealtime(),
                            observedAtMillis = System.currentTimeMillis(),
                            telemetry = telemetrySample.snapshot ?: latestObservation.telemetry,
                            countersBefore = countersBefore,
                            afterWakeProbePassed = verified,
                            telemetryFresh = telemetrySample.fresh,
                        )
                    applyScreenOffResult(run, startedAt, result)
                }
            }
        }

        private suspend fun recordCompletedScreenOffProbe(
            run: RemoteAcceptanceRun,
            startedAt: Long,
            state: GuidedRunState,
            beforeProbeTelemetry: ServiceTelemetrySnapshot,
            countersBefore: DeviceRuntimeDataPlaneCounters,
            verified: Boolean,
        ) {
            val telemetrySample = awaitFreshTelemetryAfter(beforeProbeTelemetry.updatedAt)
            val latestObservation = currentGuidedObservation()
            if (isCurrent(run)) {
                val telemetryAfterProbe = telemetrySample.snapshot ?: latestObservation.telemetry
                val completedResult =
                    if (!latestObservation.isVpnRunning) {
                        state.interruptServiceStoppedProbe(telemetryAfterProbe, countersBefore)
                    } else if (latestObservation.interactive) {
                        state.interruptScreenChangedProbe(telemetryAfterProbe, countersBefore)
                    } else {
                        (
                            state.recordScreenOffProbe(
                                elapsedNowMs = SystemClock.elapsedRealtime(),
                                observedAtMillis = System.currentTimeMillis(),
                                telemetry = telemetryAfterProbe,
                                countersBefore = countersBefore,
                                screenOffProbePassed = verified,
                                telemetryFresh = telemetrySample.fresh,
                            ) as? RemoteScreenOffDwellObservation.Completed
                        )?.result
                    }
                if (completedResult != null) {
                    applyScreenOffResult(run, startedAt, completedResult)
                }
            }
        }

        private fun recordDirectFallback(
            startedAt: Long,
            observation: GuidedObservation,
        ) {
            if (!observation.telemetry.relayFailed) return
            updateStep(
                stepId = StepNoDirectEgress,
                status = RemoteDeviceAcceptanceStatus.Fail,
                errorClass = ErrorDirectEgress,
                durationMs = elapsedSince(startedAt),
            )
        }

        private fun guidedTriggers(
            state: GuidedRunState,
            observation: GuidedObservation,
            running: Boolean,
        ): GuidedTriggers =
            GuidedTriggers(
                reconnect =
                    running &&
                        state.sawDisconnected &&
                        stepStatus(StepReconnect) != RemoteDeviceAcceptanceStatus.Pass,
                handover =
                    running &&
                        observation.telemetry.networkHandoverState == NetworkHandoverStates.Revalidated &&
                        state.lastHandoverState != NetworkHandoverStates.Revalidated &&
                        isWifiMobileTransition(state.baselineUnderlay, observation.underlayTransport) &&
                        stepStatus(StepHandover) != RemoteDeviceAcceptanceStatus.Pass,
            )

        private fun applyGuidedResult(
            startedAt: Long,
            state: GuidedRunState,
            observation: GuidedObservation,
            triggers: GuidedTriggers,
            status: RemoteDeviceAcceptanceStatus,
            errorClass: String?,
        ) {
            val durationMs = elapsedSince(startedAt)
            if (triggers.reconnect) {
                updateStep(StepReconnect, status, errorClass, durationMs)
                state.sawDisconnected = false
            }
            if (triggers.handover) {
                updateStep(StepHandover, status, errorClass, durationMs)
                state.baselineUnderlay = observation.underlayTransport
            }
        }

        private fun applyScreenOffResult(
            run: RemoteAcceptanceRun,
            startedAt: Long,
            result: RemoteScreenOffDwellResult,
        ) {
            if (!isCurrent(run) || stepStatus(StepScreenOff).isTerminalScreenOffStatus()) return
            if (result.status == RemoteDeviceAcceptanceStatus.Incomplete) return
            updateStep(
                stepId = StepScreenOff,
                status = result.status,
                errorClass = result.errorClass,
                durationMs = result.durationMs ?: elapsedSince(startedAt),
            )
        }

        private fun captureUnderlayTransport(): String? =
            networkFingerprintProvider
                .capture()
                ?.transport
                ?.takeIf { it == UnderlayWifi || it == UnderlayCellular }

        private fun updateStep(
            stepId: String,
            status: RemoteDeviceAcceptanceStatus,
            errorClass: String?,
            durationMs: Long,
        ) {
            val current = _report.value
            val steps =
                current.steps.map { step ->
                    if (step.id == stepId) {
                        step.copy(status = status, durationMs = durationMs, errorClass = errorClass)
                    } else {
                        step
                    }
                }
            _report.value = current.copy(status = deriveAcceptanceStatus(steps), steps = steps)
        }

        private fun stepStatus(stepId: String): RemoteDeviceAcceptanceStatus =
            _report.value.steps
                .first { it.id == stepId }
                .status

        private suspend fun awaitFreshTelemetryAfter(updatedAt: Long): FreshTelemetrySample {
            val fresh =
                withTimeoutOrNull(RemoteAcceptanceTelemetryFreshTimeoutMs) {
                    serviceStateStore.telemetry.first { snapshot -> snapshot.updatedAt > updatedAt }
                }
            return FreshTelemetrySample(snapshot = fresh, fresh = fresh != null)
        }

        private fun currentGuidedObservation(): GuidedObservation {
            val (status, mode) = serviceStateStore.status.value
            return GuidedObservation(
                status = status,
                mode = mode,
                telemetry = serviceStateStore.telemetry.value,
                interactive = screenStateObserver.isInteractive.value,
                underlayTransport = captureUnderlayTransport(),
            )
        }

        private suspend fun recordBackgroundEvidence(
            run: RemoteAcceptanceRun,
            event: DeviceRuntimeEvidence.BackgroundSurvival,
        ) {
            deviceRuntimeEvidenceStore.record(event)
            evidenceWriter.record(run.generation, event)
        }

        private fun isCurrent(run: RemoteAcceptanceRun): Boolean = activeRunOrdinal == run.ordinal
    }

internal data class GuidedDataPlaneResult(
    val status: RemoteDeviceAcceptanceStatus,
    val errorClass: String?,
)

internal fun guidedDataPlaneResult(status: RemoteDeviceAcceptanceStatus): GuidedDataPlaneResult =
    when (status) {
        RemoteDeviceAcceptanceStatus.Pass -> {
            GuidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Pass, null)
        }

        RemoteDeviceAcceptanceStatus.Incomplete -> {
            GuidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Incomplete, ErrorPostActionProbeInconclusive)
        }

        else -> {
            GuidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Fail, ErrorPostActionProbe)
        }
    }

private data class RemoteAcceptanceRun(
    val ordinal: Long,
    val generation: String,
)

private data class FreshTelemetrySample(
    val snapshot: ServiceTelemetrySnapshot?,
    val fresh: Boolean,
)

private data class GuidedObservation(
    val status: AppStatus,
    val mode: Mode,
    val telemetry: ServiceTelemetrySnapshot,
    val interactive: Boolean,
    val underlayTransport: String?,
) {
    val isVpnRunning: Boolean
        get() = status == AppStatus.Running && mode == Mode.VPN
}

private data class GuidedRunState(
    var baselineUnderlay: String?,
    var lastHandoverState: String?,
    var sawDisconnected: Boolean = false,
    val screenOffDwellTracker: RemoteScreenOffDwellTracker =
        RemoteScreenOffDwellTracker(RemoteAcceptanceScreenOffDwellMs),
) {
    suspend fun recordLifecycle(
        running: Boolean,
        interactive: Boolean,
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
    ): RemoteScreenOffDwellObservation {
        sawDisconnected = sawDisconnected || !running
        val observation =
            screenOffDwellTracker.observe(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                running = running,
                interactive = interactive,
                telemetry = telemetry,
            )
        return observation
    }

    suspend fun completeScreenOffAfterWake(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
        afterWakeProbePassed: Boolean,
        telemetryFresh: Boolean,
    ): RemoteScreenOffDwellResult {
        val result =
            screenOffDwellTracker.completeAfterWake(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                telemetry = telemetry,
                countersBefore = countersBefore,
                afterWakeProbePassed = afterWakeProbePassed,
                telemetryFresh = telemetryFresh,
            )
        return result
    }

    suspend fun recordScreenOffProbe(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
        screenOffProbePassed: Boolean,
        telemetryFresh: Boolean,
    ): RemoteScreenOffDwellObservation {
        val observation =
            screenOffDwellTracker.recordScreenOffProbe(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                telemetry = telemetry,
                countersBefore = countersBefore,
                screenOffProbePassed = screenOffProbePassed,
                telemetryFresh = telemetryFresh,
            )
        return observation
    }

    suspend fun interruptScreenOffProbe(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
        reason: DeviceRuntimeBackgroundSurvivalReason,
        status: RemoteDeviceAcceptanceStatus,
        errorClass: String?,
    ): RemoteScreenOffDwellObservation =
        screenOffDwellTracker.interruptScreenOffProbe(
            elapsedNowMs = elapsedNowMs,
            observedAtMillis = observedAtMillis,
            telemetry = telemetry,
            countersBefore = countersBefore,
            reason = reason,
            status = status,
            errorClass = errorClass,
        )

    suspend fun interruptServiceStoppedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? =
        interruptScreenOffProbe(
            elapsedNowMs = SystemClock.elapsedRealtime(),
            observedAtMillis = System.currentTimeMillis(),
            telemetry = telemetry,
            countersBefore = countersBefore,
            reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
            status = RemoteDeviceAcceptanceStatus.Fail,
            errorClass = ErrorScreenOffServiceStopped,
        ).completedResultOrNull()

    suspend fun interruptScreenChangedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? =
        interruptScreenOffProbe(
            elapsedNowMs = SystemClock.elapsedRealtime(),
            observedAtMillis = System.currentTimeMillis(),
            telemetry = telemetry,
            countersBefore = countersBefore,
            reason = DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged,
            status = RemoteDeviceAcceptanceStatus.Incomplete,
            errorClass = null,
        ).completedResultOrNull()

    suspend fun cancelScreenOff(telemetry: ServiceTelemetrySnapshot) {
        screenOffDwellTracker.cancel(
            elapsedNowMs = SystemClock.elapsedRealtime(),
            observedAtMillis = System.currentTimeMillis(),
            telemetry = telemetry,
        )
    }
}

private suspend fun RemoteDeviceAcceptanceBaselineProbe.captureDataPlanePassed(
    snapshot: ServiceTelemetrySnapshot,
): Boolean = capture(snapshot).acceptanceDataPlanePassed()

private fun RemoteScreenOffDwellObservation.completedResultOrNull(): RemoteScreenOffDwellResult? =
    (this as? RemoteScreenOffDwellObservation.Completed)?.result

private data class GuidedTriggers(
    val reconnect: Boolean,
    val handover: Boolean,
) {
    val any: Boolean
        get() = reconnect || handover
}

private fun isWifiMobileTransition(
    before: String?,
    after: String?,
): Boolean =
    (before == UnderlayWifi && after == UnderlayCellular) ||
        (before == UnderlayCellular && after == UnderlayWifi)

private fun elapsedSince(startedAt: Long): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

private fun RemoteDeviceAcceptanceStatus.isTerminalScreenOffStatus(): Boolean =
    this == RemoteDeviceAcceptanceStatus.Pass || this == RemoteDeviceAcceptanceStatus.Fail

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RemoteDeviceAcceptanceGateModule {
    @Binds
    abstract fun bindRemoteDeviceAcceptanceGate(gate: DefaultRemoteDeviceAcceptanceGate): RemoteDeviceAcceptanceGate
}

private const val UnderlayWifi = "wifi"
private const val UnderlayCellular = "cellular"
internal const val RemoteAcceptanceScreenOffDwellMs = 300_000L
private const val RemoteAcceptanceTelemetryFreshTimeoutMs = 2_000L
internal const val StepNoDirectEgress = "no_direct_fallback"
internal const val ErrorScreenOffNoDataPlaneDelta = "background_no_data_plane_delta"
internal const val ErrorScreenOffServiceStopped = "background_service_stopped"
internal const val ErrorScreenOffServiceRestarted = "background_service_restarted"
