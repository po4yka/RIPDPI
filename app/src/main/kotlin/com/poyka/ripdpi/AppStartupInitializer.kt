package com.poyka.ripdpi

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.detection.DetectionCheckScheduler
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppStartupInitializer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appCompatibilityReset: AppCompatibilityReset,
        private val diagnosticsBootstrapperProvider: Provider<DiagnosticsBootstrapper>,
        private val detectionCheckScheduler: DetectionCheckScheduler,
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
                detectionCheckScheduler.startObserving(context, this)
            }
        }
    }
