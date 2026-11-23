package com.poyka.ripdpi.services

import com.poyka.ripdpi.utility.shellSplit
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
    fun providePacketBackendRuntime(): PacketBackendRuntime = StandardPacketBackendRuntime
}

internal object StandardPacketBackendRuntime : PacketBackendRuntime by LocalPacketBackendRuntime {
    private val compatiblePrograms = setOf("ripdpi", "ripdpi-cli", "ciadpi", "byedpi")

    override fun acceptsCommandLine(arguments: String): Boolean {
        val first = shellSplit(arguments).firstOrNull() ?: return true
        return first.startsWith('-') || first.substringAfterLast('/').lowercase() in compatiblePrograms
    }
}
