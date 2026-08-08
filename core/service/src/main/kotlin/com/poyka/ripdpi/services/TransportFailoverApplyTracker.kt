package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.awgConfigOrNull
import com.poyka.ripdpi.core.relayConfigOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private enum class TransportFailoverApplyPhase {
    Pending,
    Applying,
    Applied,
    RollbackSafeFailure,
    TimedOutInFlight,
    Cancelled,
}

enum class TransportFailoverApplyOutcome {
    Applied,
    RollbackSafeFailure,
    TimedOutInFlight,
}

private data class TransportFailoverApplyState(
    val requestId: Long = 0L,
    val phase: TransportFailoverApplyPhase = TransportFailoverApplyPhase.Cancelled,
)

/** Immutable transport identity carried from the failover decision to the applied policy. */
data class TransportFailoverTarget(
    val transportKind: String,
    val profileId: String,
) {
    init {
        require(transportKind.isNotBlank()) { "Transport failover kind must not be blank" }
        require(profileId.isNotBlank()) { "Transport failover profile id must not be blank" }
    }
}

internal fun ConnectionPolicyResolution.transportFailoverTargetOrNull(): TransportFailoverTarget? =
    proxyPreferences.awgConfigOrNull()?.let { awg ->
        TransportFailoverTarget(
            transportKind = TransportKindAmneziaWg,
            profileId = awg.profileId,
        )
    } ?: proxyPreferences.relayConfigOrNull()?.let { relay ->
        TransportFailoverTarget(
            transportKind = relay.kind,
            profileId = relay.profileId,
        )
    }

const val TransportKindAmneziaWg = "amneziawg"

/**
 * Process-local acknowledgement channel for an in-session VPN transport replacement.
 *
 * [ServiceStartResult.Accepted] only proves that Android accepted the service intent. The
 * transport is applied later on the VPN service command queue. A request is therefore complete
 * only when that queue reports [recordApplied] after the runtime policy restart returns.
 */
@Singleton
class TransportFailoverApplyTracker
    @Inject
    constructor() {
        private val requestIds = AtomicLong()
        private val state = MutableStateFlow(TransportFailoverApplyState())
        private var runtimeOwnedRequestId: Long? = null

        fun begin(): Long =
            checkNotNull(tryBegin()) {
                "Cannot supersede a transport failover while runtime application is in progress"
            }

        @Synchronized
        fun tryBegin(): Long? {
            if (
                runtimeOwnedRequestId != null ||
                state.value.phase in
                setOf(
                    TransportFailoverApplyPhase.Pending,
                    TransportFailoverApplyPhase.Applying,
                )
            ) {
                return null
            }
            val requestId = requestIds.incrementAndGet()
            state.value =
                TransportFailoverApplyState(
                    requestId = requestId,
                    phase = TransportFailoverApplyPhase.Pending,
                )
            return requestId
        }

        @Synchronized
        fun claimApplying(requestId: Long): Boolean {
            val current = state.value
            if (
                runtimeOwnedRequestId != null ||
                current.requestId != requestId ||
                current.phase != TransportFailoverApplyPhase.Pending
            ) {
                return false
            }
            runtimeOwnedRequestId = requestId
            state.value = current.copy(phase = TransportFailoverApplyPhase.Applying)
            return true
        }

        @Synchronized
        fun recordApplied(requestId: Long): Boolean {
            val current = state.value
            if (
                runtimeOwnedRequestId != requestId ||
                current.requestId != requestId ||
                current.phase !in
                setOf(
                    TransportFailoverApplyPhase.Applying,
                    TransportFailoverApplyPhase.TimedOutInFlight,
                )
            ) {
                return false
            }
            state.value = current.copy(phase = TransportFailoverApplyPhase.Applied)
            return true
        }

        fun recordRollbackSafeFailure(requestId: Long): Boolean =
            completeFromActive(requestId, TransportFailoverApplyPhase.RollbackSafeFailure)

        fun recordRuntimeFailure(
            requestId: Long,
            rollbackSafe: Boolean,
        ): Boolean =
            completeFromActive(
                requestId,
                if (rollbackSafe) {
                    TransportFailoverApplyPhase.RollbackSafeFailure
                } else {
                    TransportFailoverApplyPhase.TimedOutInFlight
                },
            )

        @Synchronized
        fun releaseRuntimeOwnership(requestId: Long) {
            if (runtimeOwnedRequestId == requestId) {
                runtimeOwnedRequestId = null
            }
        }

        fun cancel(requestId: Long): Boolean =
            complete(
                requestId = requestId,
                expectedPhase = TransportFailoverApplyPhase.Pending,
                phase = TransportFailoverApplyPhase.Cancelled,
            )

        /**
         * Waits for the exact request to finish, rejecting stale completions from older intents.
         *
         * Once the runtime has claimed [TransportFailoverApplyPhase.Applying], the first timeout
         * cannot revoke ownership. A second bounded window lets the runtime watchdog finish; if
         * it does not, the outcome is [TransportFailoverApplyOutcome.TimedOutInFlight]. Runtime
         * ownership remains fenced and the caller must not roll persisted settings back while
         * the service may still complete side effects for the captured transport.
         *
         * // cancel-safe: cancellation leaves ownership unchanged. The caller must invoke
         * [settleCancellation] from NonCancellable before deciding whether to roll settings back.
         */
        suspend fun awaitOutcome(
            requestId: Long,
            timeoutMillis: Long,
        ): TransportFailoverApplyOutcome {
            val completed =
                withTimeoutOrNull(timeoutMillis) {
                    state.first { current -> current.isTerminalFor(requestId) }
                }
            if (completed != null) return completed.outcomeFor(requestId)

            return when (val phase = cancelPendingOrReadPhase(requestId)) {
                TransportFailoverApplyPhase.Applying -> awaitTerminalOrTimeOut(requestId, timeoutMillis)
                TransportFailoverApplyPhase.Applied -> TransportFailoverApplyOutcome.Applied
                TransportFailoverApplyPhase.TimedOutInFlight -> TransportFailoverApplyOutcome.TimedOutInFlight
                else -> TransportFailoverApplyOutcome.RollbackSafeFailure
            }
        }

        /** Resolves cancellation without rolling back a runtime-owned application. */
        suspend fun settleCancellation(
            requestId: Long,
            timeoutMillis: Long,
        ): TransportFailoverApplyOutcome =
            when (val phase = cancelPendingOrReadPhase(requestId)) {
                TransportFailoverApplyPhase.Applying -> awaitTerminalOrTimeOut(requestId, timeoutMillis)
                TransportFailoverApplyPhase.Applied -> TransportFailoverApplyOutcome.Applied
                TransportFailoverApplyPhase.TimedOutInFlight -> TransportFailoverApplyOutcome.TimedOutInFlight
                else -> TransportFailoverApplyOutcome.RollbackSafeFailure
            }

        private suspend fun awaitTerminalOrTimeOut(
            requestId: Long,
            timeoutMillis: Long,
        ): TransportFailoverApplyOutcome {
            val terminal =
                withTimeoutOrNull(timeoutMillis) {
                    state.first { current -> current.isTerminalFor(requestId) }
                }
            if (terminal != null) return terminal.outcomeFor(requestId)
            return timeOutApplyingOrReadOutcome(requestId)
        }

        @Synchronized
        private fun cancelPendingOrReadPhase(requestId: Long): TransportFailoverApplyPhase? {
            val current = state.value
            var phase: TransportFailoverApplyPhase? = null
            if (current.requestId == requestId) {
                phase = current.phase
                if (current.phase == TransportFailoverApplyPhase.Pending) {
                    phase = TransportFailoverApplyPhase.Cancelled
                    state.value = current.copy(phase = phase)
                }
            }
            return phase
        }

        @Synchronized
        private fun timeOutApplyingOrReadOutcome(requestId: Long): TransportFailoverApplyOutcome {
            val current = state.value
            if (
                current.requestId == requestId &&
                current.phase == TransportFailoverApplyPhase.Applying
            ) {
                state.value = current.copy(phase = TransportFailoverApplyPhase.TimedOutInFlight)
                return TransportFailoverApplyOutcome.TimedOutInFlight
            }
            return current.outcomeFor(requestId)
        }

        @Synchronized
        private fun completeFromActive(
            requestId: Long,
            phase: TransportFailoverApplyPhase,
        ): Boolean {
            val current = state.value
            if (
                current.requestId != requestId ||
                current.phase !in
                setOf(
                    TransportFailoverApplyPhase.Pending,
                    TransportFailoverApplyPhase.Applying,
                    TransportFailoverApplyPhase.TimedOutInFlight,
                )
            ) {
                return false
            }
            state.value = current.copy(phase = phase)
            return true
        }

        @Synchronized
        private fun complete(
            requestId: Long,
            expectedPhase: TransportFailoverApplyPhase,
            phase: TransportFailoverApplyPhase,
        ): Boolean =
            state.compareAndSet(
                expect =
                    TransportFailoverApplyState(
                        requestId = requestId,
                        phase = expectedPhase,
                    ),
                update = TransportFailoverApplyState(requestId = requestId, phase = phase),
            )

        private fun TransportFailoverApplyState.isTerminalFor(requestId: Long): Boolean =
            this.requestId != requestId ||
                phase in
                setOf(
                    TransportFailoverApplyPhase.Applied,
                    TransportFailoverApplyPhase.RollbackSafeFailure,
                    TransportFailoverApplyPhase.TimedOutInFlight,
                    TransportFailoverApplyPhase.Cancelled,
                )

        private fun TransportFailoverApplyState.outcomeFor(requestId: Long): TransportFailoverApplyOutcome =
            if (this.requestId != requestId) {
                TransportFailoverApplyOutcome.RollbackSafeFailure
            } else {
                when (phase) {
                    TransportFailoverApplyPhase.Applied -> {
                        TransportFailoverApplyOutcome.Applied
                    }

                    TransportFailoverApplyPhase.TimedOutInFlight -> {
                        TransportFailoverApplyOutcome.TimedOutInFlight
                    }

                    else -> {
                        TransportFailoverApplyOutcome.RollbackSafeFailure
                    }
                }
            }
    }
