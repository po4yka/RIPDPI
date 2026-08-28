package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal interface HandoverRetryPolicy {
    fun nextRetryDelayMillis(currentRetryCount: Int): Long?
}

internal class ExponentialHandoverRetryPolicy(
    private val maxRetries: Int,
    private val baseDelayMillis: Long,
    private val maxDelayMillis: Long,
) : HandoverRetryPolicy {
    override fun nextRetryDelayMillis(currentRetryCount: Int): Long? {
        if (currentRetryCount >= maxRetries) {
            return null
        }
        return (baseDelayMillis * (1L shl currentRetryCount.coerceAtMost(maxRetries)))
            .coerceAtMost(maxDelayMillis)
    }
}

internal class NetworkHandoverProcessor<TSession>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val networkHandoverMonitor: NetworkHandoverMonitor,
    private val retryPolicy: HandoverRetryPolicy,
    private val clock: ServiceClock,
    private val serviceLabel: () -> String,
    private val currentSession: () -> TSession?,
    private val currentStatus: () -> ServiceStatus,
    private val isStopping: () -> Boolean,
    private val recordPendingClassification: (String) -> Unit,
    private val updateHandoverState: (String?) -> Unit,
    private val performRestart: suspend (TSession, NetworkHandoverEvent, Long, () -> Boolean) -> String?,
    private val onExhaustedFailure: suspend (HandoverExhaustedFailure) -> Unit,
    private val handoverCooldownMillis: Long,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private val generation = AtomicLong()
    private val commands = Channel<ProcessorCommand>(Channel.UNLIMITED)
    private var processorJob: Job? = null
    private var monitorJob: Job? = null

    fun startMonitoring() {
        cancel()
        ensureProcessorStarted()
        monitorJob =
            scope.launch(dispatcher) {
                networkHandoverMonitor.events.collect { event ->
                    val eventGeneration = generation.incrementAndGet()
                    commands.send(
                        ProcessorCommand.Event(
                            HandoverAttemptCommand(event, eventGeneration, retryCount = 0),
                        ),
                    )
                }
            }
    }

    fun cancel() {
        generation.incrementAndGet()
        monitorJob?.cancel()
        monitorJob = null
        commands.trySend(ProcessorCommand.Cancel)
    }

    private fun ensureProcessorStarted() {
        if (processorJob?.isActive == true) {
            return
        }
        processorJob = scope.launch(dispatcher) { processCommands() }
    }

    private suspend fun processCommands() =
        coroutineScope {
            var retryTimer: Job? = null
            var currentAttempt: Job? = null
            for (command in commands) {
                when (command) {
                    ProcessorCommand.Cancel -> {
                        retryTimer?.cancel()
                        retryTimer = null
                        currentAttempt?.cancel()
                        currentAttempt = null
                    }

                    is ProcessorCommand.Event -> {
                        retryTimer?.cancel()
                        retryTimer = null
                        currentAttempt?.cancel()
                        currentAttempt =
                            if (recordEvent(command.attempt)) launchAttempt(command.attempt) else null
                    }

                    is ProcessorCommand.Retry -> {
                        currentAttempt?.cancel()
                        currentAttempt =
                            if (isCurrent(command.attempt)) launchAttempt(command.attempt) else null
                    }

                    is ProcessorCommand.AttemptCompleted -> {
                        if (isCurrent(command.attempt)) {
                            currentAttempt = null
                            retryTimer = handleCompletion(command.result)
                        }
                    }
                }
            }
        }

    private fun recordEvent(command: HandoverAttemptCommand): Boolean {
        if (!isCurrent(command)) return false
        val event = command.event
        recordPendingClassification(event.classification)
        updateHandoverState(
            if (event.isActionable) {
                NetworkHandoverStates.Observed
            } else {
                NetworkHandoverStates.WaitingForNetwork
            },
        )
        return event.isActionable
    }

    private fun CoroutineScope.launchAttempt(command: HandoverAttemptCommand): Job =
        launch {
            val result = handle(command)
            commands.send(ProcessorCommand.AttemptCompleted(command, result))
        }

    private suspend fun CoroutineScope.handleCompletion(result: HandoverAttemptResult): Job? =
        when (result) {
            HandoverAttemptResult.Done -> {
                null
            }

            is HandoverAttemptResult.Retry -> {
                scheduleRetry(result.schedule)
            }

            is HandoverAttemptResult.Exhausted -> {
                onExhaustedFailure(result.failure)
                null
            }
        }

    private fun CoroutineScope.scheduleRetry(schedule: RetrySchedule): Job =
        launch {
            delay(schedule.delayMillis)
            val sameRuntime = currentSession()?.runtimeId == schedule.runtimeId
            if (isCurrent(schedule.command) && sameRuntime && !isStopping()) {
                commands.send(
                    ProcessorCommand.Retry(
                        schedule.command.copy(retryCount = schedule.command.retryCount + 1),
                    ),
                )
            }
        }

    private suspend fun handle(command: HandoverAttemptCommand): HandoverAttemptResult {
        val context = currentAttemptContext(command)
        return if (context == null || shouldSkip(context)) {
            HandoverAttemptResult.Done
        } else {
            performCurrentRestart(context)
        }
    }

    private fun currentAttemptContext(command: HandoverAttemptCommand): HandoverAttemptContext<TSession>? {
        val session = currentSession()
        val currentFingerprint = command.event.currentFingerprint
        if (session == null || currentFingerprint == null) return null
        val lifecycleReady = currentStatus() == ServiceStatus.Connected && !isStopping()
        return HandoverAttemptContext(command, session, currentFingerprint, clock.nowMillis())
            .takeIf { lifecycleReady && isCurrent(command) }
    }

    private fun shouldSkip(context: HandoverAttemptContext<TSession>): Boolean {
        if (context.fingerprint.captivePortalDetected) {
            updateHandoverState(NetworkHandoverStates.DeferredCaptivePortal)
            Logger.i {
                "${serviceLabel()}: captive portal detected, deferring handover until network is validated"
            }
            return true
        }

        val fingerprintHash = context.fingerprint.scopeKey()
        val isCoolingDown =
            context.command.event.classification != "connectivity_restore" &&
                context.session.lastSuccessfulHandoverFingerprintHash == fingerprintHash &&
                context.appliedAt - context.session.lastSuccessfulHandoverAt < handoverCooldownMillis
        if (isCoolingDown) {
            updateHandoverState(NetworkHandoverStates.Revalidated)
        }
        return isCoolingDown
    }

    private suspend fun performCurrentRestart(context: HandoverAttemptContext<TSession>): HandoverAttemptResult {
        updateHandoverState(NetworkHandoverStates.Restarting)
        return when (val outcome = restartOutcome(context)) {
            is RestartOutcome.Applied -> {
                recordSuccess(context, outcome.fingerprintHash)
                HandoverAttemptResult.Done
            }

            is RestartOutcome.Failed -> {
                handleFailure(context, outcome.error)
            }

            RestartOutcome.Ignored -> {
                HandoverAttemptResult.Done
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun restartOutcome(context: HandoverAttemptContext<TSession>): RestartOutcome =
        try {
            val fingerprintHash =
                performRestart(
                    context.session,
                    context.command.event,
                    context.appliedAt,
                ) { isCurrent(context.command) }
            fingerprintHash?.let(RestartOutcome::Applied) ?: RestartOutcome.Ignored
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            RestartOutcome.Failed(error)
        } catch (error: Exception) {
            RestartOutcome.Failed(error)
        }

    private fun recordSuccess(
        context: HandoverAttemptContext<TSession>,
        appliedFingerprintHash: String,
    ) {
        if (isCurrent(context.command)) {
            context.session.lastSuccessfulHandoverFingerprintHash = appliedFingerprintHash
            context.session.lastSuccessfulHandoverAt = context.appliedAt
            updateHandoverState(NetworkHandoverStates.Revalidated)
        }
    }

    private fun handleFailure(
        context: HandoverAttemptContext<TSession>,
        failure: Exception,
    ): HandoverAttemptResult {
        if (!isCurrent(context.command)) return HandoverAttemptResult.Done
        val retryDelayMillis = retryPolicy.nextRetryDelayMillis(context.command.retryCount)
        return if (retryDelayMillis != null) {
            HandoverAttemptResult.Retry(scheduleRetryAfterFailure(context, retryDelayMillis))
        } else {
            Logger.e(failure) {
                "Failed to restart ${serviceLabel()} after handover (exhausted retries)"
            }
            HandoverAttemptResult.Exhausted(
                HandoverExhaustedFailure(
                    error = failure,
                    attemptGeneration = context.command.generation,
                    runtimeId = context.session.runtimeId,
                    isCurrentAttempt = { isCurrent(context.command) },
                ),
            )
        }
    }

    private fun scheduleRetryAfterFailure(
        context: HandoverAttemptContext<TSession>,
        retryDelayMillis: Long,
    ): RetrySchedule {
        updateHandoverState(NetworkHandoverStates.RetryScheduled)
        Logger.w {
            "${serviceLabel()} handover failed (attempt ${context.command.retryCount + 1}), " +
                "retrying in ${retryDelayMillis}ms"
        }
        return RetrySchedule(
            command = context.command,
            runtimeId = context.session.runtimeId,
            delayMillis = retryDelayMillis,
        )
    }

    private fun isCurrent(command: HandoverAttemptCommand): Boolean = generation.get() == command.generation
}

internal data class HandoverExhaustedFailure(
    val error: Exception,
    val attemptGeneration: Long,
    val runtimeId: String,
    val isCurrentAttempt: () -> Boolean,
)

private sealed interface ProcessorCommand {
    data class Event(
        val attempt: HandoverAttemptCommand,
    ) : ProcessorCommand

    data class Retry(
        val attempt: HandoverAttemptCommand,
    ) : ProcessorCommand

    data class AttemptCompleted(
        val attempt: HandoverAttemptCommand,
        val result: HandoverAttemptResult,
    ) : ProcessorCommand

    data object Cancel : ProcessorCommand
}

private data class HandoverAttemptCommand(
    val event: NetworkHandoverEvent,
    val generation: Long,
    val retryCount: Int,
)

private data class HandoverAttemptContext<TSession>(
    val command: HandoverAttemptCommand,
    val session: TSession,
    val fingerprint: NetworkFingerprint,
    val appliedAt: Long,
)

private data class RetrySchedule(
    val command: HandoverAttemptCommand,
    val runtimeId: String,
    val delayMillis: Long,
)

private sealed interface HandoverAttemptResult {
    data class Retry(
        val schedule: RetrySchedule,
    ) : HandoverAttemptResult

    data class Exhausted(
        val failure: HandoverExhaustedFailure,
    ) : HandoverAttemptResult

    data object Done : HandoverAttemptResult
}

private sealed interface RestartOutcome {
    data class Applied(
        val fingerprintHash: String,
    ) : RestartOutcome

    data class Failed(
        val error: Exception,
    ) : RestartOutcome

    data object Ignored : RestartOutcome
}
