package com.poyka.ripdpi.diagnostics.replay

/**
 * Input to [ProbeReplayService.run]. Identifies the target + strategy +
 * cap. No locale-dependent fields; the strategy id resolves to a
 * StrategyDescriptor in the strategy registry.
 */
data class ReplayProbeRequest(
    val domain: String,
    val strategyId: String,
    val timeoutMs: Long,
)
