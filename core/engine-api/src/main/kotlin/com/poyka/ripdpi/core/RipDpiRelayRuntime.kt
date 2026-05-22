package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot

/**
 * Stable runtime contract for the relay-transport native session, implemented
 * by `RipDpiRelay` in `:core:engine`. New `:core:service` / relay features
 * depend on this interface rather than on the JNI implementation module.
 */
interface RipDpiRelayRuntime {
    suspend fun start(config: ResolvedRipDpiRelayConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultRelayReadyTimeoutMs)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val defaultRelayReadyTimeoutMs = 5_000L
