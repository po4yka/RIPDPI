package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.core.RipDpiAdaptiveFallbackConfig
import com.poyka.ripdpi.data.DefaultEvolutionCooldownAfterFailures
import com.poyka.ripdpi.data.DefaultEvolutionCooldownMs
import com.poyka.ripdpi.data.DefaultEvolutionDecayHalfLifeMs
import com.poyka.ripdpi.data.DefaultEvolutionExperimentTtlMs
import kotlinx.serialization.Serializable

@Serializable
internal data class NativeAdaptiveFallbackConfig(
    val enabled: Boolean = true,
    val torst: Boolean = true,
    val tlsErr: Boolean = true,
    val httpRedirect: Boolean = true,
    val connectFailure: Boolean = true,
    val autoSort: Boolean = true,
    val cacheTtlSeconds: Int = 90,
    val cachePrefixV4: Int = 24,
    val strategyEvolution: Boolean = false,
    val evolutionEpsilon: Double = 0.1,
    val evolutionExperimentTtlMs: Long = DefaultEvolutionExperimentTtlMs,
    val evolutionDecayHalfLifeMs: Long = DefaultEvolutionDecayHalfLifeMs,
    val evolutionCooldownAfterFailures: Int = DefaultEvolutionCooldownAfterFailures,
    val evolutionCooldownMs: Long = DefaultEvolutionCooldownMs,
)

internal object AdaptiveSectionCodec {
    fun toModel(value: NativeAdaptiveFallbackConfig): RipDpiAdaptiveFallbackConfig =
        RipDpiAdaptiveFallbackConfig(
            enabled = value.enabled,
            torst = value.torst,
            tlsErr = value.tlsErr,
            httpRedirect = value.httpRedirect,
            connectFailure = value.connectFailure,
            autoSort = value.autoSort,
            cacheTtlSeconds = value.cacheTtlSeconds,
            cachePrefixV4 = value.cachePrefixV4,
            strategyEvolution = value.strategyEvolution,
            evolutionEpsilon = value.evolutionEpsilon,
            evolutionExperimentTtlMs = value.evolutionExperimentTtlMs,
            evolutionDecayHalfLifeMs = value.evolutionDecayHalfLifeMs,
            evolutionCooldownAfterFailures = value.evolutionCooldownAfterFailures,
            evolutionCooldownMs = value.evolutionCooldownMs,
        )

    fun toNative(value: RipDpiAdaptiveFallbackConfig): NativeAdaptiveFallbackConfig =
        NativeAdaptiveFallbackConfig(
            enabled = value.enabled,
            torst = value.torst,
            tlsErr = value.tlsErr,
            httpRedirect = value.httpRedirect,
            connectFailure = value.connectFailure,
            autoSort = value.autoSort,
            cacheTtlSeconds = value.cacheTtlSeconds,
            cachePrefixV4 = value.cachePrefixV4,
            strategyEvolution = value.strategyEvolution,
            evolutionEpsilon = value.evolutionEpsilon,
            evolutionExperimentTtlMs = value.evolutionExperimentTtlMs,
            evolutionDecayHalfLifeMs = value.evolutionDecayHalfLifeMs,
            evolutionCooldownAfterFailures = value.evolutionCooldownAfterFailures,
            evolutionCooldownMs = value.evolutionCooldownMs,
        )
}
