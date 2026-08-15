@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

internal class BridgeDetachment(
    val confirmed: Boolean,
    private val publication: () -> Unit,
) {
    internal fun publish() = publication()
}

internal sealed interface BridgeRetirementDrainResult {
    data object Drained : BridgeRetirementDrainResult

    data class Pending(
        val count: Int,
    ) : BridgeRetirementDrainResult
}

internal class BridgeRetirementReservation internal constructor(
    internal val bridge: NetworkDiagnosticsBridge,
    internal val completion: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    internal var state: BridgeRetirementReservationState = BridgeRetirementReservationState.RESERVED
}

internal enum class BridgeRetirementReservationState {
    RESERVED,
    COMMITTED,
    ABORTED,
    COMPLETED,
}

/**
 * Process-lifetime owner for native bridge retirements that must outlive canceled work scopes.
 * Android has no reliable graceful process-termination callback; [closeAndDrain] exists for
 * controlled component and test teardown and closes admission before joining tracked work.
 */
@Singleton
class BridgeRetirementQueue
    internal constructor(
        dispatcher: CoroutineDispatcher,
    ) {
        @Inject
        constructor(dispatchers: AppCoroutineDispatchers) : this(dispatchers.io)

        private val lifecycleLock = Any()
        private val retirementJob = SupervisorJob()
        private val retirementScope = CoroutineScope(retirementJob + dispatcher)
        private val outstandingRetirements = LinkedHashSet<BridgeRetirementReservation>()
        private var acceptingRetirements = true

        internal fun tryReserve(bridge: NetworkDiagnosticsBridge): BridgeRetirementReservation? =
            synchronized(lifecycleLock) {
                if (!acceptingRetirements) {
                    null
                } else {
                    BridgeRetirementReservation(bridge).also(outstandingRetirements::add)
                }
            }

        internal fun commit(reservation: BridgeRetirementReservation) {
            val retirement =
                synchronized(lifecycleLock) {
                    check(reservation in outstandingRetirements)
                    check(reservation.state == BridgeRetirementReservationState.RESERVED)
                    reservation.state = BridgeRetirementReservationState.COMMITTED
                    retirementScope
                        .launch(start = CoroutineStart.LAZY) {
                            if (runCatching { reservation.bridge.destroy() }.isFailure) {
                                Logger.w { "Asynchronous diagnostics bridge retirement failed" }
                            }
                        }
                }
            retirement.invokeOnCompletion { finish(reservation) }
            retirement.start()
        }

        internal fun abort(reservation: BridgeRetirementReservation): Boolean {
            val shouldCompleteSupervisor =
                synchronized(lifecycleLock) {
                    if (reservation.state != BridgeRetirementReservationState.RESERVED) {
                        return false
                    }
                    reservation.state = BridgeRetirementReservationState.ABORTED
                    outstandingRetirements.remove(reservation)
                    !acceptingRetirements && outstandingRetirements.isEmpty()
                }
            if (shouldCompleteSupervisor) retirementJob.complete()
            reservation.completion.complete(Unit)
            return true
        }

        private fun finish(reservation: BridgeRetirementReservation) {
            val shouldCompleteSupervisor =
                synchronized(lifecycleLock) {
                    reservation.state = BridgeRetirementReservationState.COMPLETED
                    outstandingRetirements.remove(reservation)
                    !acceptingRetirements && outstandingRetirements.isEmpty()
                }
            if (shouldCompleteSupervisor) retirementJob.complete()
            reservation.completion.complete(Unit)
        }

        internal suspend fun closeAndDrain(timeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS): BridgeRetirementDrainResult {
            require(timeoutMs >= 0L)
            val snapshot =
                synchronized(lifecycleLock) {
                    acceptingRetirements = false
                    outstandingRetirements.map(BridgeRetirementReservation::completion)
                }
            if (snapshot.isEmpty()) {
                retirementJob.complete()
                return BridgeRetirementDrainResult.Drained
            }
            withTimeoutOrNull(timeoutMs) {
                snapshot.forEach { completion -> completion.await() }
            }
            val remaining = synchronized(lifecycleLock) { outstandingRetirements.size }
            return if (remaining == 0) {
                BridgeRetirementDrainResult.Drained
            } else {
                BridgeRetirementDrainResult.Pending(remaining)
            }
        }

        internal val isSupervisorCompleted: Boolean
            get() = retirementJob.isCompleted

        private companion object {
            const val DEFAULT_DRAIN_TIMEOUT_MS = 2_000L
        }
    }
