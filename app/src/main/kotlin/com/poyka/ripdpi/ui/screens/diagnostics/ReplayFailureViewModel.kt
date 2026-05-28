package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.diagnostics.replay.ProbeReplayService
import com.poyka.ripdpi.diagnostics.replay.ReplayErrorKind
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayResultStore
import com.poyka.ripdpi.diagnostics.replay.ReplayStepEvent
import com.poyka.ripdpi.diagnostics.replay.ReplayStepKind
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * UI-facing state for the Replay Failure screen.
 *
 * The presentation [ReplayStep] / [ReplayStepStatus] types live in
 * [ReplayFailureScreen]; this view-model translates the orchestration
 * model ([ReplayStepEvent] / [ReplayStepKind] / [ReplayErrorKind]) into
 * them so the screen never sees the core/diagnostics types directly.
 */
data class ReplayFailureUiState(
    val timestampLabel: String = "",
    val probeSummary: String = "",
    val steps: ImmutableList<ReplayStep> = persistentListOf(),
    val recommendationKey: String = "",
    val isRunning: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class ReplayFailureViewModel
    @Inject
    constructor(
        private val replayService: ProbeReplayService,
        private val replayResultStore: ReplayResultStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(ReplayFailureUiState())
        val uiState: StateFlow<ReplayFailureUiState> = mutableUiState.asStateFlow()

        private var currentJob: Job? = null
        private var currentRequest: ReplayProbeRequest? = null
        private var eventBuffer: MutableList<ReplayStepEvent> = mutableListOf()
        private var recorded: AtomicBoolean = AtomicBoolean(false)

        fun start(
            domain: String,
            strategyId: String,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ) {
            currentJob?.cancel()
            val request = ReplayProbeRequest(domain, strategyId, timeoutMs)
            currentRequest = request
            eventBuffer = mutableListOf()
            recorded = AtomicBoolean(false)
            val timestamp = currentTimestamp()
            val summary = "Probe replay · $domain · $strategyId"
            mutableUiState.value =
                ReplayFailureUiState(
                    timestampLabel = timestamp,
                    probeSummary = summary,
                    steps = buildPendingSteps(),
                    isRunning = true,
                )
            currentJob =
                viewModelScope.launch {
                    try {
                        replayService
                            .run(request)
                            .catch { error ->
                                if (error is CancellationException) {
                                    throw error
                                }
                                mutableUiState.update { state ->
                                    state.copy(
                                        isRunning = false,
                                        errorMessage = error.localizedMessage ?: error.javaClass.simpleName,
                                    )
                                }
                            }.collect { event -> handleEvent(event) }
                    } finally {
                        // If the flow terminated without a Finished event
                        // (cancellation, error, scope teardown) persist a
                        // Cancelled-verdict aggregate so the user still has
                        // a record of the attempt in the archive + history.
                        recordCancelledIfNotYet()
                    }
                }
        }

        fun cancel() {
            currentJob?.cancel()
            currentJob = null
            mutableUiState.update { it.copy(isRunning = false) }
        }

        override fun onCleared() {
            super.onCleared()
            currentJob?.cancel()
        }

        private fun handleEvent(event: ReplayStepEvent) {
            eventBuffer.add(event)
            when (event) {
                is ReplayStepEvent.StepStarted -> {
                    updateStep(event.step) { step ->
                        step.copy(
                            whenLabel = formatTime(event.timestampMs),
                            status = ReplayStepStatus.Pending,
                            detail = "in progress",
                        )
                    }
                }

                is ReplayStepEvent.StepCompleted -> {
                    updateStep(event.step) { step ->
                        step.copy(
                            status = ReplayStepStatus.Success,
                            detail = "${event.detail} · ${event.durationMs} ms",
                        )
                    }
                }

                is ReplayStepEvent.StepFailed -> {
                    updateStep(event.step) { step ->
                        step.copy(
                            status = ReplayStepStatus.Failure,
                            detail = "${errorKindLabel(event.errorKind)} · ${event.detail}",
                        )
                    }
                }

                is ReplayStepEvent.Finished -> {
                    mutableUiState.update {
                        it.copy(
                            isRunning = false,
                            recommendationKey = event.recommendationKey,
                        )
                    }
                    recordTerminal(event)
                }
            }
        }

        private fun recordTerminal(finished: ReplayStepEvent.Finished) {
            if (!recorded.compareAndSet(false, true)) return
            val request = currentRequest ?: return
            replayResultStore.record(
                ReplayProbeResult(
                    request = request,
                    events = eventBuffer.toPersistentList(),
                    verdict = finished.verdict,
                    terminalStep = finished.terminalStep,
                    recommendationKey = finished.recommendationKey,
                ),
            )
        }

        private fun recordCancelledIfNotYet() {
            if (!recorded.compareAndSet(false, true)) return
            val request = currentRequest ?: return
            replayResultStore.record(
                ReplayProbeResult(
                    request = request,
                    events = eventBuffer.toPersistentList(),
                    verdict = ReplayVerdict.Cancelled,
                    terminalStep = null,
                    recommendationKey = "",
                ),
            )
        }

        private fun updateStep(
            kind: ReplayStepKind,
            mutate: (ReplayStep) -> ReplayStep,
        ) {
            mutableUiState.update { state ->
                val target = stepDisplayName(kind)
                state.copy(
                    steps =
                        state.steps
                            .map { step -> if (step.name == target) mutate(step) else step }
                            .toPersistentList(),
                )
            }
        }

        private fun buildPendingSteps(): ImmutableList<ReplayStep> =
            ReplayStepKind.entries
                .map { kind ->
                    ReplayStep(
                        whenLabel = "",
                        name = stepDisplayName(kind),
                        detail = "",
                        status = ReplayStepStatus.Pending,
                    )
                }.toPersistentList()

        private fun stepDisplayName(kind: ReplayStepKind): String =
            when (kind) {
                ReplayStepKind.DnsResolve -> "DNS resolve"
                ReplayStepKind.TcpOpen -> "TCP open"
                ReplayStepKind.TlsClientHello -> "TLS ClientHello"
                ReplayStepKind.TlsHandshake -> "TLS handshake"
                ReplayStepKind.FirstByte -> "First byte"
            }

        private fun errorKindLabel(kind: ReplayErrorKind): String =
            when (kind) {
                ReplayErrorKind.DnsTampered -> "DNS tampered"
                ReplayErrorKind.ConnectionRefused -> "connection refused"
                ReplayErrorKind.ConnectionReset -> "connection reset"
                ReplayErrorKind.Timeout -> "timeout"
                ReplayErrorKind.TlsHandshakeFailed -> "TLS handshake failed"
                ReplayErrorKind.Unknown -> "unknown error"
            }

        private fun currentTimestamp(): String =
            requireNotNull(TIMESTAMP_FORMAT.get()).format(Date(System.currentTimeMillis()))

        private fun formatTime(epochMs: Long): String = requireNotNull(STEP_TIME_FORMAT.get()).format(Date(epochMs))

        companion object {
            const val DEFAULT_TIMEOUT_MS: Long = 15_000L

            // ThreadLocal<T>.get() is declared nullable in Kotlin's stubs
            // even when initialValue() is overridden to a non-null value.
            // requireNotNull() at call sites documents the invariant.
            private val TIMESTAMP_FORMAT: ThreadLocal<SimpleDateFormat> =
                object : ThreadLocal<SimpleDateFormat>() {
                    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                }

            private val STEP_TIME_FORMAT: ThreadLocal<SimpleDateFormat> =
                object : ThreadLocal<SimpleDateFormat>() {
                    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                }
        }
    }
