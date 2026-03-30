package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import java.util.Locale

class BreadcrumbLogWriter(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : LogWriter() {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
    }

    data class BreadcrumbEntry(
        val timestamp: Long,
        val severity: String,
        val tag: String,
        val message: String,
    )

    private val buffer = ArrayDeque<BreadcrumbEntry>(maxEntries)
    private val lock = Any()

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val entry =
            BreadcrumbEntry(
                timestamp = System.currentTimeMillis(),
                severity = severity.name.uppercase(Locale.US),
                tag = tag,
                message = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message,
            )
        synchronized(lock) {
            if (buffer.size >= maxEntries) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    fun snapshot(): List<BreadcrumbEntry> =
        synchronized(lock) {
            buffer.toList()
        }
}
