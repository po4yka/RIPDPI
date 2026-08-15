package com.poyka.ripdpi.services

import android.database.SQLException
import android.os.SystemClock
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneCounters
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultRemoteDeviceAcceptanceGate internal constructor(
    private val serviceStateStore: ServiceStateStore,
    private val screenStateObserver: ScreenStateObserver,
    private val baselineProbe: RemoteDeviceAcceptanceBaselineProbe,
    private val networkFingerprintProvider: NetworkFingerprintProvider,
    private val evidenceWriter: RemoteDeviceAcceptanceEvidenceWriter,
    private val screenOffDwellMs: Long,
    private val telemetryFreshTimeoutMs: Long,
    private val cancellationPersistenceTimeoutMs: Long,
    private val recoveryReceiptSource: RemoteDeviceRecoveryReceiptSource = EmptyRemoteDeviceRecoveryReceiptSource,
    private val uidPolicyQualificationSource: RemoteDeviceUidPolicyQualificationSource =
        EmptyRemoteDeviceUidPolicyQualificationSource,
) : RemoteDeviceAcceptanceGate {
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        screenStateObserver: ScreenStateObserver,
        baselineProbe: RemoteDeviceAcceptanceBaselineProbe,
        networkFingerprintProvider: NetworkFingerprintProvider,
        evidenceWriter: RemoteDeviceAcceptanceEvidenceWriter,
        recoveryReceiptCollector: RemoteDeviceRecoveryReceiptCollector,
        flowAttributionBridge: FlowAttributionBridge,
    ) : this(
        serviceStateStore = serviceStateStore,
        screenStateObserver = screenStateObserver,
        baselineProbe = baselineProbe,
        networkFingerprintProvider = networkFingerprintProvider,
        evidenceWriter = evidenceWriter,
        screenOffDwellMs = RemoteAcceptanceScreenOffDwellMs,
        telemetryFreshTimeoutMs = RemoteAcceptanceTelemetryFreshTimeoutMs,
        cancellationPersistenceTimeoutMs = RemoteAcceptanceCancellationPersistenceTimeoutMs,
        recoveryReceiptSource = recoveryReceiptCollector,
        uidPolicyQualificationSource = flowAttributionBridge,
    )

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
                val previousRunStopped =
                    withTimeoutOrNull(cancellationPersistenceTimeoutMs) {
                        previousRun?.cancelAndJoin()
                        true
                    } ?: false
                if (!previousRunStopped) {
                    logRecoveryWarning(RemoteAcceptanceRecoveryWarning.PreviousRunTimeout)
                }
                if (!isCurrent(run)) return@launch
                try {
                    evidenceWriter.beginRun(run.generation, System.currentTimeMillis())
                    evidenceWriter.recordRecoveryReceipt(
                        runGeneration = run.generation,
                        receipt = recoveryReceiptSource.snapshot(),
                        observedAtMillis = System.currentTimeMillis(),
                    )
                    if (!isCurrent(run)) return@launch
                    runAcceptance(run)
                } catch (cancelled: CancellationException) {
                    try {
                        if (!persistCancellation(run)) {
                            logRecoveryWarning(RemoteAcceptanceRecoveryWarning.CancellationPersistenceTimeout)
                        }
                    } catch (_: CancellationException) {
                        logRecoveryWarning(RemoteAcceptanceRecoveryWarning.CancellationPersistenceCancelled)
                    } catch (_: SQLException) {
                        logRecoveryWarning(RemoteAcceptanceRecoveryWarning.CancellationPersistenceDatabase)
                    } catch (_: IllegalStateException) {
                        logRecoveryWarning(RemoteAcceptanceRecoveryWarning.CancellationPersistenceState)
                    }
                    throw cancelled
                }
            }
    }

    override fun renderRedactedReport(): String =
        renderRemoteDeviceAcceptanceReport(
            _report.value
                .withRecoveryReceipt(recoveryReceiptSource.snapshot())
                .withUidPolicyQualification(uidPolicyQualificationSource.snapshot()),
        )

    /** Cancel-safe: an in-flight screen-off check records a terminal cancellation event. */
    private suspend fun runAcceptance(run: RemoteAcceptanceRun) {
        val startedAt = SystemClock.elapsedRealtime()
        val initialSnapshot = serviceStateStore.telemetry.value
        run.guidedState =
            GuidedRunState(
                baselineUnderlay = captureUnderlayTransport(),
                lastHandoverState = initialSnapshot.networkHandoverState,
                screenOffDwellTracker =
                    RemoteScreenOffDwellTracker(
                        minimumDwellMs = screenOffDwellMs,
                        evidenceRecorder = { event -> evidenceWriter.record(run.generation, event) },
                    ),
            )
        _report.value =
            RemoteDeviceAcceptanceReport(
                status = RemoteDeviceAcceptanceStatus.Running,
                device = captureRemoteDeviceAcceptanceDevice(),
                transportKind = sanitizeTransportKind(initialSnapshot.relayTelemetry.protocolKind),
            ).withRecoveryReceipt(
                recoveryReceiptSource.snapshot(),
            ).withUidPolicyQualification(uidPolicyQualificationSource.snapshot())
        val baseline = baselineProbe.capture(initialSnapshot)
        if (!isCurrent(run)) return
        _report.value =
            baseline
                .withRecoveryReceipt(recoveryReceiptSource.snapshot())
                .withUidPolicyQualification(uidPolicyQualificationSource.snapshot())
        observeGuidedSteps(run, startedAt, requireNotNull(run.guidedState))
    }

    private suspend fun observeGuidedSteps(
        run: RemoteAcceptanceRun,
        startedAt: Long,
        state: GuidedRunState,
    ) {
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
        recordPathPolicyInconsistency(startedAt, observation)
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
        val serviceInstanceBefore = beforeProbeTelemetry.serviceInstance()
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
                        serviceInstanceBefore = serviceInstanceBefore,
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
        val serviceInstanceBefore = beforeProbeTelemetry.serviceInstance()
        val verified = baselineProbe.captureDataPlanePassed(beforeProbeTelemetry).takeIf { isCurrent(run) }
        val afterBaseline = currentGuidedObservation()
        if (verified != null && isCurrent(run)) {
            val interruptedAfterBaseline =
                when {
                    !afterBaseline.isVpnRunning -> {
                        state.interruptAfterWakeServiceStoppedProbe(afterBaseline.telemetry, countersBefore)
                    }

                    !afterBaseline.interactive -> {
                        state.interruptAfterWakeScreenChangedProbe(afterBaseline.telemetry, countersBefore)
                    }

                    afterBaseline.telemetry.serviceInstance() != serviceInstanceBefore -> {
                        state.interruptAfterWakeServiceRestartedProbe(afterBaseline.telemetry, countersBefore)
                    }

                    else -> {
                        null
                    }
                }
            if (interruptedAfterBaseline != null) {
                applyScreenOffResult(run, startedAt, interruptedAfterBaseline)
                return
            }
            val telemetrySample = awaitFreshTelemetryAfter(beforeProbeTelemetry.updatedAt)
            val latestObservation = currentGuidedObservation()
            if (isCurrent(run)) {
                val result =
                    when {
                        !latestObservation.isVpnRunning -> {
                            state.interruptAfterWakeServiceStoppedProbe(latestObservation.telemetry, countersBefore)
                        }

                        !latestObservation.interactive -> {
                            state.interruptAfterWakeScreenChangedProbe(latestObservation.telemetry, countersBefore)
                        }

                        latestObservation.telemetry.serviceInstance() != serviceInstanceBefore -> {
                            state.interruptAfterWakeServiceRestartedProbe(
                                latestObservation.telemetry,
                                countersBefore,
                            )
                        }

                        else -> {
                            state.completeScreenOffAfterWake(
                                elapsedNowMs = SystemClock.elapsedRealtime(),
                                observedAtMillis = System.currentTimeMillis(),
                                telemetry = latestObservation.telemetry,
                                countersBefore = countersBefore,
                                afterWakeProbePassed = verified,
                                telemetryFresh = telemetrySample.fresh,
                            )
                        }
                    }
                result?.let { applyScreenOffResult(run, startedAt, it) }
            }
        }
    }

    private suspend fun recordCompletedScreenOffProbe(
        run: RemoteAcceptanceRun,
        startedAt: Long,
        state: GuidedRunState,
        beforeProbeTelemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
        serviceInstanceBefore: ServiceInstance,
        verified: Boolean,
    ) {
        val telemetrySample = awaitFreshTelemetryAfter(beforeProbeTelemetry.updatedAt)
        val latestObservation = currentGuidedObservation()
        if (isCurrent(run)) {
            val completedResult =
                if (!latestObservation.isVpnRunning) {
                    state.interruptServiceStoppedProbe(latestObservation.telemetry, countersBefore)
                } else if (latestObservation.interactive) {
                    state.interruptScreenChangedProbe(latestObservation.telemetry, countersBefore)
                } else if (latestObservation.telemetry.serviceInstance() != serviceInstanceBefore) {
                    state.interruptServiceRestartedProbe(latestObservation.telemetry, countersBefore)
                } else {
                    (
                        state.recordScreenOffProbe(
                            elapsedNowMs = SystemClock.elapsedRealtime(),
                            observedAtMillis = System.currentTimeMillis(),
                            telemetry = latestObservation.telemetry,
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

    private fun recordPathPolicyInconsistency(
        startedAt: Long,
        observation: GuidedObservation,
    ) {
        if (!observation.telemetry.relayFailed) return
        updateStep(
            stepId = StepPathPolicyConsistency,
            status = RemoteDeviceAcceptanceStatus.Fail,
            errorClass = ErrorPathPolicyInconsistent,
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
        _report.value =
            current
                .copy(
                    steps = steps,
                ).withRecoveryReceipt(
                    recoveryReceiptSource.snapshot(),
                ).withUidPolicyQualification(
                    uidPolicyQualificationSource.snapshot(),
                )
    }

    private fun stepStatus(stepId: String): RemoteDeviceAcceptanceStatus =
        _report.value.steps
            .first { it.id == stepId }
            .status

    private suspend fun awaitFreshTelemetryAfter(updatedAt: Long): FreshTelemetrySample {
        val fresh =
            withTimeoutOrNull(telemetryFreshTimeoutMs) {
                serviceStateStore.telemetry.first { snapshot -> snapshot.updatedAt > updatedAt }
            }
        return FreshTelemetrySample(fresh = fresh != null)
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

    private suspend fun persistCancellation(run: RemoteAcceptanceRun): Boolean =
        withContext(NonCancellable) {
            withTimeoutOrNull(cancellationPersistenceTimeoutMs) {
                val wroteScreenOffCancellation =
                    run.guidedState?.cancelScreenOff(serviceStateStore.telemetry.value) == true
                if (!wroteScreenOffCancellation) {
                    evidenceWriter.cancelRun(run.generation, System.currentTimeMillis())
                }
                true
            } ?: false
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

    suspend fun interruptServiceStoppedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? {
        val observation =
            screenOffDwellTracker
                .interruptScreenOffProbe(
                    elapsedNowMs = SystemClock.elapsedRealtime(),
                    observedAtMillis = System.currentTimeMillis(),
                    telemetry = telemetry,
                    countersBefore = countersBefore,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                    status = RemoteDeviceAcceptanceStatus.Fail,
                    errorClass = ErrorScreenOffServiceStopped,
                )
        return observation.completedResultOrNull()
    }

    suspend fun interruptServiceRestartedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? {
        val observation =
            screenOffDwellTracker
                .interruptScreenOffProbe(
                    elapsedNowMs = SystemClock.elapsedRealtime(),
                    observedAtMillis = System.currentTimeMillis(),
                    telemetry = telemetry,
                    countersBefore = countersBefore,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceRestarted,
                    status = RemoteDeviceAcceptanceStatus.Fail,
                    errorClass = ErrorScreenOffServiceRestarted,
                )
        return observation.completedResultOrNull()
    }

    suspend fun interruptScreenChangedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? {
        val observation =
            screenOffDwellTracker
                .interruptScreenOffProbe(
                    elapsedNowMs = SystemClock.elapsedRealtime(),
                    observedAtMillis = System.currentTimeMillis(),
                    telemetry = telemetry,
                    countersBefore = countersBefore,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged,
                    status = RemoteDeviceAcceptanceStatus.Incomplete,
                    errorClass = ErrorScreenOffScreenStateChanged,
                )
        return observation.completedResultOrNull()
    }

    suspend fun interruptAfterWakeServiceStoppedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? =
        interruptAfterWakeProbe(
            telemetry = telemetry,
            countersBefore = countersBefore,
            reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
            status = RemoteDeviceAcceptanceStatus.Fail,
            errorClass = ErrorScreenOffServiceStopped,
        )

    suspend fun interruptAfterWakeServiceRestartedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? =
        interruptAfterWakeProbe(
            telemetry = telemetry,
            countersBefore = countersBefore,
            reason = DeviceRuntimeBackgroundSurvivalReason.ServiceRestarted,
            status = RemoteDeviceAcceptanceStatus.Fail,
            errorClass = ErrorScreenOffServiceRestarted,
        )

    suspend fun interruptAfterWakeScreenChangedProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
    ): RemoteScreenOffDwellResult? =
        interruptAfterWakeProbe(
            telemetry = telemetry,
            countersBefore = countersBefore,
            reason = DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged,
            status = RemoteDeviceAcceptanceStatus.Incomplete,
            errorClass = ErrorScreenOffScreenStateChanged,
        )

    suspend fun cancelScreenOff(telemetry: ServiceTelemetrySnapshot): Boolean =
        screenOffDwellTracker.cancel(
            elapsedNowMs = SystemClock.elapsedRealtime(),
            observedAtMillis = System.currentTimeMillis(),
            telemetry = telemetry,
        ) != null

    private suspend fun interruptAfterWakeProbe(
        telemetry: ServiceTelemetrySnapshot,
        countersBefore: DeviceRuntimeDataPlaneCounters,
        reason: DeviceRuntimeBackgroundSurvivalReason,
        status: RemoteDeviceAcceptanceStatus,
        errorClass: String?,
    ): RemoteScreenOffDwellResult? =
        screenOffDwellTracker.interruptAfterWakeProbe(
            elapsedNowMs = SystemClock.elapsedRealtime(),
            observedAtMillis = System.currentTimeMillis(),
            telemetry = telemetry,
            countersBefore = countersBefore,
            reason = reason,
            status = status,
            errorClass = errorClass,
        )
}

private data class RemoteAcceptanceRun(
    val ordinal: Long,
    val generation: String,
) {
    var guidedState: GuidedRunState? = null
}

private suspend fun RemoteDeviceAcceptanceBaselineProbe.captureDataPlanePassed(
    snapshot: ServiceTelemetrySnapshot,
): Boolean = capture(snapshot).acceptanceDataPlanePassed()

private fun RemoteScreenOffDwellObservation.completedResultOrNull(): RemoteScreenOffDwellResult? =
    (this as? RemoteScreenOffDwellObservation.Completed)?.result

private fun ServiceTelemetrySnapshot.serviceInstance(): ServiceInstance =
    ServiceInstance(serviceStartedAt, restartCount)

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
internal const val RemoteAcceptanceTelemetryFreshTimeoutMs = 6_000L
internal const val StepPathPolicyConsistency = "path_policy_consistency"
internal const val ErrorScreenOffDwellTooShort = "background_dwell_too_short"
internal const val ErrorScreenOffNoDataPlaneDelta = "background_no_data_plane_delta"
internal const val ErrorScreenOffProbeMissing = "background_screen_off_probe_missing"
internal const val ErrorScreenOffScreenStateChanged = "background_screen_state_changed"
internal const val ErrorScreenOffServiceStopped = "background_service_stopped"
internal const val ErrorScreenOffServiceRestarted = "background_service_restarted"
internal const val ErrorScreenOffTelemetryStale = "background_telemetry_stale"
