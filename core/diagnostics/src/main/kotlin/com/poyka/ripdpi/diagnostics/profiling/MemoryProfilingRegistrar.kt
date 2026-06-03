package com.poyka.ripdpi.diagnostics.profiling

import android.content.Context
import android.os.Build
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import androidx.annotation.RequiresApi
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers Android 17's event-driven profiling triggers so a Java heap dump is
 * captured automatically when the app hits an OutOfMemoryError ([OOM][ProfilingTrigger.TRIGGER_TYPE_OOM])
 * or a severe memory/binder anomaly ([ANOMALY][ProfilingTrigger.TRIGGER_TYPE_ANOMALY]).
 * The OOM profile is delivered on the next launch; the anomaly profile just
 * before the system terminates the app.
 *
 * Artifacts are written to the app's own storage by the platform (no backend,
 * per project policy); we record a diagnostics event pointing at the captured
 * file so it can be surfaced and exported through the existing pipeline.
 */
interface MemoryProfilingRegistrar {
    fun register()
}

@Singleton
class DefaultMemoryProfilingRegistrar
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) : MemoryProfilingRegistrar {
        override fun register() {
            // ProfilingTrigger.TRIGGER_TYPE_OOM / TRIGGER_TYPE_ANOMALY are API 37.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
            val profilingManager = context.getSystemService(ProfilingManager::class.java) ?: return
            runCatching {
                val executor =
                    Executors.newSingleThreadExecutor { runnable ->
                        Thread(runnable, "ripdpi-profiling")
                    }
                profilingManager.registerForAllProfilingResults(executor, ::onProfilingResult)
                profilingManager.addProfilingTriggers(
                    listOf(
                        ProfilingTrigger
                            .Builder(ProfilingTrigger.TRIGGER_TYPE_OOM)
                            .setRateLimitingPeriodHours(RateLimitHours)
                            .build(),
                        ProfilingTrigger
                            .Builder(ProfilingTrigger.TRIGGER_TYPE_ANOMALY)
                            .setRateLimitingPeriodHours(RateLimitHours)
                            .build(),
                    ),
                )
            }.onFailure { error ->
                Logger.w(error) { "Failed to register memory profiling triggers" }
            }
        }

        @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
        private fun onProfilingResult(result: ProfilingResult) {
            val event =
                profilingEvent(
                    timestampMs = System.currentTimeMillis(),
                    triggerType = result.triggerType,
                    success = result.errorCode == ProfilingResult.ERROR_NONE,
                    filePath = result.resultFilePath,
                    errorMessage = result.errorMessage,
                )
            applicationScope.launch {
                runCatching { artifactWriteStore.insertNativeSessionEvent(event) }
                    .onFailure { error -> Logger.w(error) { "Failed to persist profiling result event" } }
            }
        }

        companion object {
            /** Diagnostics event source tag for captured memory profiles. */
            const val Source: String = "memory_profiling"

            private const val RateLimitHours: Int = 1

            fun profilingEvent(
                timestampMs: Long,
                triggerType: Int,
                success: Boolean,
                filePath: String?,
                errorMessage: String?,
            ): NativeSessionEventEntity =
                NativeSessionEventEntity(
                    id = "memory_profiling:$timestampMs:$triggerType",
                    sessionId = null,
                    source = Source,
                    level = if (success) "info" else "warn",
                    message =
                        if (success) {
                            "Captured memory profile (trigger=$triggerType) at ${filePath ?: "unknown"}"
                        } else {
                            "Memory profiling failed (trigger=$triggerType): ${errorMessage ?: "unknown error"}"
                        },
                    createdAt = timestampMs,
                    subsystem = "memory",
                )
        }
    }
