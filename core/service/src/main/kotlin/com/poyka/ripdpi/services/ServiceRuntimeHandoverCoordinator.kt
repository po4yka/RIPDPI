package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ServiceRuntimeHandoverCoordinator<TSession>(
    private val dependencies: ServiceRuntimeHandoverDependencies<TSession>,
    private val hooks: ServiceRuntimeModeHooks<TSession>,
    private val stopService: suspend (RuntimeStopGuard) -> Unit,
    private val resumeTelemetry: () -> Unit,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private companion object {
        private const val HandoverCooldownMs = 10_000L
        private const val MaxHandoverRetries = 4
        private const val HandoverRetryBaseMs = 2_000L
        private const val HandoverRetryMaxMs = 30_000L
    }

    private val state = dependencies.state

    @Volatile
    private var recoverableRuntimeId: String? = null
    private val handoverRestarter =
        ServiceRuntimeHandoverRestarter(
            mode = dependencies.mode,
            mutex = dependencies.mutex,
            policyHandoverEventStore = dependencies.policyHandoverEventStore,
            currentSession = state.currentSession,
            canRestart = ::canRestart,
            setHandoverRestarting = state.setHandoverRestarting,
            resolveConnectionPolicy = hooks.handoverHooks.resolveConnectionPolicy,
            restartAfterHandover = ::restartAfterHandover,
        )

    val processor =
        NetworkHandoverProcessor(
            scope = dependencies.scope,
            dispatcher = dependencies.dispatcher,
            networkHandoverMonitor = dependencies.networkHandoverMonitor,
            retryPolicy =
                ExponentialHandoverRetryPolicy(
                    maxRetries = MaxHandoverRetries,
                    baseDelayMillis = HandoverRetryBaseMs,
                    maxDelayMillis = HandoverRetryMaxMs,
                ),
            clock = dependencies.clock,
            serviceLabel = { hooks.serviceLabel },
            currentSession = state.currentSession,
            canRestart = ::canRestart,
            isStopping = state.isStopping,
            recordPendingClassification = { classification ->
                state.currentSession()?.pendingNetworkHandoverClass = classification
            },
            updateHandoverState = { handoverState ->
                state.currentSession()?.networkHandoverState = handoverState
            },
            performRestart = handoverRestarter::restart,
            onExhaustedFailure = ::handleExhaustedHandoverFailure,
            handoverCooldownMillis = HandoverCooldownMs,
        )

    fun cancel() {
        processor.cancel()
    }

    fun consumePendingNetworkHandoverClass(): String? =
        state.currentSession()?.pendingNetworkHandoverClass?.also {
            state.currentSession()?.pendingNetworkHandoverClass = null
        }

    fun currentNetworkHandoverState(): String? = state.currentSession()?.networkHandoverState

    fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val classification = consumePendingNetworkHandoverClass() ?: return snapshot
        return snapshot.copy(networkHandoverClass = classification)
    }

    private fun isRecovering(runtimeId: String): Boolean =
        state.currentStatus() == ServiceStatus.Failed && recoverableRuntimeId == runtimeId

    private fun canRestart(
        session: TSession,
        fingerprint: NetworkFingerprint,
    ): Boolean =
        !state.isStopping() &&
            (
                state.currentStatus() == ServiceStatus.Connected ||
                    (isRecovering(session.runtimeId) && fingerprint.networkValidated)
            )

    private suspend fun restartAfterHandover(
        session: TSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
        isCurrentAttempt: () -> Boolean,
    ): Boolean {
        val recovering = isRecovering(session.runtimeId)
        try {
            hooks.handoverHooks.restartAfterHandover(session, resolution, appliedAt)
            return !recovering || completeRecovery(session, isCurrentAttempt)
        } finally {
            // A failed or superseded recovery may have installed a forwarding bridge.
            if (recovering && isRecovering(session.runtimeId)) {
                hooks.handoverHooks.retainFailClosedAfterExhaustion()
            }
        }
    }

    private fun completeRecovery(
        session: TSession,
        isCurrentAttempt: () -> Boolean,
    ): Boolean {
        var resumed = false
        hooks.startHooks.evidencePublication.publishCaptured(session) {
            if (isCurrentAttempt()) {
                session.networkHandoverState = NetworkHandoverStates.RuntimeReady
                hooks.statusHooks.publishConnected()
                recoverableRuntimeId = null
                resumed = true
            }
        }
        if (resumed) {
            hooks.statusHooks.reportConnected()
            resumeTelemetry()
        }
        return resumed
    }

    private suspend fun handleExhaustedHandoverFailure(failure: HandoverExhaustedFailure) {
        val reason = hooks.handoverHooks.classifyFailure(failure.error)
        val retainedFailClosed =
            dependencies.mutex.withLock {
                val session = state.currentSession()
                if (isStaleExhaustedFailure(failure, session)) return@withLock null
                val retained = hooks.handoverHooks.retainFailClosedAfterExhaustion()
                if (isStaleExhaustedFailure(failure, session)) return@withLock null
                val reportRetained = hooks.handoverHooks.reportRetainedHandoverFailure?.takeIf { retained }
                recoverableRuntimeId = failure.runtimeId.takeIf { reportRetained != null }
                session?.lastSuccessfulHandoverFingerprintHash = null
                session?.networkHandoverState = NetworkHandoverStates.Failed
                if (reportRetained != null) {
                    reportRetained(reason)
                } else {
                    hooks.statusHooks.updateStatus(ServiceStatus.Failed, reason)
                }
                retained
            }
        if (retainedFailClosed == false) {
            // The mutex was released above; a queued Start may already own a newer session.
            stopService(RuntimeStopGuard(isCurrent = { state.currentSession()?.runtimeId == failure.runtimeId }))
        }
    }

    private fun isStaleExhaustedFailure(
        failure: HandoverExhaustedFailure,
        session: TSession?,
    ): Boolean =
        !failure.isCurrentAttempt() ||
            session?.runtimeId != failure.runtimeId ||
            (state.currentStatus() != ServiceStatus.Connected && !isRecovering(failure.runtimeId)) ||
            state.isStopping()
}

@Suppress("LongParameterList")
internal class ServiceRuntimeHandoverDependencies<TSession>(
    val mode: Mode,
    val scope: CoroutineScope,
    val dispatcher: CoroutineDispatcher,
    val mutex: Mutex,
    val networkHandoverMonitor: NetworkHandoverMonitor,
    val policyHandoverEventStore: PolicyHandoverEventStore,
    val clock: ServiceClock,
    val state: ServiceRuntimeSharedState<TSession>,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession
