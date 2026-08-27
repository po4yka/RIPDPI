package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Authority for one accepted user start; mutations must be synchronous under [runIfCurrent]. */
class ExplicitUserStartGuard internal constructor(
    private val arbiter: ServiceIntentArbiter,
    private val generation: Long,
) {
    fun isCurrent(): Boolean = arbiter.runIfExplicitUserIntentCurrent(generation) { true } == true

    fun runIfCurrent(action: () -> Unit): Boolean =
        arbiter.runIfExplicitUserIntentCurrent(generation) {
            action()
            true
        } == true
}

/** Flavor-specific preparation that must complete before an explicit user runtime start. */
interface ExplicitUserStartPreparer {
    suspend fun prepare(
        mode: Mode,
        guard: ExplicitUserStartGuard,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExplicitUserStartPreparerOptionalBindingsModule {
    @BindsOptionalOf
    abstract fun bindExplicitUserStartPreparer(): ExplicitUserStartPreparer
}
