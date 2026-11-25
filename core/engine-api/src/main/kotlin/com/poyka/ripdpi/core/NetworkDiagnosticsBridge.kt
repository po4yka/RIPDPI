package com.poyka.ripdpi.core

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
