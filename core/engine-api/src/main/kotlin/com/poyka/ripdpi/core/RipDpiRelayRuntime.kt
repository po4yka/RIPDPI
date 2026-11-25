package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot

interface RipDpiRelayRuntime {
    suspend fun start(config: ResolvedRipDpiRelayConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultRelayReadyTimeoutMs)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val defaultRelayReadyTimeoutMs = 5_000L
