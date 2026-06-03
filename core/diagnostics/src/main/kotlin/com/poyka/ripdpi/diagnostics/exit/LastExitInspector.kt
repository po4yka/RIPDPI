package com.poyka.ripdpi.diagnostics.exit

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether a prior process death was caused by Android 17's per-app
 * memory limiter and records it as a diagnostics event so the user (and support)
 * can tell a memory-cap kill apart from a crash or an ordinary background kill.
 *
 * Android 17 caps a single bloated app rather than letting the Low Memory Killer
 * evict well-behaved cached apps. When it caps RIPDPI's process the kill is
 * reported through [ApplicationExitInfo] as [ApplicationExitInfo.REASON_OTHER]
 * with a description containing [DefaultLastExitInspector.DescriptionMarker].
 */
interface LastExitInspector {
    suspend fun recordRecentMemoryLimiterExits()
}

@Singleton
class DefaultLastExitInspector
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) : LastExitInspector {
        override suspend fun recordRecentMemoryLimiterExits() {
            // getHistoricalProcessExitReasons / ApplicationExitInfo are API 30+.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
            val exits =
                runCatching {
                    activityManager.getHistoricalProcessExitReasons(
                        context.packageName,
                        // pid =
                        0,
                        // maxNum =
                        MaxExitsInspected,
                    )
                }.getOrElse { error ->
                    Logger.w(error) { "Unable to read historical process exit reasons" }
                    return
                }
            exits.forEach { info ->
                if (isMemoryLimiterExit(info.reason, info.description)) {
                    // Deterministic id => the REPLACE insert makes re-scanning the
                    // same exit on every later launch idempotent (no duplicate rows).
                    artifactWriteStore.insertNativeSessionEvent(
                        memoryLimiterEvent(
                            timestampMs = info.timestamp,
                            pid = info.pid,
                            description = info.description,
                        ),
                    )
                }
            }
        }

        companion object {
            /**
             * Substring Android 17 places in [ApplicationExitInfo.getDescription]
             * when its per-app memory limiter caps the process.
             */
            const val DescriptionMarker: String = "MemoryLimiter:AnonSwap"

            /** Diagnostics event source tag for memory-limiter kills. */
            const val Source: String = "memory_limiter"

            private const val Level: String = "warn"
            private const val Subsystem: String = "memory"
            private const val MaxExitsInspected: Int = 16

            fun isMemoryLimiterExit(
                reason: Int,
                description: String?,
            ): Boolean =
                reason == ApplicationExitInfo.REASON_OTHER &&
                    description?.contains(DescriptionMarker) == true

            fun memoryLimiterEventId(
                timestampMs: Long,
                pid: Int,
            ): String = "memory_limiter:$timestampMs:$pid"

            fun memoryLimiterEvent(
                timestampMs: Long,
                pid: Int,
                description: String?,
            ): NativeSessionEventEntity =
                NativeSessionEventEntity(
                    id = memoryLimiterEventId(timestampMs, pid),
                    sessionId = null,
                    source = Source,
                    level = Level,
                    message = description ?: "Process capped by the Android memory limiter",
                    createdAt = timestampMs,
                    subsystem = Subsystem,
                )
        }
    }
