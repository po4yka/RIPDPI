package com.poyka.ripdpi

import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppStartupInitializer
@Inject
constructor(
    private val diagnosticsBootstrapperProvider: Provider<DiagnosticsBootstrapper>,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    fun initialize() {
        applicationScope.launch {
            runCatching {
                diagnosticsBootstrapperProvider.get().initialize()
            }.onFailure { error ->
                logcat(LogPriority.WARN) { "Diagnostics bootstrap skipped\n${error.asLog()}" }
            }
        }
    }
}
