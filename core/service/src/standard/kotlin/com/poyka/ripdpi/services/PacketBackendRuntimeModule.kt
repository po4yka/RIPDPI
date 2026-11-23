package com.poyka.ripdpi.services

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object PacketBackendRuntimeModule {
    @Provides
    @Singleton
    fun providePacketBackendRuntime(): PacketBackendRuntime = LocalPacketBackendRuntime
}
