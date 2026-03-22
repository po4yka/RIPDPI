package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        private val started = AtomicBoolean(false)

        override fun start() {
            if (!started.compareAndSet(false, true)) {
                return
            }

            scope.launch {
                serviceStateStore.status.collectLatest { (status, mode) ->
                    sessionCoordinator.handleStatusChange(status = status, mode = mode)
                }
            }

            scope.launch {
                serviceStateStore.telemetry.collectLatest { telemetry ->
                    sessionCoordinator.handleTelemetryUpdate(telemetry)
                }
            }

            scope.launch {
                serviceStateStore.events.collectLatest { event ->
                    when (event) {
                        is ServiceEvent.Failed -> sessionCoordinator.handleFailure(event.sender, event.reason)
                    }
                }
            }

            scope.launch {
                activeConnectionPolicyStore.activePolicies.collectLatest { policies ->
                    sessionCoordinator.handleActiveConnectionPolicyChange(
                        policies[serviceStateStore.status.value.second],
                    )
                }
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
