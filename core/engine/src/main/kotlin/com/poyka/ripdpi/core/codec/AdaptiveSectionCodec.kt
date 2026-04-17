package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.core.RipDpiAdaptiveFallbackConfig
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
        )
}
