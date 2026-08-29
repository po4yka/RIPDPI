package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
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
    private val stopService: suspend () -> Unit,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession {
    private companion object {
        private const val HandoverCooldownMs = 10_000L
        private const val MaxHandoverRetries = 4
        private const val HandoverRetryBaseMs = 2_000L
        private const val HandoverRetryMaxMs = 30_000L
    }

    private val state = dependencies.state
    private val handoverRestarter =
        ServiceRuntimeHandoverRestarter(
            mode = dependencies.mode,
            mutex = dependencies.mutex,
            policyHandoverEventStore = dependencies.policyHandoverEventStore,
            currentSession = state.currentSession,
            currentStatus = state.currentStatus,
            isStopping = state.isStopping,
            setHandoverRestarting = state.setHandoverRestarting,
            resolveConnectionPolicy = hooks.handoverHooks.resolveConnectionPolicy,
            restartAfterHandover = hooks.handoverHooks.restartAfterHandover,
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
            currentStatus = state.currentStatus,
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

    private suspend fun handleExhaustedHandoverFailure(failure: HandoverExhaustedFailure) {
        val reason = hooks.handoverHooks.classifyFailure(failure.error)
        val retainedFailClosed =
            dependencies.mutex.withLock {
                val session = state.currentSession()
                if (isStaleExhaustedFailure(failure, session)) return@withLock null
                val retained = hooks.handoverHooks.retainFailClosedAfterExhaustion()
                if (isStaleExhaustedFailure(failure, session)) return@withLock null
                session?.networkHandoverState = NetworkHandoverStates.Failed
                hooks.statusHooks.updateStatus(ServiceStatus.Failed, reason)
                retained
            }
        if (retainedFailClosed == false) {
            stopService()
        }
    }

    private fun isStaleExhaustedFailure(
        failure: HandoverExhaustedFailure,
        session: TSession?,
    ): Boolean =
        !failure.isCurrentAttempt() ||
            session?.runtimeId != failure.runtimeId ||
            state.currentStatus() != ServiceStatus.Connected ||
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
