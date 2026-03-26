package com.poyka.ripdpi

import android.util.Log
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
    private val diagnosticsBootstrapperProvider: Provider<DiagnosticsBootstrapper>,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    private companion object {
        private const val Tag = "AppStartup"
    }

    fun initialize() {
        applicationScope.launch {
            runCatching {
                diagnosticsBootstrapperProvider.get().initialize()
            }.onFailure { error ->
                Log.w(Tag, "Diagnostics bootstrap skipped", error)
            }
        }
    }
}
