package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Mandatory canonical post-image capture, performed before the short claim/ACK locks. */
internal fun interface RequestedRuntimeConfigurationSource {
    suspend fun capture(
        mode: Mode,
        settings: AppSettings,
        awg: AwgActivationRequest?,
    ): RequestedRuntimeConfiguration
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RequestedRuntimeConfigurationSourceModule {
    @Binds
    @Singleton
    abstract fun source(capture: RequestedRuntimeConfigurationCapture): RequestedRuntimeConfigurationSource
}
