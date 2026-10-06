package com.poyka.ripdpi.services

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local user lifecycle intent used to invalidate diagnostics resume leases.
 *
 * Diagnostics temporarily stop a running service to observe the raw network path.
 * A later explicit start or stop supersedes that temporary pause; service status
 * alone cannot express which caller owns the halted state.
 */
@Singleton
class RuntimeResumeIntentTracker
    @Inject
    constructor(
        private val pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority,
    ) {
        private val lock = Any()
        private var state = State()

        internal fun currentDurableAuthority() = pauseAuthority.reference()

        internal fun captureResumeLease(): ResumeLease =
            synchronized(lock) {
                ResumeLease(state.generation, pauseAuthority.reference())
            }

        internal fun ownership(lease: ResumeLease): ResumeLeaseOwnership =
            synchronized(lock) {
                ownershipLocked(lease)
            }

        internal fun <T : Any> runIfOwned(
            lease: ResumeLease,
            action: () -> T,
        ): T? = if (ownership(lease) == ResumeLeaseOwnership.Owned) action() else null

        internal fun runCompensatingStopIfCurrent(
            expected: ResumeLeaseOwnership.Superseded,
            action: () -> Unit,
        ): Boolean {
            val currentStop =
                synchronized(lock) {
                    state.generation == expected.generation && state.intent == UserRuntimeIntent.Stopped
                }
            if (currentStop) action()
            return currentStop
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun <T> withUserStart(
            action: () -> T,
            isAccepted: (T) -> Boolean = { true },
        ): T {
            val reservation =
                synchronized(lock) {
                    val previous = state
                    recordLocked(UserRuntimeIntent.Running)
                    previous to state
                }
            try {
                return action().also { result ->
                    if (!isAccepted(result)) rollbackStartIfOwned(reservation)
                }
            } catch (failure: Exception) {
                rollbackStartIfOwned(reservation)
                throw failure
            }
        }

        private fun rollbackStartIfOwned(reservation: Pair<State, State>) =
            synchronized(lock) {
                if (state == reservation.second) state = reservation.first
            }

        internal fun recordAcceptedStart() {
            synchronized(lock) {
                if (state.intent != UserRuntimeIntent.Running) {
                    recordLocked(UserRuntimeIntent.Running)
                }
            }
        }

        internal fun recordAcceptedStop() {
            synchronized(lock) {
                recordLocked(UserRuntimeIntent.Stopped)
            }
        }

        internal fun isCurrentIntentStopped(): Boolean =
            synchronized(lock) {
                state.intent == UserRuntimeIntent.Stopped
            }

        private fun ownershipLocked(lease: ResumeLease): ResumeLeaseOwnership =
            if (state.generation == lease.generation && state.intent != UserRuntimeIntent.Stopped &&
                pauseAuthority.reference() == lease.durableAuthority
            ) {
                ResumeLeaseOwnership.Owned
            } else {
                ResumeLeaseOwnership.Superseded(
                    generation = state.generation,
                    intent = state.intent,
                    durableAuthority = state.durableAuthority ?: lease.durableAuthority,
                )
            }

        private fun recordLocked(intent: UserRuntimeIntent) {
            state =
                State(
                    generation = state.generation + 1,
                    intent = intent,
                    durableAuthority = pauseAuthority.reference(),
                )
        }

        private data class State(
            val generation: Long = 0,
            val intent: UserRuntimeIntent = UserRuntimeIntent.Unknown,
            val durableAuthority: com.poyka.ripdpi.data.PauseAuthorityRef? = null,
        )
    }

internal data class ResumeLease(
    val generation: Long,
    val durableAuthority: com.poyka.ripdpi.data.PauseAuthorityRef,
)

internal sealed interface ResumeLeaseOwnership {
    data object Owned : ResumeLeaseOwnership

    data class Superseded(
        val generation: Long,
        val intent: UserRuntimeIntent,
        val durableAuthority: com.poyka.ripdpi.data.PauseAuthorityRef,
    ) : ResumeLeaseOwnership
}

internal enum class UserRuntimeIntent {
    Unknown,
    Running,
    Stopped,
}
