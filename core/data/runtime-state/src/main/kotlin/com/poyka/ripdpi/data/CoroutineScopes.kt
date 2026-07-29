package com.poyka.ripdpi.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
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

/**
 * Synchronously closes the process-wide coroutine scopes during test application teardown.
 *
 * Android does not call [android.app.Application.onTerminate] on production devices. Robolectric
 * does, so joining the root jobs here prevents eager singleton collectors from outliving resources
 * such as Room databases that the test environment closes between applications.
 */
@Singleton
class ApplicationCoroutineScopeTerminator
    @Inject
    constructor(
        @ApplicationScope applicationScope: CoroutineScope,
        @ApplicationIoScope applicationIoScope: CoroutineScope,
    ) {
        private val rootJobs =
            listOf(
                requireNotNull(applicationScope.coroutineContext[Job]),
                requireNotNull(applicationIoScope.coroutineContext[Job]),
            )

        @Synchronized
        fun terminate() {
            rootJobs.forEach { job -> job.cancel() }
            runBlocking {
                withTimeoutOrNull(ApplicationScopeTerminationTimeoutMillis) {
                    rootJobs.joinAll()
                }
            }
        }
    }

private const val ApplicationScopeTerminationTimeoutMillis = 250L

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
