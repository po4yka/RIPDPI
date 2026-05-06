package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore

internal class ServiceRuntimeStartStopOrchestrator<TSession>(
    private val dependencies: ServiceRuntimeStartStopDependencies<TSession>,
    private val callbacks: ServiceRuntimeStartStopCallbacks<TSession>,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    suspend fun start() {
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
        stop()
    }

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
    ) {
        Logger.i { "Stopping ${dependencies.serviceLabel()}" }

        dependencies.lifecycleRunner.stop {
            dependencies.handoverProcessor.cancel()
            dependencies.loopOwner.cancelPermissionWatchdog()
            runCatching {
                callbacks.stopModeRuntime(skipRuntimeShutdown)
            }.onFailure { failure ->
                val error =
                    failure as? Exception ?: IllegalStateException(
                        "Failed to stop ${dependencies.serviceLabel()}",
                        failure,
                    )
                Logger.e(error) { "Failed to stop ${dependencies.serviceLabel()}" }
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
}

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
    val stopModeRuntime: suspend (Boolean) -> Unit,
    val startModeTelemetryUpdates: () -> Unit,
    val onAfterStopCleanup: (TSession?) -> Unit,
    val updateStatus: (ServiceStatus, FailureReason?) -> Unit,
    val classifyStartupFailure: (Exception) -> FailureReason,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession
