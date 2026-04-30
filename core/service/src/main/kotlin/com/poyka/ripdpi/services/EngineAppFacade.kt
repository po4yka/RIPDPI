package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.RipDpiPlatformCapabilities
import com.poyka.ripdpi.core.clearHostAutolearnStore
import com.poyka.ripdpi.core.hasHostAutolearnStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface HostAutolearnStoreController {
    fun hasStore(): Boolean

    fun clearStore(): Boolean
}

interface EnginePlatformCapabilities {
    fun seqovlSupported(): Boolean
}

@Singleton
class DefaultHostAutolearnStoreController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : HostAutolearnStoreController {
        override fun hasStore(): Boolean = hasHostAutolearnStore(context)

        override fun clearStore(): Boolean = clearHostAutolearnStore(context)
    }

@Singleton
class DefaultEnginePlatformCapabilities
    @Inject
    constructor() : EnginePlatformCapabilities {
        override fun seqovlSupported(): Boolean = RipDpiPlatformCapabilities.seqovlSupported()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineAppFacadeModule {
    @Binds
    @Singleton
    abstract fun bindHostAutolearnStoreController(
        controller: DefaultHostAutolearnStoreController,
    ): HostAutolearnStoreController

    @Binds
    @Singleton
    abstract fun bindEnginePlatformCapabilities(
        capabilities: DefaultEnginePlatformCapabilities,
    ): EnginePlatformCapabilities
}
