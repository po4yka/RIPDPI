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

/** Records privacy-safe categories for recent Android process exits. */
interface LastExitInspector {
    suspend fun recordRecentProcessExits()
}

internal data class ProcessExitHistoryItem(
    val timestampMs: Long,
    val identityDiscriminator: String,
    val reason: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val memoryLimiterMarkerPresent: Boolean,
    // Untrusted fields exist at the source boundary so tests can prove the
    // persistence projector discards them. The Android source never copies
    // trace content and only retains the description-derived boolean above.
    val rawPid: Int? = null,
    val rawProcessName: String? = null,
    val rawStatus: Int? = null,
    val rawDescription: String? = null,
    val rawTraceMarker: String? = null,
)

internal interface ProcessExitHistorySource {
    val sdkInt: Int

    fun recentProcessExits(limit: Int): List<ProcessExitHistoryItem>
}

internal fun processExitIdentityDiscriminators(timestampPidPairs: List<Pair<Long, Int>>): List<String> {
    val result = MutableList(timestampPidPairs.size) { "" }
    timestampPidPairs.indices
        .groupBy { index -> timestampPidPairs[index].first }
        .values
        .forEach { sameTimestampIndices ->
            sameTimestampIndices
                .sortedWith(compareBy({ index -> timestampPidPairs[index].second }, { index -> index }))
                .forEachIndexed { ordinal, originalIndex ->
                    result[originalIndex] = "ordinal-$ordinal"
                }
        }
    return result
}

@Singleton
internal class AndroidProcessExitHistorySource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ProcessExitHistorySource {
        override val sdkInt: Int
            get() = Build.VERSION.SDK_INT

        override fun recentProcessExits(limit: Int): List<ProcessExitHistoryItem> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
            val activityManager = context.getSystemService(ActivityManager::class.java)
            return activityManager
                ?.let { manager ->
                    runCatching {
                        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, limit)
                        val identityDiscriminators =
                            processExitIdentityDiscriminators(exits.map { info -> info.timestamp to info.pid })
                        exits.mapIndexed { index, info ->
                            ProcessExitHistoryItem(
                                timestampMs = info.timestamp,
                                identityDiscriminator = identityDiscriminators[index],
                                reason = info.reason,
                                importance = info.importance,
                                pssKb = info.pss,
                                rssKb = info.rss,
                                memoryLimiterMarkerPresent =
                                    sdkInt >= DefaultLastExitInspector.AndroidMemoryLimiterApi &&
                                        info.reason == ApplicationExitInfo.REASON_OTHER &&
                                        info.description?.contains(DefaultLastExitInspector.DescriptionMarker) == true,
                            )
                        }
                    }.getOrElse { error ->
                        Logger.w(error) { "Unable to read historical process exit reasons" }
                        emptyList()
                    }
                }.orEmpty()
        }
    }

@Singleton
class DefaultLastExitInspector
    @Inject
    internal constructor(
        private val historySource: ProcessExitHistorySource,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) : LastExitInspector {
        override suspend fun recordRecentProcessExits() {
            if (historySource.sdkInt < Build.VERSION_CODES.R) return
            historySource.recentProcessExits(MaxExitsInspected).take(MaxExitsInspected).forEach { info ->
                artifactWriteStore.insertNativeSessionEvent(
                    processExitEvent(
                        timestampMs = info.timestampMs,
                        identityDiscriminator = info.identityDiscriminator,
                        reason = info.reason,
                        sdkInt = historySource.sdkInt,
                        importance = info.importance,
                        pssKb = info.pssKb,
                        rssKb = info.rssKb,
                        memoryLimiterMarkerPresent = info.memoryLimiterMarkerPresent,
                    ),
                )
            }
        }

        companion object {
            /** Android 17 marker checked locally; its description is never persisted. */
            const val DescriptionMarker: String = "MemoryLimiter:AnonSwap"

            const val Source: String = "application_exit"
            const val Subsystem: String = "process"
            const val AndroidMemoryLimiterSubtype: String = "android_memory_limiter"
            const val NoSubtype: String = "none"
            const val Unknown: String = "unknown"

            internal const val AndroidMemoryLimiterApi: Int = 37
            private const val FreezerReason: Int = 14
            private const val PackageStateChangeReason: Int = 15
            private const val PackageUpdatedReason: Int = 16
            private const val MaxExitsInspected: Int = 16
            private const val KibPerMib: Long = 1024
            private const val MediumMemoryThresholdKb: Long = 128 * KibPerMib
            private const val HighMemoryThresholdKb: Long = 512 * KibPerMib
            private val BaseReasonCategories =
                listOf(
                    Unknown,
                    "exit_self",
                    "signaled",
                    "low_memory",
                    "crash",
                    "crash_native",
                    "anr",
                    "initialization_failure",
                    "permission_change",
                    "excessive_resource_usage",
                    "user_requested",
                    "user_stopped",
                    "dependency_died",
                    "other",
                )

            fun reasonCategory(
                reason: Int,
                sdkInt: Int,
            ): String =
                when {
                    sdkInt < Build.VERSION_CODES.R -> {
                        Unknown
                    }

                    reason in BaseReasonCategories.indices -> {
                        BaseReasonCategories[reason]
                    }

                    reason == FreezerReason && sdkInt >= Build.VERSION_CODES.TIRAMISU -> {
                        "freezer"
                    }

                    reason == PackageStateChangeReason && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        "package_state_change"
                    }

                    reason == PackageUpdatedReason && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        "package_updated"
                    }

                    else -> {
                        Unknown
                    }
                }

            fun importanceBand(importance: Int): String =
                when {
                    importance <= 0 -> {
                        Unknown
                    }

                    importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> {
                        "foreground"
                    }

                    importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> {
                        "foreground_service"
                    }

                    importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> {
                        "visible"
                    }

                    importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> {
                        "perceptible"
                    }

                    importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> {
                        "service"
                    }

                    importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> {
                        "background"
                    }

                    importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> {
                        "cached"
                    }

                    else -> {
                        Unknown
                    }
                }

            fun memoryBand(sizeKb: Long): String =
                when {
                    sizeKb <= 0 -> Unknown
                    sizeKb < MediumMemoryThresholdKb -> "low"
                    sizeKb < HighMemoryThresholdKb -> "medium"
                    else -> "high"
                }

            fun processExitEvent(
                timestampMs: Long,
                identityDiscriminator: String,
                reason: Int,
                sdkInt: Int,
                importance: Int,
                pssKb: Long,
                rssKb: Long,
                memoryLimiterMarkerPresent: Boolean,
            ): NativeSessionEventEntity {
                val reasonCategory = reasonCategory(reason, sdkInt)
                val subtype =
                    if (
                        sdkInt >= AndroidMemoryLimiterApi &&
                        reason == ApplicationExitInfo.REASON_OTHER &&
                        memoryLimiterMarkerPresent
                    ) {
                        AndroidMemoryLimiterSubtype
                    } else {
                        NoSubtype
                    }
                val importanceBand = importanceBand(importance)
                val pssBand = memoryBand(pssKb)
                val rssBand = memoryBand(rssKb)
                val categoricalPayload =
                    "reason=$reasonCategory;subtype=$subtype;importance=$importanceBand;pss=$pssBand;rss=$rssBand"
                return NativeSessionEventEntity(
                    id = "$Source:$timestampMs:$identityDiscriminator",
                    sessionId = null,
                    source = Source,
                    level = severity(reasonCategory, subtype),
                    message = "process_exit $categoricalPayload",
                    createdAt = timestampMs,
                    subsystem = Subsystem,
                )
            }

            private fun severity(
                reasonCategory: String,
                subtype: String,
            ): String =
                when {
                    subtype == AndroidMemoryLimiterSubtype -> "warn"

                    reasonCategory in setOf("crash", "crash_native", "anr", "initialization_failure") -> "error"

                    reasonCategory in
                        setOf(
                            "signaled",
                            "low_memory",
                            "excessive_resource_usage",
                            "dependency_died",
                            "other",
                            "freezer",
                            Unknown,
                        ) -> "warn"

                    else -> "info"
                }
        }
    }
