package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
                val session = callbacks.createRuntimeSession()
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
                callbacks.publishRuntimeStartEvidence(
                    session,
                    resolution,
                    runtimeStartEvidence,
                )
                callbacks.setRuntimeSession(session)
                dependencies.serviceRuntimeRegistry.register(session)
                callbacks.updateStatus(ServiceStatus.Connected, null)
                dependencies.handoverProcessor.startMonitoring()
                callbacks.startModeTelemetryUpdates()
                dependencies.loopOwner.startPermissionWatchdog()
                transaction?.onStarted?.invoke()
            }
                ?: return
        val error =
            failure as? Exception ?: IllegalStateException(
                "Failed to start ${dependencies.serviceLabel()}",
                failure,
            )
        val classifiedError = error.unwrapSupervisorStartupFailure()
        Logger.e(classifiedError) { "Failed to start ${dependencies.serviceLabel()}" }
        matchedRememberedPolicy?.let { policy ->
            dependencies.rememberedNetworkPolicyStore.recordFailure(policy)
        }
        val failureReason = callbacks.classifyStartupFailure(classifiedError)
        callbacks.updateStatus(ServiceStatus.Failed, failureReason)
        stop(stopSelfStartId = stopSelfStartId)
    }

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
    ) {
        Logger.i { "Stopping ${dependencies.serviceLabel()}" }
        dependencies.handoverProcessor.cancel()

        var terminalTelemetryCancellation: CancellationException? = null
        dependencies.lifecycleRunner.stop {
            terminalTelemetryCancellation =
                finalizeRuntimeStop(
                    skipRuntimeShutdown = skipRuntimeShutdown,
                    stopSelfStartId = stopSelfStartId,
                    requestServiceStop = true,
                )
        }
        terminalTelemetryCancellation?.let { throw it }
    }

    private suspend fun finalizeRuntimeStop(
        skipRuntimeShutdown: Boolean,
        stopSelfStartId: Int?,
        requestServiceStop: Boolean,
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
                    Logger.e(failure) { "Failed to stop ${dependencies.serviceLabel()} runtime" }
                }

            val session = callbacks.currentSession()
            runCatching { callbacks.updateStatus(ServiceStatus.Disconnected, null) }
                .onFailure { failure ->
                    Logger.e(failure) { "Failed to publish stopped ${dependencies.serviceLabel()} status" }
                }
            dependencies.loopOwner.cancelTelemetry()
            runCatching { callbacks.onAfterStopCleanup(session) }
                .onFailure { failure ->
                    Logger.e(failure) { "Failed to clean up stopped ${dependencies.serviceLabel()} runtime" }
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
    val applyActiveConnectionPolicy: (TSession, ConnectionPolicyResolution, String, Long) -> Unit,
    val startResolvedRuntime: suspend (TSession, ConnectionPolicyResolution) -> RuntimeStartEvidence,
    val publishRuntimeStartEvidence: suspend (TSession, ConnectionPolicyResolution, RuntimeStartEvidence) -> Unit,
    val captureFinalTelemetry: suspend () -> Unit = {},
    val stopModeRuntime: suspend (Boolean) -> Unit,
    val startModeTelemetryUpdates: () -> Unit,
    val onAfterStopCleanup: (TSession?) -> Unit,
    val updateStatus: (ServiceStatus, FailureReason?) -> Unit,
    val classifyStartupFailure: (Exception) -> FailureReason,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession
