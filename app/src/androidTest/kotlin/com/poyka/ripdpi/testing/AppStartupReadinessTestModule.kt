package com.poyka.ripdpi.testing

import com.poyka.ripdpi.AppStartupReadiness
import com.poyka.ripdpi.AppStartupReadinessModule
import com.poyka.ripdpi.ReadyAppStartupReadiness
import com.poyka.ripdpi.RipDpiApp
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * The test runner uses `HiltTestApplication`, not [RipDpiApp]. Its production startup initializer
 * is therefore never invoked, so UI tests must not wait on the production recovery gate.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppStartupReadinessModule::class],
)
object AppStartupReadinessTestModule {
    @Provides
    @Singleton
    fun provideAppStartupReadiness(): AppStartupReadiness = ReadyAppStartupReadiness
}
