package com.poyka.ripdpi.data

interface DiagnosticsRuntimeCoordinator {
    suspend fun runRawPathScan(block: suspend () -> Unit)

    suspend fun runAutomaticRawPathScan(block: suspend () -> Unit)
}
