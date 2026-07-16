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
    constructor() {
        private val lock = Any()
        private var state = State()

        internal fun captureResumeLease(): ResumeLease = synchronized(lock) { ResumeLease(state.generation) }

        internal fun ownership(lease: ResumeLease): ResumeLeaseOwnership =
            synchronized(lock) {
                ownershipLocked(lease)
            }

        internal fun <T : Any> runIfOwned(
            lease: ResumeLease,
            action: () -> T,
        ): T? =
            synchronized(lock) {
                if (ownershipLocked(lease) == ResumeLeaseOwnership.Owned) {
                    action()
                } else {
                    null
                }
            }

        internal fun runCompensatingStopIfCurrent(
            expected: ResumeLeaseOwnership.Superseded,
            action: () -> Unit,
        ): Boolean =
            synchronized(lock) {
                val isCurrentStop =
                    state.generation == expected.generation &&
                        state.intent == UserRuntimeIntent.Stopped
                if (isCurrentStop) {
                    action()
                }
                isCurrentStop
            }

        @Suppress("TooGenericExceptionCaught")
        internal fun <T> withUserStart(
            action: () -> T,
            isAccepted: (T) -> Boolean = { true },
        ): T =
            synchronized(lock) {
                val previous = state
                recordLocked(UserRuntimeIntent.Running)
                try {
                    action().also { result ->
                        if (!isAccepted(result)) {
                            state = previous
                        }
                    }
                } catch (failure: Exception) {
                    state = previous
                    throw failure
                }
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
            if (state.generation == lease.generation && state.intent != UserRuntimeIntent.Stopped) {
                ResumeLeaseOwnership.Owned
            } else {
                ResumeLeaseOwnership.Superseded(
                    generation = state.generation,
                    intent = state.intent,
                )
            }

        private fun recordLocked(intent: UserRuntimeIntent) {
            state =
                State(
                    generation = state.generation + 1,
                    intent = intent,
                )
        }

        private data class State(
            val generation: Long = 0,
            val intent: UserRuntimeIntent = UserRuntimeIntent.Unknown,
        )
    }

internal data class ResumeLease(
    val generation: Long,
)

internal sealed interface ResumeLeaseOwnership {
    data object Owned : ResumeLeaseOwnership

    data class Superseded(
        val generation: Long,
        val intent: UserRuntimeIntent,
    ) : ResumeLeaseOwnership
}

internal enum class UserRuntimeIntent {
    Unknown,
    Running,
    Stopped,
}
