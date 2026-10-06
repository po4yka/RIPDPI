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
    private val durable: com.poyka.ripdpi.data.PauseAuthorityRef,
) {
    internal val durableReference: com.poyka.ripdpi.data.PauseAuthorityRef get() = durable

    fun isCurrent(): Boolean =
        arbiter.runIfExplicitUserIntentCurrent(generation) { arbiter.isDurableCurrent(durable) } == true

    fun runIfCurrent(action: () -> Unit): Boolean =
        arbiter.runIfExplicitUserIntentCurrent(generation) {
            if (!arbiter.isDurableCurrent(durable)) {
                false
            } else {
                action()
                true
            }
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
