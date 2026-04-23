package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

internal const val RelayKindGoogleAppsScript = "google_apps_script"

@Singleton
internal class GoogleAppsScriptRelayRuntime
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
    ) : RipDpiRelayRuntime {
        @Volatile private var relayRuntime: RipDpiRelayRuntime? = null

        @Volatile private var relayStartSignal = CompletableDeferred<RipDpiRelayRuntime>()

        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
            require(config.kind == RelayKindGoogleAppsScript) {
                "Google Apps Script relay runtime only supports $RelayKindGoogleAppsScript profiles"
            }
            val runtime = relayFactory.create()
            val startSignal = CompletableDeferred<RipDpiRelayRuntime>()
            relayRuntime = runtime
            relayStartSignal = startSignal
            startSignal.complete(runtime)
            return try {
                runtime.start(config)
            } finally {
                if (relayRuntime === runtime) {
                    relayRuntime = null
                }
                if (relayStartSignal === startSignal) {
                    relayStartSignal = CompletableDeferred()
                }
            }
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            relayStartSignal.await().awaitReady(timeoutMillis)
        }

        override suspend fun stop() {
            relayRuntime?.stop()
        }

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot =
            relayRuntime?.pollTelemetry()
                ?: error("Google Apps Script relay runtime is not active")
    }

internal interface GoogleAppsScriptRelayRuntimeFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
internal class DefaultGoogleAppsScriptRelayRuntimeFactory
    @Inject
    constructor(
        private val runtime: GoogleAppsScriptRelayRuntime,
    ) : GoogleAppsScriptRelayRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtime
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GoogleAppsScriptRelayRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindGoogleAppsScriptRelayRuntimeFactory(
        factory: DefaultGoogleAppsScriptRelayRuntimeFactory,
    ): GoogleAppsScriptRelayRuntimeFactory
}
