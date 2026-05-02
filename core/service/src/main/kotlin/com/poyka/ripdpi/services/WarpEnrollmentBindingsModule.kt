package com.poyka.ripdpi.services

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WarpEnrollmentBindingsModule {
    @Binds
    @Singleton
    internal abstract fun bindWarpBootstrapProxyRunner(
        runner: ManagedWarpBootstrapProxyRunner,
    ): WarpBootstrapProxyRunner

    @Binds
    @Singleton
    abstract fun bindWarpEndpointScanner(scanner: DefaultWarpEndpointScanner): WarpEndpointScanner

    @Binds
    @Singleton
    abstract fun bindWarpEndpointProbe(probe: DefaultWarpEndpointProbe): WarpEndpointProbe

    @Binds
    @Singleton
    abstract fun bindWarpProfileActivationService(
        service: DefaultWarpProfileActivationService,
    ): WarpProfileActivationService

    @Binds
    @Singleton
    abstract fun bindWarpEnrollmentFlowService(service: DefaultWarpEnrollmentFlowService): WarpEnrollmentFlowService

    @Binds
    @Singleton
    abstract fun bindWarpCredentialProfileMutationService(
        service: DefaultWarpCredentialProfileMutationService,
    ): WarpCredentialProfileMutationService

    @Binds
    @Singleton
    abstract fun bindWarpEnrollmentOrchestrator(
        orchestrator: DefaultWarpEnrollmentOrchestrator,
    ): WarpEnrollmentOrchestrator
}
