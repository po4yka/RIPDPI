package com.poyka.ripdpi.diagnostics

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

data class LogcatSnapshot(
    val content: String,
    val captureScope: String,
    val byteCount: Int,
)

open class LogcatSnapshotCollector
    @Inject
    constructor() {
        companion object {
            const val AppVisibleSnapshotScope = "app_visible_snapshot"
        }

        open suspend fun capture(): LogcatSnapshot? =
            withContext(Dispatchers.IO) {
                try {
                    val output =
                        Runtime
                            .getRuntime()
                            .exec(arrayOf("logcat", "*:D", "-d"))
                            .inputStream
                            .bufferedReader()
                            .use { it.readText() }

                    if (output.isBlank()) {
                        null
                    } else {
                        LogcatSnapshot(
                            content = output,
                            captureScope = AppVisibleSnapshotScope,
                            byteCount = output.toByteArray(Charsets.UTF_8).size,
                        )
                    }
                } catch (error: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to collect logs\n${error.asLog()}" }
                    null
                }
            }
    }
