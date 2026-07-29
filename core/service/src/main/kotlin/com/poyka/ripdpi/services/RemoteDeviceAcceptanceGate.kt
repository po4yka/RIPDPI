package com.poyka.ripdpi.services

import android.os.SystemClock
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

        /** NOT cancel-safe: cancellation closes the guided run without persisting partial state. */
        private suspend fun observeGuidedSteps(
            startedAt: Long,
            initialSnapshot: ServiceTelemetrySnapshot,
        ) {
            val state =
                GuidedRunState(
                    baselineUnderlay = captureUnderlayTransport(),
                    lastHandoverState = initialSnapshot.networkHandoverState,
                )

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
        }

        private suspend fun handleGuidedObservation(
            startedAt: Long,
            state: GuidedRunState,
            observation: GuidedObservation,
        ) {
            val running = observation.status == AppStatus.Running && observation.mode == Mode.VPN
            val screenOffSurvived =
                state.recordLifecycle(
                    running = running,
                    interactive = observation.interactive,
                    nowMs = SystemClock.elapsedRealtime(),
                )
            recordDirectFallback(startedAt, observation)
            val triggers = guidedTriggers(state, observation, running, screenOffSurvived)
            state.lastHandoverState = observation.telemetry.networkHandoverState
            if (!triggers.any) return

            val verified = baselineProbe.capture(observation.telemetry).acceptanceDataPlanePassed()
            val status = if (verified) RemoteDeviceAcceptanceStatus.Pass else RemoteDeviceAcceptanceStatus.Fail
            val errorClass = if (verified) null else ErrorPostActionProbe
            applyGuidedResult(startedAt, state, observation, triggers, status, errorClass)
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
                updateStep(StepScreenOff, status, errorClass, durationMs)
            }
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
    fun recordLifecycle(
        running: Boolean,
        interactive: Boolean,
        nowMs: Long,
    ): Boolean {
        sawDisconnected = sawDisconnected || !running
        return screenOffDwellTracker.observe(nowMs, running, interactive)
    }
}

internal class RemoteScreenOffDwellTracker(
    private val minimumDwellMs: Long,
) {
    private var screenOffStartedAt: Long? = null
    private var interrupted = false

    fun observe(
        nowMs: Long,
        running: Boolean,
        interactive: Boolean,
    ): Boolean =
        if (interactive) {
            finishObservation(nowMs, running)
        } else {
            recordScreenOff(nowMs, running)
            false
        }

    private fun recordScreenOff(
        nowMs: Long,
        running: Boolean,
    ) {
        if (!running) {
            interrupted = interrupted || screenOffStartedAt != null
        } else if (screenOffStartedAt == null) {
            screenOffStartedAt = nowMs
            interrupted = false
        }
    }

    private fun finishObservation(
        nowMs: Long,
        running: Boolean,
    ): Boolean {
        val startedAt = screenOffStartedAt
        val survived = startedAt != null && running && !interrupted && nowMs - startedAt >= minimumDwellMs
        screenOffStartedAt = null
        interrupted = false
        return survived
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
