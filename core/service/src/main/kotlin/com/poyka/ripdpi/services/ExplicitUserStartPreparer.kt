package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Flavor-specific preparation that must complete before an explicit user runtime start. */
interface ExplicitUserStartPreparer {
    suspend fun prepare(mode: Mode)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExplicitUserStartPreparerOptionalBindingsModule {
    @BindsOptionalOf
    abstract fun bindExplicitUserStartPreparer(): ExplicitUserStartPreparer
}
