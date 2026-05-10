package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultEvolutionEpsilon
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCachePrefixV4
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCacheTtlSeconds

internal fun normalizeAdaptiveFallbackConfig(config: RipDpiAdaptiveFallbackConfig): RipDpiAdaptiveFallbackConfig =
    config.copy(
        cacheTtlSeconds = normalizeAdaptiveFallbackCacheTtlSeconds(config.cacheTtlSeconds),
        cachePrefixV4 = normalizeAdaptiveFallbackCachePrefixV4(config.cachePrefixV4),
        strategyEvolution = config.strategyEvolution,
        evolutionEpsilon =
            config.evolutionEpsilon
                .takeIf { !it.isNaN() }
                ?.coerceIn(0.0, 1.0)
                ?: DefaultEvolutionEpsilon,
        evolutionExperimentTtlMs = config.evolutionExperimentTtlMs.coerceAtLeast(1),
        evolutionDecayHalfLifeMs = config.evolutionDecayHalfLifeMs.coerceAtLeast(1),
        evolutionCooldownAfterFailures = config.evolutionCooldownAfterFailures.coerceAtLeast(1),
        evolutionCooldownMs = config.evolutionCooldownMs.coerceAtLeast(1),
    )
