package com.poyka.ripdpi

import android.util.Log
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.RuntimeHistoryRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppStartupInitializer
    @Inject
    constructor(
        private val diagnosticsBootstrapperProvider: Provider<DiagnosticsBootstrapper>,
        private val runtimeHistoryRecorderProvider: Provider<RuntimeHistoryRecorder>,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        private companion object {
            private const val Tag = "AppStartup"
        }

        fun initialize() {
            runCatching {
                runtimeHistoryRecorderProvider.get().start()
            }.onFailure { error ->
                Log.w(Tag, "Runtime history bootstrap skipped", error)
            }
            applicationScope.launch {
                runCatching {
                    diagnosticsBootstrapperProvider.get().initialize()
                }.onFailure { error ->
                    Log.w(Tag, "Diagnostics bootstrap skipped", error)
                }
            }
        }
    }
