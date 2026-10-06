package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class ServiceRuntimeStartStopOrchestrator<TSession>(
    private val dependencies: ServiceRuntimeStartStopDependencies<TSession>,
    private val callbacks: ServiceRuntimeStartStopCallbacks<TSession>,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    suspend fun start(
        stopSelfStartId: Int? = null,
        transaction: RuntimeStartTransaction? = null,
    ) {
        Logger.i { "Starting ${dependencies.serviceLabel()}" }

        val context = currentCoroutineContext()
        val originalIntent =
            checkNotNull(context[RuntimeCommandStartAuthority]) {
                "Runtime start command was not captured"
            }.original
        var matchedRememberedPolicy: RememberedNetworkPolicyEntity? = null
        val failure =
            dependencies.lifecycleRunner.start(
                shouldRecoverRunning = { callbacks.currentStatus() == ServiceStatus.Failed },
                recoverRunningBlock = {
                    dependencies.handoverProcessor.cancel()
                    finalizeRuntimeStop(
                        skipRuntimeShutdown = false,
                        stopSelfStartId = null,
                        requestServiceStop = false,
                    )
                },
            ) {
                val pauseResume = currentCoroutineContext()[PauseResumeAuthority]
                pauseResume?.ensureCurrent()
                val session = callbacks.createRuntimeSession()
                session.captureOriginalAppliedIntent(originalIntent)
                session.pauseResumeIntent = pauseResume?.intent
                callbacks.setRuntimeSession(session)
                session.networkHandoverState = null
                val resolution = callbacks.resolveInitialConnectionPolicy()
                transaction?.beforeStart?.invoke(resolution)
                matchedRememberedPolicy = resolution.matchedNetworkPolicy
                callbacks.applyActiveConnectionPolicy(
                    session,
                    resolution,
                    "initial_start",
                    dependencies.clock.nowMillis(),
                )
                val runtimeStartEvidence =
                    callbacks.startResolvedRuntime(
                        session,
                        resolution,
                    )
                currentCoroutineContext().ensureActive()
                pauseResume?.ensureCurrent()
                val authority = currentCoroutineContext()[ExplicitRuntimeStartAuthority]?.guard
                authority?.ensureCurrentStart()
                publishStartedSession(session, resolution, runtimeStartEvidence, pauseResume)
                callbacks.statusHooks.reportConnected()
                dependencies.handoverProcessor.startMonitoring()
                callbacks.startModeTelemetryUpdates()
                dependencies.loopOwner.startPermissionWatchdog()
                transaction?.onStarted?.invoke()
            }
                ?: return
        val cancellation = (failure as? CancellationException)?.takeUnless { currentCoroutineContext().isActive }
        try {
            if (cancellation == null) {
                val error =
                    failure as? Exception ?: IllegalStateException(
                        "Failed to start ${dependencies.serviceLabel()}",
                        failure,
                    )
                val classifiedError = error.unwrapSupervisorStartupFailure()
                Logger.e(classifiedError) { "Failed to start ${dependencies.serviceLabel()}" }
                matchedRememberedPolicy?.let { policy ->
                    runCatching { dependencies.rememberedNetworkPolicyStore.recordFailure(policy) }
                        .onFailure { Logger.e(it) { "Failed to record remembered policy startup failure" } }
                }
                val failureReason = callbacks.statusHooks.classifyStartupFailure(classifiedError)
                callbacks.statusHooks.updateStatus(ServiceStatus.Failed, failureReason)
            }
        } finally {
            withContext(NonCancellable) { stop(stopSelfStartId = stopSelfStartId) }
        }
        if (cancellation != null) throw cancellation
    }

    private suspend fun publishStartedSession(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        runtimeStartEvidence: RuntimeStartEvidence,
        pauseResume: PauseResumeAuthority?,
    ) {
        callbacks.evidencePublication.publish(
            session,
            resolution,
            runtimeStartEvidence,
        )
        currentCoroutineContext().ensureActive()
        pauseResume?.ensureGeneration()
        // ACK completes with Store -> linearizer -> authority lock order before publication takes its gate.
        callbacks.evidencePublication.complete(session, resolution, runtimeStartEvidence)
        val publish = {
            dependencies.serviceRuntimeRegistry.register(session)
            callbacks.statusHooks.publishConnected()
        }
        val published = callbacks.evidencePublication.publishCaptured(session, publish)
        if (!published) throw CancellationException("Start intent superseded before publication")
    }

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
        guard: RuntimeStopGuard? = null,
        disposition: RuntimeStopDisposition = RuntimeStopDisposition.StopService,
    ): RuntimeStopOutcome =
        stopInternal(
            stopSelfStartId = stopSelfStartId,
            skipRuntimeShutdown = skipRuntimeShutdown,
            guard = guard,
            terminalFailure = guard?.failureReason,
            disposition = disposition,
        )

    suspend fun failAndStop(
        failureReason: FailureReason,
        guard: RuntimeStopGuard? = null,
        beforeStopFinalization: suspend () -> Unit,
    ): RuntimeStopOutcome =
        stopInternal(
            stopSelfStartId = null,
            skipRuntimeShutdown = false,
            guard = guard,
            terminalFailure = failureReason,
            beforeStopFinalization = beforeStopFinalization,
        )

    private suspend fun stopInternal(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
        guard: RuntimeStopGuard? = null,
        terminalFailure: FailureReason?,
        beforeStopFinalization: suspend () -> Unit = {},
        disposition: RuntimeStopDisposition = RuntimeStopDisposition.StopService,
    ): RuntimeStopOutcome {
        Logger.i { "Stopping ${dependencies.serviceLabel()}" }
        if (guard == null) dependencies.handoverProcessor.cancel()
        var terminalTelemetryCancellation: CancellationException? = null
        val outcome =
            try {
                val accepted =
                    dependencies.lifecycleRunner.stop(guard) {
                        if (guard != null) dependencies.handoverProcessor.cancel()
                        terminalTelemetryCancellation =
                            finalizeAfterStopCallback(beforeStopFinalization) {
                                finalizeRuntimeStop(
                                    skipRuntimeShutdown = skipRuntimeShutdown,
                                    stopSelfStartId = stopSelfStartId,
                                    requestServiceStop = disposition == RuntimeStopDisposition.StopService,
                                    terminalFailure = terminalFailure,
                                )
                            }
                    }
                if (accepted) RuntimeStopOutcome.FullyReleased else RuntimeStopOutcome.Superseded
            } catch (_: RuntimeCleanupPendingException) {
                // Native cleanup is unconfirmed. Keep ownership so stop can be retried.
                callbacks.statusHooks.updateStatus(
                    ServiceStatus.Failed,
                    FailureReason.NativeError("Runtime cleanup is incomplete"),
                )
                RuntimeStopOutcome.CleanupPending
            }
        terminalTelemetryCancellation?.let { throw it }
        return outcome
    }

    private suspend fun finalizeRuntimeStop(
        skipRuntimeShutdown: Boolean,
        stopSelfStartId: Int?,
        requestServiceStop: Boolean,
        terminalFailure: FailureReason? = null,
    ): CancellationException? {
        dependencies.loopOwner.cancelPermissionWatchdog()
        var terminalTelemetryCancellation: CancellationException? = null
        try {
            captureFinalTelemetryWithRetry()
        } catch (failure: CancellationException) {
            terminalTelemetryCancellation = failure
        }
        withContext(NonCancellable) {
            runCatching { callbacks.stopModeRuntime(skipRuntimeShutdown) }
                .onFailure { failure ->
                    throw if (failure is RuntimeCleanupPendingException) {
                        failure
                    } else {
                        RuntimeCleanupPendingException(
                            failure,
                        )
                    }
                }

            val session = callbacks.currentSession()
            runCatching {
                callbacks.statusHooks.updateStatus(
                    if (terminalFailure == null) ServiceStatus.Disconnected else ServiceStatus.Failed,
                    terminalFailure,
                )
            }.onFailure { failure ->
                Logger.e(failure) { "Failed to publish stopped ${dependencies.serviceLabel()} status" }
            }
            dependencies.loopOwner.cancelTelemetry()
            runCatching { callbacks.onAfterStopCleanup(session) }
                .onFailure { failure ->
                    throw RuntimeCleanupPendingException(failure)
                }
            runCatching { session?.clearActiveConnectionPolicy() }
                .onFailure { failure ->
                    Logger.e(failure) { "Failed to clear stopped ${dependencies.serviceLabel()} policy" }
                }
            session?.let { activeSession ->
                runCatching {
                    dependencies.serviceRuntimeRegistry.unregister(
                        mode = dependencies.mode,
                        runtimeId = activeSession.runtimeId,
                    )
                }.onFailure { failure ->
                    Logger.e(failure) { "Failed to unregister stopped ${dependencies.serviceLabel()} runtime" }
                }
            }
            callbacks.setRuntimeSession(null)
            if (requestServiceStop) {
                dependencies.host.requestStopSelf(stopSelfStartId)
            }
        }
        return terminalTelemetryCancellation
    }

    private suspend fun captureFinalTelemetryWithRetry() {
        repeat(TerminalTelemetryCaptureAttempts) { attempt ->
            when (val outcome = captureFinalTelemetryAttempt()) {
                TerminalTelemetryCaptureOutcome.Completed -> {
                    return
                }

                TerminalTelemetryCaptureOutcome.TimedOut -> {
                    Logger.e {
                        "Timed out capturing final ${dependencies.serviceLabel()} telemetry " +
                            "(attempt ${attempt + 1}/$TerminalTelemetryCaptureAttempts)"
                    }
                }

                is TerminalTelemetryCaptureOutcome.Failed -> {
                    Logger.e(outcome.failure) {
                        "Failed to capture final ${dependencies.serviceLabel()} telemetry " +
                            "(attempt ${attempt + 1}/$TerminalTelemetryCaptureAttempts)"
                    }
                }
            }
            if (attempt + 1 < TerminalTelemetryCaptureAttempts) {
                delay(TerminalTelemetryRetryDelayMillis)
            }
        }
    }

    private suspend fun captureFinalTelemetryAttempt(): TerminalTelemetryCaptureOutcome {
        val result =
            runCatching {
                withTimeoutOrNull(TerminalTelemetryAttemptTimeoutMillis) {
                    callbacks.captureFinalTelemetry()
                    true
                } == true
            }
        val failure = result.exceptionOrNull()
        return when {
            failure is CancellationException -> throw failure
            failure is Exception -> TerminalTelemetryCaptureOutcome.Failed(failure)
            failure != null -> throw failure
            result.getOrThrow() -> TerminalTelemetryCaptureOutcome.Completed
            else -> TerminalTelemetryCaptureOutcome.TimedOut
        }
    }
}

/** Always finalizes an accepted stop, then propagates cancellation or failure without hiding pending cleanup. */
private suspend fun finalizeAfterStopCallback(
    beforeFinalization: suspend () -> Unit,
    finalize: suspend () -> CancellationException?,
): CancellationException? {
    val callbackFailure = runCatching { beforeFinalization() }.exceptionOrNull()
    val cleanup = runCatching { finalize() }
    val cleanupFailure = cleanup.exceptionOrNull()
    val failure = cleanupFailure ?: callbackFailure
    if (failure != null) {
        val suppressed = if (cleanupFailure != null) callbackFailure else cleanup.getOrNull()
        if (suppressed != null && suppressed !== failure) failure.addSuppressed(suppressed)
        throw failure
    }
    return cleanup.getOrNull()
}

/** Runs inside lifecycle serialization, before side effects and after complete startup respectively. */
internal class RuntimeStartTransaction(
    val beforeStart: (ConnectionPolicyResolution) -> Unit,
    val onStarted: () -> Unit,
)

private sealed interface TerminalTelemetryCaptureOutcome {
    data object Completed : TerminalTelemetryCaptureOutcome

    data object TimedOut : TerminalTelemetryCaptureOutcome

    data class Failed(
        val failure: Exception,
    ) : TerminalTelemetryCaptureOutcome
}

internal const val TerminalTelemetryCaptureAttempts = 2
internal const val TerminalTelemetryAttemptTimeoutMillis = 2_000L
internal const val TerminalTelemetryRetryDelayMillis = 50L

internal class ServiceRuntimeStartStopDependencies<TSession>(
    val mode: Mode,
    val serviceLabel: () -> String,
    val lifecycleRunner: RuntimeLifecycleRunner,
    val serviceRuntimeRegistry: ServiceRuntimeRegistry,
    val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    val loopOwner: ServiceRuntimeLoopOwner,
    val handoverProcessor: NetworkHandoverProcessor<TSession>,
    val clock: ServiceClock,
    val host: ServiceCoordinatorHost,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession

internal class ServiceRuntimeStartStopCallbacks<TSession>(
    val currentStatus: () -> ServiceStatus,
    val currentSession: () -> TSession?,
    val setRuntimeSession: (TSession?) -> Unit,
    val createRuntimeSession: () -> TSession,
    val resolveInitialConnectionPolicy: suspend () -> ConnectionPolicyResolution,
    val applyActiveConnectionPolicy: suspend (TSession, ConnectionPolicyResolution, String, Long) -> Unit,
    val startResolvedRuntime: suspend (TSession, ConnectionPolicyResolution) -> RuntimeStartEvidence,
    val evidencePublication: RuntimeStartEvidencePublication<TSession>,
    val captureFinalTelemetry: suspend () -> Unit = {},
    val stopModeRuntime: suspend (Boolean) -> Unit,
    val startModeTelemetryUpdates: () -> Unit,
    val onAfterStopCleanup: (TSession?) -> Unit,
    val statusHooks: ServiceRuntimeStatusHooks,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession

/** Evaluated under the lifecycle mutex before any teardown side effect. */
internal class RuntimeStopGuard(
    val isCurrent: () -> Boolean,
    val failureReason: FailureReason? = null,
)

private fun ExplicitUserStartGuard.ensureCurrentStart() {
    if (!isCurrent()) throw CancellationException("Start intent superseded")
}

/** Suspended startup publications precede the synchronous final readiness commit. */
internal class RuntimeStartEvidencePublication<TSession>(
    val publish: suspend (TSession, ConnectionPolicyResolution, RuntimeStartEvidence) -> Unit,
    val complete: (TSession, ConnectionPolicyResolution, RuntimeStartEvidence) -> Unit,
    val publishCaptured: (TSession, () -> Unit) -> Boolean,
)

internal enum class RuntimeStopDisposition { StopService, RetainPausedShell }

enum class RuntimeStopOutcome { FullyReleased, Superseded, CleanupPending }
