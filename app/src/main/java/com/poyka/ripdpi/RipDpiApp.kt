package com.poyka.ripdpi

import android.app.Application
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority.VERBOSE
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.RuntimeHistoryRecorder

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RipDpiAppEntryPoint {
    fun diagnosticsManager(): DiagnosticsManager

    fun runtimeHistoryRecorder(): RuntimeHistoryRecorder
}

@HiltAndroidApp
class RipDpiApp : Application() {
    private companion object {
        private const val Tag = "RipDpiApp"
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AndroidLogcatLogger.installOnDebuggableApp(this, minPriority = VERBOSE)

        val entryPoint = EntryPointAccessors.fromApplication(this, RipDpiAppEntryPoint::class.java)
        runCatching {
            entryPoint.runtimeHistoryRecorder().start()
        }.onFailure { error ->
            Log.w(Tag, "Runtime history bootstrap skipped", error)
        }
        applicationScope.launch {
            runCatching {
                entryPoint.diagnosticsManager().initialize()
            }.onFailure { error ->
                Log.w(Tag, "Diagnostics bootstrap skipped", error)
            }
        }
    }
}
