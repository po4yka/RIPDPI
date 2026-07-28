package com.poyka.ripdpi.services

import android.os.SystemClock
import com.poyka.ripdpi.data.AppStatus
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
    ) : RemoteDeviceAcceptanceGate {
        private val _report = MutableStateFlow(RemoteDeviceAcceptanceReport())
        override val report: StateFlow<RemoteDeviceAcceptanceReport> = _report.asStateFlow()
        private var runJob: Job? = null

        override fun start(scope: CoroutineScope) {
            runJob?.cancel()
            runJob =
                scope.launch {
                    val startedAt = SystemClock.elapsedRealtime()
                    val initialSnapshot = serviceStateStore.telemetry.value
                    _report.value =
                        RemoteDeviceAcceptanceReport(
                            status = RemoteDeviceAcceptanceStatus.Running,
                            device = captureRemoteDeviceAcceptanceDevice(),
                            transportKind = sanitizeTransportKind(initialSnapshot.relayTelemetry.protocolKind),
                        )
                    _report.value = baselineProbe.capture(initialSnapshot)
                    observeGuidedSteps(startedAt, initialSnapshot)
                }
        }

        override fun renderRedactedReport(): String = renderRemoteDeviceAcceptanceReport(_report.value)

        /** Cancel-safe: an in-flight screen-off check records a terminal cancellation event. */
        private suspend fun observeGuidedSteps(
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
                            evidenceRecorder = deviceRuntimeEvidenceStore::record,
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
                    handleGuidedObservation(startedAt, state, observation)
                }
            } catch (cancelled: CancellationException) {
                state.cancelScreenOff(serviceStateStore.telemetry.value)
                throw cancelled
            }
        }

        private suspend fun handleGuidedObservation(
            startedAt: Long,
            state: GuidedRunState,
            observation: GuidedObservation,
        ) {
            val running = observation.status == AppStatus.Running && observation.mode == Mode.VPN
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
                    applyScreenOffResult(startedAt, screenOffObservation.result)
                }

                is RemoteScreenOffDwellObservation.ReadyForScreenOffProbe -> {
                    val verified = baselineProbe.capture(observation.telemetry).acceptanceDataPlanePassed()
                    val result =
                        state.recordScreenOffProbe(
                            elapsedNowMs = SystemClock.elapsedRealtime(),
                            observedAtMillis = System.currentTimeMillis(),
                            telemetry = serviceStateStore.telemetry.value,
                            screenOffProbePassed = verified,
                        )
                    if (result is RemoteScreenOffDwellObservation.Completed) {
                        applyScreenOffResult(startedAt, result.result)
                    }
                }

                is RemoteScreenOffDwellObservation.ReadyForAfterWakeProbe -> {
                    val verified = baselineProbe.capture(observation.telemetry).acceptanceDataPlanePassed()
                    val result =
                        state.completeScreenOffAfterWake(
                            elapsedNowMs = SystemClock.elapsedRealtime(),
                            observedAtMillis = System.currentTimeMillis(),
                            telemetry = serviceStateStore.telemetry.value,
                            afterWakeProbePassed = verified,
                        )
                    applyScreenOffResult(startedAt, result)
                }

                RemoteScreenOffDwellObservation.None -> {
                }
            }

            val triggers = guidedTriggers(state, observation, running, screenOffSurvived = false)
            state.lastHandoverState = observation.telemetry.networkHandoverState
            if (!triggers.any) return

            val result =
                guidedDataPlaneResult(
                    baselineProbe.capture(observation.telemetry).acceptanceDataPlaneStatus(),
                )
            applyGuidedResult(startedAt, state, observation, triggers, result.status, result.errorClass)
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
            screenOffSurvived: Boolean,
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
                screenOff =
                    running &&
                        screenOffSurvived &&
                        stepStatus(StepScreenOff) != RemoteDeviceAcceptanceStatus.Pass,
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
            if (triggers.screenOff) {
                updateStep(
                    StepScreenOff,
                    status,
                    errorClass,
                    state.lastScreenOffDurationMs ?: durationMs,
                )
            }
        }

        private fun applyScreenOffResult(
            startedAt: Long,
            result: RemoteScreenOffDwellResult,
        ) {
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

private data class GuidedObservation(
    val status: AppStatus,
    val mode: Mode,
    val telemetry: ServiceTelemetrySnapshot,
    val interactive: Boolean,
    val underlayTransport: String?,
)

private data class GuidedRunState(
    var baselineUnderlay: String?,
    var lastHandoverState: String?,
    var sawDisconnected: Boolean = false,
    val screenOffDwellTracker: RemoteScreenOffDwellTracker =
        RemoteScreenOffDwellTracker(RemoteAcceptanceScreenOffDwellMs),
) {
    var lastScreenOffDurationMs: Long? = null

    fun recordLifecycle(
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
        if (observation is RemoteScreenOffDwellObservation.ReadyForScreenOffProbe) {
            lastScreenOffDurationMs = observation.durationMs
        } else if (observation is RemoteScreenOffDwellObservation.ReadyForAfterWakeProbe) {
            lastScreenOffDurationMs = observation.durationMs
        } else if (observation is RemoteScreenOffDwellObservation.Completed) {
            lastScreenOffDurationMs = observation.result.durationMs
        }
        return observation
    }

    fun completeScreenOffAfterWake(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        afterWakeProbePassed: Boolean,
    ): RemoteScreenOffDwellResult {
        val result =
            screenOffDwellTracker.completeAfterWake(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                telemetry = telemetry,
                afterWakeProbePassed = afterWakeProbePassed,
            )
        lastScreenOffDurationMs = result.durationMs
        return result
    }

    fun recordScreenOffProbe(
        elapsedNowMs: Long,
        observedAtMillis: Long,
        telemetry: ServiceTelemetrySnapshot,
        screenOffProbePassed: Boolean,
    ): RemoteScreenOffDwellObservation {
        val observation =
            screenOffDwellTracker.recordScreenOffProbe(
                elapsedNowMs = elapsedNowMs,
                observedAtMillis = observedAtMillis,
                telemetry = telemetry,
                screenOffProbePassed = screenOffProbePassed,
            )
        if (observation is RemoteScreenOffDwellObservation.Completed) {
            lastScreenOffDurationMs = observation.result.durationMs
        }
        return observation
    }

    fun cancelScreenOff(telemetry: ServiceTelemetrySnapshot) {
        screenOffDwellTracker.cancel(
            elapsedNowMs = SystemClock.elapsedRealtime(),
            observedAtMillis = System.currentTimeMillis(),
            telemetry = telemetry,
        )
    }
}

private data class GuidedTriggers(
    val reconnect: Boolean,
    val handover: Boolean,
    val screenOff: Boolean,
) {
    val any: Boolean
        get() = reconnect || handover || screenOff
}

private fun isWifiMobileTransition(
    before: String?,
    after: String?,
): Boolean =
    (before == UnderlayWifi && after == UnderlayCellular) ||
        (before == UnderlayCellular && after == UnderlayWifi)

private fun elapsedSince(startedAt: Long): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RemoteDeviceAcceptanceGateModule {
    @Binds
    abstract fun bindRemoteDeviceAcceptanceGate(gate: DefaultRemoteDeviceAcceptanceGate): RemoteDeviceAcceptanceGate
}

private const val UnderlayWifi = "wifi"
private const val UnderlayCellular = "cellular"
internal const val RemoteAcceptanceScreenOffDwellMs = 300_000L
internal const val StepNoDirectEgress = "no_direct_fallback"
internal const val ErrorScreenOffNoDataPlaneDelta = "background_no_data_plane_delta"
internal const val ErrorScreenOffServiceStopped = "background_service_stopped"
internal const val ErrorScreenOffServiceRestarted = "background_service_restarted"
