package com.poyka.ripdpi.core

/**
 * Stable runtime contract for the network-diagnostics native session,
 * implemented by `NetworkDiagnostics` in `:core:engine`. New `:core:diagnostics`
 * features depend on this interface rather than on the JNI implementation
 * module.
 */
interface NetworkDiagnosticsBridge {
    suspend fun startScan(
        requestJson: String,
        sessionId: String,
    )

    suspend fun cancelScan()

    suspend fun pollProgressJson(): String?

    suspend fun takeReportJson(): String?

    suspend fun pollPassiveEventsJson(): String?

    suspend fun destroy()
}
