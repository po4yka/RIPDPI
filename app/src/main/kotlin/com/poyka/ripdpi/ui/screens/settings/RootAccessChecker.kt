package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.services.RootAccessResult
import com.poyka.ripdpi.services.RootDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Runs the superuser check; it can show the root manager's prompt, so call it only on an explicit user action. */
fun interface RootAccessChecker {
    suspend fun check(): RootAccessResult
}

@Module
@InstallIn(SingletonComponent::class)
object RootAccessCheckerModule {
    @Provides
    fun provideRootAccessChecker(detector: RootDetector): RootAccessChecker =
        RootAccessChecker(detector::testRootAccess)
}
