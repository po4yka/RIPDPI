package com.poyka.ripdpi.diagnostics

import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class HomeCompositeStageCpuTracker internal constructor(
    private val readProcessCpuMs: () -> Long?,
) {
    @Inject
    constructor() : this({ runCatching(Process::getElapsedCpuTime).getOrNull() })

    private val startedAtBySessionId = ConcurrentHashMap<String, Long>()

    fun start(sessionId: String) {
        readProcessCpuMs()?.let { startedAtBySessionId[sessionId] = it }
    }

    fun finish(sessionId: String): Long? =
        startedAtBySessionId.remove(sessionId)?.let { startedAt ->
            readProcessCpuMs()?.let { finishedAt -> (finishedAt - startedAt).coerceAtLeast(0L) }
        }
}
