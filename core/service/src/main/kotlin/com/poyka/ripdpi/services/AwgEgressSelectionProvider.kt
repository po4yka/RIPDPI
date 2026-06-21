package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Optional source of a VPN-mode AmneziaWG egress selected outside AppSettings. */
interface AwgEgressSelectionProvider {
    suspend fun selectedAwgEgress(): AwgActivationRequest?
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AwgEgressSelectionProviderOptionalBindingsModule {
    @BindsOptionalOf
    abstract fun bindAwgEgressSelectionProvider(): AwgEgressSelectionProvider
}
