package com.poyka.ripdpi.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface ControlComparisonProvider {
    suspend fun compare(request: ScanRequest): ScanReport? = null
}

interface EchoProbeProvider {
    suspend fun probe(request: ScanRequest): List<ProbeResult> = emptyList()
}

@Singleton
internal class DisabledControlComparisonProvider
    @Inject
    constructor() : ControlComparisonProvider

@Singleton
internal class DisabledEchoProbeProvider
    @Inject
    constructor() : EchoProbeProvider

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiagnosticsComparisonProvidersModule {
    @Binds
    @Singleton
    abstract fun bindControlComparisonProvider(
        provider: DisabledControlComparisonProvider,
    ): ControlComparisonProvider

    @Binds
    @Singleton
    abstract fun bindEchoProbeProvider(
        provider: DisabledEchoProbeProvider,
    ): EchoProbeProvider
}
