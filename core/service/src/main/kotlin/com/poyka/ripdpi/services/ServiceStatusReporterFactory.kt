package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

internal interface ServiceStatusReporterFactory {
    fun create(
        mode: Mode,
        sender: Sender,
        serviceStateStore: ServiceStateStore,
        networkFingerprintProvider: NetworkFingerprintProvider,
        telemetryFingerprintHasher: TelemetryFingerprintHasher,
        clock: ServiceClock = SystemServiceClock,
    ): ServiceStatusReporter
}

@Singleton
internal class DefaultServiceStatusReporterFactory
    @Inject
    constructor(
        private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    ) : ServiceStatusReporterFactory {
        override fun create(
            mode: Mode,
            sender: Sender,
            serviceStateStore: ServiceStateStore,
            networkFingerprintProvider: NetworkFingerprintProvider,
            telemetryFingerprintHasher: TelemetryFingerprintHasher,
            clock: ServiceClock,
        ): ServiceStatusReporter =
            ServiceStatusReporter(
                mode = mode,
                sender = sender,
                serviceStateStore = serviceStateStore,
                networkFingerprintProvider = networkFingerprintProvider,
                telemetryFingerprintHasher = telemetryFingerprintHasher,
                runtimeExperimentSelectionProvider = runtimeExperimentSelectionProvider,
                clock = clock,
            )
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ServiceStatusReporterFactoryModule {
    @Binds
    @Singleton
    abstract fun bindServiceStatusReporterFactory(
        factory: DefaultServiceStatusReporterFactory,
    ): ServiceStatusReporterFactory
}
