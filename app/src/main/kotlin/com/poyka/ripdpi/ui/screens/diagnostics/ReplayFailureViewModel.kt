package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.replay.ProbeReplayService
import com.poyka.ripdpi.diagnostics.replay.ReplayErrorKind
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayResultStore
import com.poyka.ripdpi.diagnostics.replay.ReplayStepEvent
import com.poyka.ripdpi.diagnostics.replay.ReplayStepKind
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import com.poyka.ripdpi.platform.StringResolver
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
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(ReplayFailureUiState())
        val uiState: StateFlow<ReplayFailureUiState> = mutableUiState.asStateFlow()

        private var currentJob: Job? = null
        private var currentAttempt: ReplayAttempt? = null

        fun start(
            domain: String,
            strategyId: String,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ) {
            currentJob?.cancel()
            val request = ReplayProbeRequest(domain, strategyId, timeoutMs)
            val attempt = ReplayAttempt(request)
            currentAttempt = attempt
            val timestamp = currentTimestamp()
            val summary = stringResolver.getString(R.string.diagnostics_replay_probe_summary, domain, strategyId)
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
                                if (isCurrent(attempt)) {
                                    mutableUiState.update { state ->
                                        state.copy(
                                            isRunning = false,
                                            errorMessage = error.localizedMessage ?: error.javaClass.simpleName,
                                        )
                                    }
                                }
                            }.collect { event -> handleEvent(attempt, event) }
                    } finally {
                        // If the flow terminated without a Finished event
                        // (cancellation, error, scope teardown) persist a
                        // Cancelled-verdict aggregate so the user still has
                        // a record of the attempt in the archive + history.
                        recordCancelledIfNotYet(attempt)
                    }
                }
        }

        /**
         * Auto-starts the probe unless this ViewModel instance already ran
         * the same (domain, strategyId) target. Configuration changes
         * recreate the composition but retain the ViewModel, so the retained
         * [currentAttempt] suppresses duplicate auto-starts; a genuinely new
         * target restarts the probe.
         */
        fun ensureStarted(
            domain: String,
            strategyId: String,
        ) {
            val request = currentAttempt?.request
            if (request != null && request.domain == domain && request.strategyId == strategyId) return
            start(domain = domain, strategyId = strategyId)
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

        private fun handleEvent(
            attempt: ReplayAttempt,
            event: ReplayStepEvent,
        ) {
            attempt.eventBuffer.add(event)
            if (!isCurrent(attempt)) {
                return
            }
            when (event) {
                is ReplayStepEvent.StepStarted -> {
                    updateStep(event.step) { step ->
                        step.copy(
                            whenLabel = formatTime(event.timestampMs),
                            status = ReplayStepStatus.Pending,
                            detail = stringResolver.getString(R.string.diagnostics_replay_step_in_progress),
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
                    recordTerminal(attempt, event)
                }
            }
        }

        private fun recordTerminal(
            attempt: ReplayAttempt,
            finished: ReplayStepEvent.Finished,
        ) {
            if (!attempt.recorded.compareAndSet(false, true)) return
            replayResultStore.record(
                ReplayProbeResult(
                    request = attempt.request,
                    events = attempt.eventBuffer.toPersistentList(),
                    verdict = finished.verdict,
                    terminalStep = finished.terminalStep,
                    recommendationKey = finished.recommendationKey,
                ),
            )
        }

        private fun recordCancelledIfNotYet(attempt: ReplayAttempt) {
            if (!attempt.recorded.compareAndSet(false, true)) return
            replayResultStore.record(
                ReplayProbeResult(
                    request = attempt.request,
                    events = attempt.eventBuffer.toPersistentList(),
                    verdict = ReplayVerdict.Cancelled,
                    terminalStep = null,
                    recommendationKey = "",
                ),
            )
        }

        private fun isCurrent(attempt: ReplayAttempt): Boolean = currentAttempt === attempt

        private data class ReplayAttempt(
            val request: ReplayProbeRequest,
            val eventBuffer: MutableList<ReplayStepEvent> = mutableListOf(),
            val recorded: AtomicBoolean = AtomicBoolean(false),
        )

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
            stringResolver.getString(
                when (kind) {
                    ReplayStepKind.DnsResolve -> R.string.diagnostics_replay_step_dns_resolve
                    ReplayStepKind.TcpOpen -> R.string.diagnostics_replay_step_tcp_open
                    ReplayStepKind.TlsClientHello -> R.string.diagnostics_replay_step_tls_client_hello
                    ReplayStepKind.TlsHandshake -> R.string.diagnostics_replay_step_tls_handshake
                    ReplayStepKind.FirstByte -> R.string.diagnostics_replay_step_first_byte
                },
            )

        private fun errorKindLabel(kind: ReplayErrorKind): String =
            stringResolver.getString(
                when (kind) {
                    ReplayErrorKind.DnsTampered -> R.string.diagnostics_replay_error_dns_tampered
                    ReplayErrorKind.ConnectionRefused -> R.string.diagnostics_replay_error_connection_refused
                    ReplayErrorKind.ConnectionReset -> R.string.diagnostics_replay_error_connection_reset
                    ReplayErrorKind.Timeout -> R.string.diagnostics_replay_error_timeout
                    ReplayErrorKind.TlsHandshakeFailed -> R.string.diagnostics_replay_error_tls_handshake_failed
                    ReplayErrorKind.Unknown -> R.string.diagnostics_replay_error_unknown
                },
            )

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
