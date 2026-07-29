@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

internal class BridgeDetachment(
    val confirmed: Boolean,
    private val publication: () -> Unit,
) {
    internal fun publish() = publication()
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
        private val pendingRetirements = LinkedHashSet<Job>()
        private var acceptingRetirements = true

        internal fun schedule(bridge: NetworkDiagnosticsBridge) {
            synchronized(lifecycleLock) {
                check(acceptingRetirements) { "Diagnostics bridge retirement queue is closed" }
                retirementScope
                    .launch(start = CoroutineStart.LAZY) {
                        if (runCatching { bridge.destroy() }.isFailure) {
                            Logger.w { "Asynchronous diagnostics bridge retirement failed" }
                        }
                    }.also { retirement ->
                        pendingRetirements += retirement
                        retirement.invokeOnCompletion {
                            synchronized(lifecycleLock) {
                                pendingRetirements -= retirement
                            }
                        }
                        retirement.start()
                    }
            }
        }

        internal suspend fun closeAndDrain() {
            val pending =
                synchronized(lifecycleLock) {
                    acceptingRetirements = false
                    pendingRetirements.toList()
                }
            pending.joinAll()
            retirementJob.complete()
            retirementJob.join()
        }
    }
