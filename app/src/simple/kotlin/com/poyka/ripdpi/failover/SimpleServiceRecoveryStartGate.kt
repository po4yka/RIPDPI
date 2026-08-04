package com.poyka.ripdpi.failover

import com.poyka.ripdpi.AppStartupReadiness
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.services.ServiceRecoveryStartGate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SimpleServiceRecoveryStartGate
    @Inject
    constructor(
        private val appStartupReadiness: AppStartupReadiness,
    ) : ServiceRecoveryStartGate {
        override suspend fun awaitReady(): Boolean =
            appStartupReadiness.readiness.first { state ->
                state != AppStartupReadinessState.Pending
            } == AppStartupReadinessState.Ready
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SimpleServiceRecoveryStartGateModule {
    @Binds
    abstract fun bindServiceRecoveryStartGate(gate: SimpleServiceRecoveryStartGate): ServiceRecoveryStartGate
}
