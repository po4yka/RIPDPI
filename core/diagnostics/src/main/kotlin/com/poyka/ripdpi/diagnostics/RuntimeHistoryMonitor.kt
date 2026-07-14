package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject
import javax.inject.Singleton

fun interface RuntimeHistoryStartup {
    fun start()
}

@Singleton
class RuntimeHistoryMonitor
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val activeConnectionPolicyStore: ActiveConnectionPolicyStore,
        private val sessionCoordinator: RuntimeSessionCoordinator,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : RuntimeHistoryStartup {
        private val lifecycleLock = Any()
        private var collectorJob: Job? = null

        override fun start() {
            synchronized(lifecycleLock) {
                if (collectorJob?.isActive == true) {
                    return
                }
                collectorJob = scope.launch { collectRuntimeHistory() }
            }
        }

        private suspend fun collectRuntimeHistory() =
            supervisorScope {
                launch {
                    serviceStateStore.status.collect { (status, mode) ->
                        persistSafely("status") {
                            sessionCoordinator.handleStatusChange(status = status, mode = mode)
                        }
                    }
                }

                launch {
                    serviceStateStore.telemetry.collect { telemetry ->
                        persistSafely("telemetry") {
                            sessionCoordinator.handleTelemetryUpdate(telemetry)
                        }
                    }
                }

                launch {
                    serviceStateStore.events.collect { event ->
                        persistSafely("events") {
                            when (event) {
                                is ServiceEvent.Failed -> sessionCoordinator.handleFailure(event.sender, event.reason)
                                is ServiceEvent.PermissionRevoked -> Unit
                            }
                        }
                    }
                }

                launch {
                    activeConnectionPolicyStore.activePolicies.collect { policies ->
                        persistSafely("active-policy") {
                            sessionCoordinator.handleActiveConnectionPolicyChange(
                                policies[serviceStateStore.status.value.second],
                            )
                        }
                    }
                }
            }

        private suspend fun persistSafely(
            lane: String,
            persist: suspend () -> Unit,
        ) {
            val failure = runCatching { persist() }.exceptionOrNull() ?: return
            when (failure) {
                is CancellationException -> throw failure
                is Exception -> Logger.e(failure) { "Runtime history $lane persistence failed" }
                else -> throw failure
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeHistoryMonitorModule {
    @Binds
    @Singleton
    abstract fun bindRuntimeHistoryStartup(monitor: RuntimeHistoryMonitor): RuntimeHistoryStartup
}
