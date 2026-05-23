package com.poyka.ripdpi.service.runtime.control

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the runtime control plane.
 *
 * Binds the process-level [ServiceControllerRuntimeControlActions] as the
 * [RuntimeControlActions] implementation and provides a singleton
 * [DefaultRuntimeControlPlane] over it. Keeping the binding here — the smallest
 * implementation site — means features depend only on the [RuntimeControlPlane]
 * seam, never on the actions wiring.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RuntimeControlPlaneModule {
    @Binds
    @Singleton
    abstract fun bindRuntimeControlActions(actions: ServiceControllerRuntimeControlActions): RuntimeControlActions

    companion object {
        @Provides
        @Singleton
        fun provideRuntimeControlPlane(actions: RuntimeControlActions): RuntimeControlPlane =
            DefaultRuntimeControlPlane(actions)
    }
}
