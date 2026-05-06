package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ServiceCoordinatorHost {
    val serviceScope: CoroutineScope

    fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    )

    fun requestStopSelf(stopSelfStartId: Int?)
}

internal interface VpnCoordinatorHost :
    ServiceCoordinatorHost,
    VpnTunnelBuilderHost {
    fun syncUnderlyingNetworksFromActiveNetwork()
}

internal interface HandoverAwareSession {
    var pendingNetworkHandoverClass: String?
    var networkHandoverState: String?
    var lastSuccessfulHandoverFingerprintHash: String?
    var lastSuccessfulHandoverAt: Long
    var handoverRetryCount: Int
}

@Suppress("TooManyFunctions")
internal abstract class BaseServiceRuntimeCoordinator<TSession>(
    private val mode: Mode,
    protected val host: ServiceCoordinatorHost,
    protected val connectionPolicyResolver: ConnectionPolicyResolver,
    protected val serviceRuntimeRegistry: ServiceRuntimeRegistry,
    private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    private val policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    protected val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    protected val clock: ServiceClock = SystemServiceClock,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private companion object {
        private const val HandoverCooldownMs = 10_000L
        private const val MaxHandoverRetries = 4
        private const val HandoverRetryBaseMs = 2_000L
        private const val HandoverRetryMaxMs = 30_000L
    }

    protected val mutex = Mutex()
    protected val lifecycleState = ServiceLifecycleStateMachine()

    @Volatile
    protected var stopping: Boolean = false

    protected var status: ServiceStatus = ServiceStatus.Disconnected
    protected var runtimeSession: TSession? = null
    protected val consumePendingNetworkHandoverClass: () -> String? = {
        runtimeSession?.pendingNetworkHandoverClass?.also {
            runtimeSession?.pendingNetworkHandoverClass = null
        }
    }
    protected val currentNetworkHandoverState: () -> String? = {
        runtimeSession?.networkHandoverState
    }

    private val lifecycleRunner =
        RuntimeLifecycleRunner(
            mutex = mutex,
            lifecycleState = lifecycleState,
            serviceLabel = { serviceLabel },
            isStopping = { stopping },
            setStopping = { stopping = it },
        )
    private val telemetryLoopCoordinator =
        ServiceTelemetryLoopCoordinator(
            scope = host.serviceScope,
            dispatcher = ioDispatcher,
        )
    private val permissionWatchdogCoordinator =
        PermissionWatchdogCoordinator(
            scope = host.serviceScope,
            dispatcher = ioDispatcher,
            permissionWatchdog = permissionWatchdog,
            onPermissionRevoked = ::onPermissionRevoked,
        )
    private val handoverProcessor =
        NetworkHandoverProcessor(
            scope = host.serviceScope,
            dispatcher = ioDispatcher,
            networkHandoverMonitor = networkHandoverMonitor,
            retryPolicy =
                ExponentialHandoverRetryPolicy(
                    maxRetries = MaxHandoverRetries,
                    baseDelayMillis = HandoverRetryBaseMs,
                    maxDelayMillis = HandoverRetryMaxMs,
                ),
            clock = clock,
            serviceLabel = { serviceLabel },
            currentSession = { runtimeSession },
            currentStatus = { status },
            isStopping = { stopping },
            recordPendingClassification = { classification ->
                runtimeSession?.pendingNetworkHandoverClass = classification
            },
            updateHandoverState = { state ->
                runtimeSession?.networkHandoverState = state
            },
            performRestart = ::performHandoverRestart,
            onExhaustedFailure = ::handleExhaustedHandoverFailure,
            handoverCooldownMillis = HandoverCooldownMs,
        )

    protected abstract val serviceLabel: String

    suspend fun start() {
        Logger.i { "Starting $serviceLabel" }

        var matchedRememberedPolicy: RememberedNetworkPolicyEntity? = null
        val session = createRuntimeSession()
        val failure =
            lifecycleRunner.start {
                session.networkHandoverState = null
                val resolution = resolveInitialConnectionPolicy()
                matchedRememberedPolicy = resolution.matchedNetworkPolicy
                applyActiveConnectionPolicy(
                    session = session,
                    resolution = resolution,
                    restartReason = "initial_start",
                    appliedAt = clock.nowMillis(),
                )
                startResolvedRuntime(
                    session = session,
                    resolution = resolution,
                )
                runtimeSession = session
                serviceRuntimeRegistry.register(session)
                updateStatus(ServiceStatus.Connected)
                handoverProcessor.startMonitoring()
                startModeTelemetryUpdates()
                permissionWatchdogCoordinator.start()
            }
                ?: return
        val error = failure as? Exception ?: IllegalStateException("Failed to start $serviceLabel", failure)
        val classifiedError = error.unwrapSupervisorStartupFailure()
        Logger.e(classifiedError) { "Failed to start $serviceLabel" }
        matchedRememberedPolicy?.let { policy ->
            rememberedNetworkPolicyStore.recordFailure(policy)
        }
        val failureReason = classifyStartupFailure(classifiedError)
        updateStatus(ServiceStatus.Failed, failureReason)
        stop()
    }

    suspend fun stop(
        stopSelfStartId: Int? = null,
        skipRuntimeShutdown: Boolean = false,
    ) {
        Logger.i { "Stopping $serviceLabel" }

        val stopped =
            lifecycleRunner.stop {
                handoverProcessor.cancel()
                permissionWatchdogCoordinator.cancel()
                runCatching {
                    stopModeRuntime(skipRuntimeShutdown)
                }.onFailure { failure ->
                    val error =
                        failure as? Exception ?: IllegalStateException(
                            "Failed to stop $serviceLabel",
                            failure,
                        )
                    Logger.e(error) { "Failed to stop $serviceLabel" }
                }

                val session = runtimeSession
                updateStatus(ServiceStatus.Disconnected)
                telemetryLoopCoordinator.cancel()
                onAfterStopCleanup(session)
                session?.clearActiveConnectionPolicy()
                session?.let {
                    serviceRuntimeRegistry.unregister(
                        mode = mode,
                        runtimeId = it.runtimeId,
                    )
                }
                runtimeSession = null
                host.requestStopSelf(stopSelfStartId)
            }

        if (!stopped) {
            return
        }
    }

    fun onDestroy() {
        telemetryLoopCoordinator.cancel()
        handoverProcessor.cancel()
        permissionWatchdogCoordinator.cancel()
    }

    protected fun replaceTelemetryJob(block: suspend CoroutineScope.() -> Unit) {
        telemetryLoopCoordinator.replace(block)
    }

    protected fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val classification = consumePendingNetworkHandoverClass() ?: return snapshot
        return snapshot.copy(networkHandoverClass = classification)
    }

    protected open fun onPermissionRevoked(event: PermissionChangeEvent) = Unit

    private suspend fun performHandoverRestart(
        session: TSession,
        event: NetworkHandoverEvent,
        appliedAt: Long,
    ): String? {
        val currentFingerprint = checkNotNull(event.currentFingerprint)
        val currentFingerprintHash = currentFingerprint.scopeKey()
        val resolution =
            resolveHandoverConnectionPolicy(
                fingerprint = currentFingerprint,
                handoverClassification = event.classification,
            )

        val restartResult =
            mutex.withLock {
                val activeSession = runtimeSession
                if (
                    status != ServiceStatus.Connected ||
                    stopping ||
                    activeSession?.runtimeId != session.runtimeId
                ) {
                    return@withLock null
                }

                val previousFingerprintHash = activeSession.currentActiveConnectionPolicy?.fingerprintHash
                stopping = true
                try {
                    restartAfterHandover(
                        session = activeSession,
                        resolution = resolution,
                        appliedAt = appliedAt,
                    )
                    HandoverRestartResult(
                        previousFingerprintHash = previousFingerprintHash,
                        currentFingerprintHash = currentFingerprintHash,
                    )
                } finally {
                    stopping = false
                }
            } ?: return null

        policyHandoverEventStore.publish(
            PolicyHandoverEvent(
                mode = mode,
                previousFingerprintHash = restartResult.previousFingerprintHash,
                currentFingerprintHash = restartResult.currentFingerprintHash,
                classification = event.classification,
                currentNetworkValidated = currentFingerprint.networkValidated,
                currentCaptivePortalDetected = currentFingerprint.captivePortalDetected,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                policySignature = resolution.policySignature,
                occurredAt = appliedAt,
            ),
        )

        return currentFingerprintHash
    }

    private suspend fun handleExhaustedHandoverFailure(error: Exception) {
        val reason = classifyHandoverFailure(error)
        updateStatus(ServiceStatus.Failed, reason)
        stop()
    }

    protected abstract fun createRuntimeSession(): TSession

    protected abstract suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution

    protected abstract suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution

    protected abstract fun applyActiveConnectionPolicy(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    )

    protected abstract suspend fun startResolvedRuntime(
        session: TSession,
        resolution: ConnectionPolicyResolution,
    )

    protected abstract suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean)

    protected abstract fun startModeTelemetryUpdates()

    protected abstract suspend fun restartAfterHandover(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    )

    protected abstract fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason? = null,
    )

    protected abstract fun classifyStartupFailure(error: Exception): FailureReason

    protected abstract fun classifyHandoverFailure(error: Exception): FailureReason

    protected open fun onAfterStopCleanup(session: TSession?) = Unit
}

private data class HandoverRestartResult(
    val previousFingerprintHash: String?,
    val currentFingerprintHash: String,
)
