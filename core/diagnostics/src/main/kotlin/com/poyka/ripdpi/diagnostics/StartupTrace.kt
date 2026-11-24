package com.poyka.ripdpi.diagnostics

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

private val nextStartupTraceCookie = AtomicInteger()

suspend fun <T> traceStartupPhase(
    name: String,
    action: suspend () -> T,
): T {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !Trace.isEnabled()) {
        return action()
    }

    val section = "RIPDPI startup: $name"
    val cookie = nextStartupTraceCookie.incrementAndGet()
    Trace.beginAsyncSection(section, cookie)
    return try {
        action()
    } finally {
        Trace.endAsyncSection(section, cookie)
    }
}
