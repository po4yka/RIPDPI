package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.DefaultAdaptiveFallbackCachePrefixV4
import com.poyka.ripdpi.data.DefaultAdaptiveFallbackCacheTtlSeconds
import com.poyka.ripdpi.data.DefaultEntropyPaddingMax
import com.poyka.ripdpi.data.DefaultEntropyPaddingTargetPermil
import com.poyka.ripdpi.data.DefaultEvolutionCooldownAfterFailures
import com.poyka.ripdpi.data.DefaultEvolutionCooldownMs
import com.poyka.ripdpi.data.DefaultEvolutionDecayHalfLifeMs
import com.poyka.ripdpi.data.DefaultEvolutionEpsilon
import com.poyka.ripdpi.data.DefaultEvolutionExperimentTtlMs
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.DefaultShannonEntropyTargetPermil
import com.poyka.ripdpi.data.EntropyModeDisabled
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable

@Stable
data class QuicUiState(
    val quicInitialMode: String = QuicInitialModeRouteAndCache,
    val quicSupportV1: Boolean = true,
    val quicSupportV2: Boolean = true,
    val quicFakeProfile: String = QuicFakeProfileDisabled,
    val quicFakeHost: String = "",
) {
    val quicFakeProfileActive: Boolean
        get() = quicFakeProfile != QuicFakeProfileDisabled

    val showQuicFakeHostOverride: Boolean
        get() = quicFakeProfile == QuicFakeProfileRealisticInitial

    val quicFakeUsesCustomHost: Boolean
        get() = showQuicFakeHostOverride && quicFakeHost.isNotBlank()

    val quicFakeEffectiveHost: String
        get() =
            if (showQuicFakeHostOverride) {
                quicFakeHost.ifBlank { DefaultQuicFakeHost }
            } else {
                ""
            }
}

@Stable
data class DetectionResistanceUiState(
    val quicBindLowPort: Boolean = false,
    val quicMigrateAfterHandshake: Boolean = false,
    val strategyEvolution: Boolean = false,
    val evolutionEpsilon: Double = DefaultEvolutionEpsilon,
    val evolutionExperimentTtlMs: Long = DefaultEvolutionExperimentTtlMs,
    val evolutionDecayHalfLifeMs: Long = DefaultEvolutionDecayHalfLifeMs,
    val evolutionCooldownAfterFailures: Int = DefaultEvolutionCooldownAfterFailures,
    val evolutionCooldownMs: Long = DefaultEvolutionCooldownMs,
    val tlsFingerprintProfile: String = TlsFingerprintProfileChromeStable,
    val entropyMode: String = EntropyModeDisabled,
    val entropyPaddingTargetPermil: Int = DefaultEntropyPaddingTargetPermil,
    val entropyPaddingMax: Int = DefaultEntropyPaddingMax,
    val shannonEntropyTargetPermil: Int = DefaultShannonEntropyTargetPermil,
) {
    val entropyEnabled: Boolean
        get() = entropyMode != EntropyModeDisabled

    val tlsFingerprintProfileActive: Boolean
        get() = true
}

@Stable
data class HttpParserUiState(
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val hostRemoveSpaces: Boolean = false,
    val httpHostPad: Boolean = false,
    val httpMethodEol: Boolean = false,
    val httpUnixEol: Boolean = false,
    val httpMethodSpace: Boolean = false,
    val httpHostExtraSpace: Boolean = false,
    val httpHostTab: Boolean = false,
) {
    val httpParserSafeCount: Int
        get() = listOf(hostMixedCase, domainMixedCase, hostRemoveSpaces, httpHostPad, httpHostTab).count { it }

    val httpParserAggressiveCount: Int
        get() = listOf(httpMethodEol, httpUnixEol, httpMethodSpace, httpHostExtraSpace).count { it }

    val hasSafeHttpParserTweaks: Boolean
        get() = httpParserSafeCount > 0

    val hasAggressiveHttpParserEvasions: Boolean
        get() = httpParserAggressiveCount > 0

    val hasCustomHttpParserEvasions: Boolean
        get() = hasSafeHttpParserTweaks || hasAggressiveHttpParserEvasions
}

@Stable
data class AdaptiveFallbackUiState(
    val enabled: Boolean = true,
    val torst: Boolean = true,
    val tlsErr: Boolean = true,
    val httpRedirect: Boolean = true,
    val connectFailure: Boolean = true,
    val autoSort: Boolean = true,
    val cacheTtlSeconds: Int = DefaultAdaptiveFallbackCacheTtlSeconds,
    val cachePrefixV4: Int = DefaultAdaptiveFallbackCachePrefixV4,
    val runtimeOverrideActive: Boolean = false,
    val runtimeOverrideRememberedPolicy: Boolean = false,
    val runtimeRouteGroup: Int? = null,
    val runtimeTriggerMask: Long? = null,
    val runtimeLastTrigger: String? = null,
    val runtimeOverrideReason: String? = null,
) {
    val triggerCount: Int
        get() = listOf(torst, tlsErr, httpRedirect, connectFailure).count { it }

    val hasAnyTrigger: Boolean
        get() = triggerCount > 0

    val usesDefaultProfile: Boolean
        get() =
            enabled &&
                torst &&
                tlsErr &&
                httpRedirect &&
                connectFailure &&
                autoSort &&
                cacheTtlSeconds == DefaultAdaptiveFallbackCacheTtlSeconds &&
                cachePrefixV4 == DefaultAdaptiveFallbackCachePrefixV4

    val runtimeOverrideSummary: String?
        get() =
            if (!runtimeOverrideActive) {
                null
            } else {
                buildString {
                    append("Route ")
                    append(runtimeRouteGroup?.toString() ?: "unknown")
                    runtimeTriggerMask?.let {
                        append(" via mask ")
                        append(it)
                    }
                    runtimeLastTrigger?.takeIf(String::isNotBlank)?.let {
                        append(" after ")
                        append(it)
                    }
                }
            }
}
