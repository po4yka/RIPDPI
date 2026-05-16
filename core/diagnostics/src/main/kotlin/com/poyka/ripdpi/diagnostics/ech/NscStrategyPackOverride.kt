package com.poyka.ripdpi.diagnostics.ech

/**
 * Control-plane override that allows a strategy pack to set per-domain
 * [EchMode] values, superseding classifier defaults.
 */
data class NscStrategyPackOverride(
    val domainModes: Map<String, EchMode>,
)
