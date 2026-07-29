package com.poyka.ripdpi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.CustomTestApplication
import dagger.hilt.components.SingletonComponent

/**
 * Test-process application contract for components such as SystemJobService
 * that can be recreated independently of the instrumentation runner.
 */
open class RipDpiTestApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(
                    EntryPointAccessors
                        .fromApplication(this, TestWorkerFactoryEntryPoint::class.java)
                        .workerFactory(),
                ).build()
}

@CustomTestApplication(RipDpiTestApplication::class)
interface RipDpiCustomTestApplication

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TestWorkerFactoryEntryPoint {
    fun workerFactory(): HiltWorkerFactory
}
