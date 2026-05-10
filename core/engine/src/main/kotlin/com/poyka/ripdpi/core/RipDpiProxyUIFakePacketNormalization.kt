package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultEntropyPaddingMax
import com.poyka.ripdpi.data.DefaultEntropyPaddingTargetPermil
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultShannonEntropyTargetPermil
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.normalizeEntropyMode
import com.poyka.ripdpi.data.normalizeFakeTlsSniMode
import com.poyka.ripdpi.data.normalizeFakeTlsSource
import com.poyka.ripdpi.data.normalizeHttpFakeProfile
import com.poyka.ripdpi.data.normalizeIpIdMode
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizeTlsFakeProfile
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.normalizeUdpFakeProfile

private const val WsizeScaleMin = -1
private const val WsizeScaleMax = 14

internal fun normalizeFakePacketConfig(config: RipDpiFakePacketConfig): RipDpiFakePacketConfig {
    val normalizedAdaptiveFakeTtlMin = normalizeAdaptiveFakeTtlMin(config.adaptiveFakeTtlMin)
    return config.copy(
        adaptiveFakeTtlDelta = normalizeAdaptiveFakeTtlDelta(config.adaptiveFakeTtlDelta),
        adaptiveFakeTtlMin = normalizedAdaptiveFakeTtlMin,
        adaptiveFakeTtlMax = normalizeAdaptiveFakeTtlMax(config.adaptiveFakeTtlMax, normalizedAdaptiveFakeTtlMin),
        adaptiveFakeTtlFallback =
            normalizeAdaptiveFakeTtlFallback(
                config.adaptiveFakeTtlFallback,
                config.fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
            ),
        fakeSni = config.fakeSni.ifBlank { DefaultFakeSni },
        httpFakeProfile = normalizeHttpFakeProfile(config.httpFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        fakeTlsSource = normalizeFakeTlsSource(config.fakeTlsSource),
        fakeTlsSecondaryProfile =
            config.fakeTlsSecondaryProfile
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let(::normalizeTlsFakeProfile)
                .orEmpty(),
        fakeTlsSniMode = normalizeFakeTlsSniMode(config.fakeTlsSniMode),
        tlsFakeProfile = normalizeTlsFakeProfile(config.tlsFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        udpFakeProfile = normalizeUdpFakeProfile(config.udpFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        fakeOffsetMarker = normalizeOffsetExpression(config.fakeOffsetMarker, DefaultFakeOffsetMarker),
        windowClamp = config.windowClamp?.takeIf { it > 0 },
        wsizeWindow = config.wsizeWindow?.takeIf { it > 0 },
        wsizeScale = config.wsizeScale?.takeIf { it in WsizeScaleMin..WsizeScaleMax },
        stripTimestamps = config.stripTimestamps,
        ipIdMode = normalizeIpIdMode(config.ipIdMode),
        quicBindLowPort = config.quicBindLowPort,
        quicMigrateAfterHandshake = config.quicMigrateAfterHandshake,
        entropyMode = normalizeEntropyMode(config.entropyMode),
        entropyPaddingTargetPermil =
            config.entropyPaddingTargetPermil.takeIf { it > 0 } ?: DefaultEntropyPaddingTargetPermil,
        entropyPaddingMax = config.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
        shannonEntropyTargetPermil =
            config.shannonEntropyTargetPermil.takeIf { it > 0 } ?: DefaultShannonEntropyTargetPermil,
        tlsFingerprintProfile =
            normalizeTlsFingerprintProfile(
                config.tlsFingerprintProfile.ifBlank {
                    TlsFingerprintProfileChromeStable
                },
            ),
    )
}
