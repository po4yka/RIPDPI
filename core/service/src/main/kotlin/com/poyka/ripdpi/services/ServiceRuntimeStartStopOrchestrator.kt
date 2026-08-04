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
    suspend fun start(stopSelfStartId: Int? = null) {
        Logger.i { "Starting ${dependencies.serviceLabel()}" }

        var matchedRememberedPolicy: RememberedNetworkPolicyEntity? = null
        val session = callbacks.createRuntimeSession()
        val failure =
            dependencies.lifecycleRunner.start {
                session.networkHandoverState = null
                val resolution = callbacks.resolveInitialConnectionPolicy()
                matchedRememberedPolicy = resolution.matchedNetworkPolicy
                callbacks.applyActiveConnectionPolicy(
                    session,
                    resolution,
                    "initial_start",
                    dependencies.clock.nowMillis(),
                )
                callbacks.startResolvedRuntime(
                    session,
                    resolution,
                )
                callbacks.setRuntimeSession(session)
                dependencies.serviceRuntimeRegistry.register(session)
                callbacks.updateStatus(ServiceStatus.Connected, null)
                dependencies.handoverProcessor.startMonitoring()
                callbacks.startModeTelemetryUpdates()
                dependencies.loopOwner.startPermissionWatchdog()
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
            dependencies.loopOwner.cancelPermissionWatchdog()
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
                callbacks.updateStatus(ServiceStatus.Disconnected, null)
                dependencies.loopOwner.cancelTelemetry()
                callbacks.onAfterStopCleanup(session)
                session?.clearActiveConnectionPolicy()
                session?.let {
                    dependencies.serviceRuntimeRegistry.unregister(
                        mode = dependencies.mode,
                        runtimeId = it.runtimeId,
                    )
                }
                callbacks.setRuntimeSession(null)
                dependencies.host.requestStopSelf(stopSelfStartId)
            }
        }
        terminalTelemetryCancellation?.let { throw it }
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
    val currentSession: () -> TSession?,
    val setRuntimeSession: (TSession?) -> Unit,
    val createRuntimeSession: () -> TSession,
    val resolveInitialConnectionPolicy: suspend () -> ConnectionPolicyResolution,
    val applyActiveConnectionPolicy: (TSession, ConnectionPolicyResolution, String, Long) -> Unit,
    val startResolvedRuntime: suspend (TSession, ConnectionPolicyResolution) -> Unit,
    val captureFinalTelemetry: suspend () -> Unit = {},
    val stopModeRuntime: suspend (Boolean) -> Unit,
    val startModeTelemetryUpdates: () -> Unit,
    val onAfterStopCleanup: (TSession?) -> Unit,
    val updateStatus: (ServiceStatus, FailureReason?) -> Unit,
    val classifyStartupFailure: (Exception) -> FailureReason,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession
