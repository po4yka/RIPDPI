package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiContractJson
import com.poyka.ripdpi.serialization.RipDpiJson
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import com.poyka.ripdpi.serialization.RipDpiNativeProxyJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SerializationJsonModule {
    @Provides
    @Singleton
    fun provideRipDpiJson(): Json = RipDpiJson

    @Provides
    @Singleton
    @Named("ripdpiLenientJson")
    fun provideRipDpiLenientJson(): Json = RipDpiLenientJson

    @Provides
    @Singleton
    @Named("ripdpiContractJson")
    fun provideRipDpiContractJson(): Json = RipDpiContractJson

    @Provides
    @Singleton
    @Named("ripdpiNativeProxyJson")
    fun provideRipDpiNativeProxyJson(): Json = RipDpiNativeProxyJson
}
