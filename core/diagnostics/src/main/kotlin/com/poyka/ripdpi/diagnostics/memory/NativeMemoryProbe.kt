package com.poyka.ripdpi.diagnostics.memory

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class NativeMemorySample(
    val nativeHeapBytes: Long,
    val processRssBytes: Long,
)

fun interface NativeMemoryProbe {
    fun sample(): NativeMemorySample
}

@Singleton
class DefaultNativeMemoryProbe
    @Inject
    constructor() : NativeMemoryProbe {
        override fun sample(): NativeMemorySample =
            NativeMemorySample(
                nativeHeapBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L),
                processRssBytes = readProcessRssBytes(),
            )

        private fun readProcessRssBytes(): Long =
            runCatching {
                val residentPages = parseResidentPages(File("/proc/self/statm").readText())
                residentPages * pageSizeBytes()
            }.getOrDefault(0L)

        private fun pageSizeBytes(): Long =
            runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }
                .getOrDefault(DefaultPageSizeBytes)
                .takeIf { it > 0 } ?: DefaultPageSizeBytes

        companion object {
            private const val DefaultPageSizeBytes: Long = 4096

            fun parseResidentPages(statm: String): Long =
                statm
                    .trim()
                    .split(WHITESPACE)
                    .getOrNull(1)
                    ?.toLongOrNull() ?: 0L

            private val WHITESPACE = Regex("\\s+")
        }
    }
