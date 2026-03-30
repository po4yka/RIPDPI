package com.poyka.ripdpi

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppStartupInitializer
    @Inject
    constructor(
        private val appCompatibilityReset: AppCompatibilityReset,
        private val diagnosticsBootstrapperProvider: Provider<DiagnosticsBootstrapper>,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        fun initialize() {
            applicationScope.launch {
                runCatching {
                    appCompatibilityReset.resetIfNeeded()
                    diagnosticsBootstrapperProvider.get().initialize()
                }.onFailure { error ->
                    Logger.w(error) { "Diagnostics bootstrap skipped" }
                }
            }
        }
    }
