package com.poyka.ripdpi.services

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Flavor-specific gate that must complete before a sticky VPN recovery reads persisted state. */
interface ServiceRecoveryStartGate {
    suspend fun awaitReady(): Boolean
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceRecoveryStartGateOptionalBindingsModule {
    @BindsOptionalOf
    abstract fun bindServiceRecoveryStartGate(): ServiceRecoveryStartGate
}
