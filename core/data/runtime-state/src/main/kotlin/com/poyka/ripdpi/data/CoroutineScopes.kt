package com.poyka.ripdpi.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationIoScope

data class AppCoroutineDispatchers(
    val default: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val main: CoroutineDispatcher,
)

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopesModule {
    @Provides
    @Singleton
    fun provideAppCoroutineDispatchers(): AppCoroutineDispatchers =
        AppCoroutineDispatchers(
            default = Dispatchers.Default,
            io = Dispatchers.IO,
            main = Dispatchers.Main,
        )

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(dispatchers: AppCoroutineDispatchers): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default)

    @Provides
    @Singleton
    @ApplicationIoScope
    fun provideApplicationIoScope(dispatchers: AppCoroutineDispatchers): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.io)
}
