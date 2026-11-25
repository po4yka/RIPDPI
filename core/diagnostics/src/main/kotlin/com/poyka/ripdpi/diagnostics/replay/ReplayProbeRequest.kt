package com.poyka.ripdpi.diagnostics.replay

data class ReplayProbeRequest(
    val domain: String,
    val strategyId: String,
    val timeoutMs: Long,
    val sessionId: String? = null,
)
