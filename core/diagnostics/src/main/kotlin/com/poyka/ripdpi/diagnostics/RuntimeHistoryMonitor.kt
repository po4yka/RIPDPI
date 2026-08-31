package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.OrderedServiceStateStore
import com.poyka.ripdpi.data.ServiceHistoryEvent
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
        private val serviceStateStore: OrderedServiceStateStore,
        private val activeConnectionPolicyStore: ActiveConnectionPolicyStore,
        private val deviceRuntimeEvidenceStore: DeviceRuntimeEvidenceStore,
        private val networkPathValidationSource: NetworkPathValidationSource,
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
                networkPathValidationSource.evidence.value
                collectorJob = scope.launch { collectRuntimeHistory() }
            }
        }

        private suspend fun collectRuntimeHistory() =
            supervisorScope {
                launch {
                    serviceStateStore.historyEvents.collect { event ->
                        when (event) {
                            is ServiceHistoryEvent.StatusChanged -> {
                                persistSafely("lifecycle") {
                                    sessionCoordinator.handleStatusChange(event.status, event.mode)
                                }
                                if (event.status == AppStatus.Running) {
                                    persistSafely("telemetry") {
                                        sessionCoordinator.handleTelemetryUpdate(serviceStateStore.telemetry.value)
                                    }
                                }
                            }

                            is ServiceHistoryEvent.Failed -> {
                                persistSafely("lifecycle") {
                                    sessionCoordinator.handleFailure(event.event.sender, event.event.reason)
                                }
                            }
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
                    deviceRuntimeEvidenceStore.events.collect { event ->
                        persistSafely("device-runtime") {
                            sessionCoordinator.handleDeviceRuntimeEvidence(event)
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
