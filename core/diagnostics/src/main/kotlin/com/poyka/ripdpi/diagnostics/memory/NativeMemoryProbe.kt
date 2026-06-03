package com.poyka.ripdpi.diagnostics.memory

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A point-in-time read of the process's memory footprint.
 *
 * RIPDPI is single-process, so the Rust native core shares this process: the
 * native heap counts the Rust allocator's footprint and [processRssBytes]
 * covers the whole process (Java + native). This is the footprint Android 17's
 * per-app memory limiter measures.
 */
data class NativeMemorySample(
    /** Native (malloc/Rust) heap currently allocated, in bytes. */
    val nativeHeapBytes: Long,
    /** Resident set size of the whole process, in bytes. */
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
            // Fallback only; the real page size (4 KiB or 16 KiB on newer
            // devices) is read at runtime via Os.sysconf.
            private const val DefaultPageSizeBytes: Long = 4096

            /**
             * Parses the resident-pages field (the 2nd token) from the contents
             * of `/proc/self/statm`: `size resident shared text lib data dt`.
             * Returns 0 when the line is malformed.
             */
            fun parseResidentPages(statm: String): Long =
                statm
                    .trim()
                    .split(WHITESPACE)
                    .getOrNull(1)
                    ?.toLongOrNull() ?: 0L

            private val WHITESPACE = Regex("\\s+")
        }
    }
