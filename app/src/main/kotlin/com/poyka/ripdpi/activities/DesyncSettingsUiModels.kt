package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerEndHost
import com.poyka.ripdpi.data.AdaptiveMarkerHost
import com.poyka.ripdpi.data.AdaptiveMarkerSniExt
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultSeqOverlapSize
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.FakeOrderDefault
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeSeqModeDuplicate
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.IpIdModeDefault
import com.poyka.ripdpi.data.SeqOverlapFakeModeProfile
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.formatActivationFilterSummary
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.supportsAdaptiveMarker
import com.poyka.ripdpi.data.supportsFakeOrdering

internal const val AdaptiveSplitPresetManual = "manual"
internal const val AdaptiveSplitPresetCustom = "custom"
internal const val AdaptiveFakeTtlModeFixed = "fixed"
internal const val AdaptiveFakeTtlModeAdaptive = "adaptive"
internal const val AdaptiveFakeTtlModeCustom = "custom"

@Stable
data class TlsPreludeUiState(
    val tlsrecEnabled: Boolean = false,
    val tlsrecMarker: String = DefaultTlsRecordMarker,
    val tlsPreludeMode: String = "disabled",
    val tlsPreludeStepCount: Int = 0,
    val tlsRandRecFragmentCount: Int = DefaultTlsRandRecFragmentCount,
    val tlsRandRecMinFragmentSize: Int = DefaultTlsRandRecMinFragmentSize,
    val tlsRandRecMaxFragmentSize: Int = DefaultTlsRandRecMaxFragmentSize,
) {
    val tlsPreludeUsesRandomRecords: Boolean
        get() = tlsPreludeMode == TcpChainStepKind.TlsRandRec.wireName

    val hasStackedTlsPreludeSteps: Boolean
        get() = tlsPreludeStepCount > 1
}

@Stable
data class ProxyNetworkUiState(
    val proxyIp: String = "127.0.0.1",
    val proxyPort: Int = 1080,
    val maxConnections: Int = 512,
    val bufferSize: Int = 16_384,
    val noDomain: Boolean = false,
    val tcpFastOpen: Boolean = false,
    val mixedInboundEnabled: Boolean = false,
    val allowLan: Boolean = false,
    val lanAuthToken: String = "",
    val appendHttpProxy: Boolean = false,
)

@Stable
data class FakeTransportUiState(
    val fakeTtl: Int = 8,
    val adaptiveFakeTtlEnabled: Boolean = false,
    val adaptiveFakeTtlDelta: Int = DefaultAdaptiveFakeTtlDelta,
    val adaptiveFakeTtlMin: Int = DefaultAdaptiveFakeTtlMin,
    val adaptiveFakeTtlMax: Int = DefaultAdaptiveFakeTtlMax,
    val adaptiveFakeTtlFallback: Int = DefaultAdaptiveFakeTtlFallback,
    val fakeSni: String = DefaultFakeSni,
    val fakeOffsetMarker: String = DefaultFakeOffsetMarker,
    val httpFakeProfile: String = FakePayloadProfileCompatDefault,
    val fakeTlsUseOriginal: Boolean = false,
    val fakeTlsRandomize: Boolean = false,
    val fakeTlsDupSessionId: Boolean = false,
    val fakeTlsPadEncap: Boolean = false,
    val fakeTlsSize: Int = 0,
    val fakeTlsSniMode: String = FakeTlsSniModeFixed,
    val tlsFakeProfile: String = FakePayloadProfileCompatDefault,
    val udpFakeProfile: String = FakePayloadProfileCompatDefault,
    val oobData: String = "a",
    val dropSack: Boolean = false,
    val md5sig: Boolean = false,
    val ipIdMode: String = IpIdModeDefault,
) {
    val adaptiveFakeTtlMode: String
        get() =
            when {
                !adaptiveFakeTtlEnabled -> AdaptiveFakeTtlModeFixed
                adaptiveFakeTtlDelta == DefaultAdaptiveFakeTtlDelta -> AdaptiveFakeTtlModeAdaptive
                else -> AdaptiveFakeTtlModeCustom
            }

    val hasAdaptiveFakeTtl: Boolean
        get() = adaptiveFakeTtlEnabled

    val hasCustomAdaptiveFakeTtl: Boolean
        get() = adaptiveFakeTtlMode == AdaptiveFakeTtlModeCustom

    val hasCustomFakePayloadProfiles: Boolean
        get() =
            httpFakeProfile != FakePayloadProfileCompatDefault ||
                tlsFakeProfile != FakePayloadProfileCompatDefault ||
                udpFakeProfile != FakePayloadProfileCompatDefault

    val hasCustomFakeTlsProfile: Boolean
        get() =
            fakeTlsUseOriginal ||
                fakeTlsRandomize ||
                fakeTlsDupSessionId ||
                fakeTlsPadEncap ||
                fakeTlsSize != 0 ||
                fakeTlsSniMode != FakeTlsSniModeFixed ||
                (fakeTlsSniMode == FakeTlsSniModeFixed && fakeSni != DefaultFakeSni)
}

@Stable
data class DesyncCoreUiState(
    val desyncMethod: String = "split",
    val tcpChainSteps: List<TcpChainStepModel> = emptyList(),
    val udpChainSteps: List<UdpChainStepModel> = emptyList(),
    val groupActivationFilter: ActivationFilterModel = ActivationFilterModel(),
    val chainSummary: String = "tcp: none",
    val chainDsl: String = "",
    val splitMarker: String = CanonicalDefaultSplitMarker,
    val udpFakeCount: Int = 0,
    val defaultTtl: Int = 0,
    val customTtl: Boolean = false,
    val hostFakeSteps: List<TcpChainStepModel> = tcpChainSteps.filter { it.kind == TcpChainStepKind.HostFake },
    val fakeOrderingSteps: List<TcpChainStepModel> = tcpChainSteps.filter { it.kind.supportsFakeOrdering },
    val fakeApproximationSteps: List<TcpChainStepModel> =
        tcpChainSteps.filter {
            it.kind == TcpChainStepKind.FakeSplit || it.kind == TcpChainStepKind.FakeDisorder
        },
    val seqOverlapSteps: List<TcpChainStepModel> = tcpChainSteps.filter { it.kind == TcpChainStepKind.SeqOverlap },
    val hasUdpFakeBurst: Boolean = udpChainSteps.any { it.count.coerceAtLeast(0) > 0 },
) {
    val primaryTcpFlagStep: TcpChainStepModel?
        get() = primaryTcpChainStep(tcpChainSteps)

    val tcpFlagOverrideStepCount: Int
        get() =
            tcpChainSteps.count {
                it.tcpFlagsSet.isNotBlank() ||
                    it.tcpFlagsUnset.isNotBlank() ||
                    it.tcpFlagsOrigSet.isNotBlank() ||
                    it.tcpFlagsOrigUnset.isNotBlank()
            }

    val hasTcpFlagOverrides: Boolean
        get() = tcpFlagOverrideStepCount > 0

    val tcpFlagVisualEditorSupported: Boolean
        get() {
            val primaryStep = primaryTcpFlagStep ?: return false
            return tcpChainSteps.count { !it.kind.isTlsPrelude } == 1 &&
                primaryStep.kind != TcpChainStepKind.Oob &&
                primaryStep.kind != TcpChainStepKind.Disoob
        }

    val hostFakeStepCount: Int
        get() = hostFakeSteps.size

    val primaryHostFakeStep: TcpChainStepModel?
        get() = hostFakeSteps.firstOrNull()

    val fakeOrderingStepCount: Int
        get() = fakeOrderingSteps.size

    val hasFakeOrderingOverrides: Boolean
        get() =
            fakeOrderingSteps.any {
                it.fakeOrder != FakeOrderDefault || it.fakeSeqMode != FakeSeqModeDuplicate
            }

    val primaryFakeOrderingStep: TcpChainStepModel?
        get() = primaryTcpChainStep(tcpChainSteps)?.takeIf { it.kind.supportsFakeOrdering }

    val fakeOrderingVisualEditorSupported: Boolean
        get() = primaryFakeOrderingStep != null && tcpChainSteps.count { !it.kind.isTlsPrelude } == 1

    val fakeApproximationStepCount: Int
        get() = fakeApproximationSteps.size

    val hasFakeApproximation: Boolean
        get() = fakeApproximationStepCount > 0

    val primaryFakeApproximationStep: TcpChainStepModel?
        get() = fakeApproximationSteps.firstOrNull()

    val hasFakeSplitApproximation: Boolean
        get() = fakeApproximationSteps.any { it.kind == TcpChainStepKind.FakeSplit }

    val hasFakeDisorderApproximation: Boolean
        get() = fakeApproximationSteps.any { it.kind == TcpChainStepKind.FakeDisorder }

    val seqOverlapStepCount: Int
        get() = seqOverlapSteps.size

    val hasSeqOverlap: Boolean
        get() = seqOverlapStepCount > 0

    val primarySeqOverlapStep: TcpChainStepModel?
        get() = seqOverlapSteps.firstOrNull()

    val usesSeqOverlapFakeProfile: Boolean
        get() = seqOverlapSteps.any { it.fakeMode == SeqOverlapFakeModeProfile }

    val seqOverlapEffectiveSize: Int
        get() = primarySeqOverlapStep?.overlapSize?.takeIf { it > 0 } ?: DefaultSeqOverlapSize

    val hasCustomActivationWindow: Boolean
        get() = formatActivationFilterSummary(groupActivationFilter).isNotBlank()

    val stepActivationFilterCount: Int
        get() =
            tcpChainSteps.count { !it.activationFilter.isEmpty } +
                udpChainSteps.count { !it.activationFilter.isEmpty }

    val hasStepActivationFilters: Boolean
        get() = stepActivationFilterCount > 0

    val activationWindowSummary: String
        get() = formatActivationFilterSummary(groupActivationFilter).ifBlank { "Always active" }

    val adaptiveSplitPreset: String
        get() =
            when (splitMarker) {
                AdaptiveMarkerBalanced -> {
                    AdaptiveMarkerBalanced
                }

                AdaptiveMarkerHost -> {
                    AdaptiveMarkerHost
                }

                AdaptiveMarkerEndHost -> {
                    AdaptiveMarkerEndHost
                }

                AdaptiveMarkerSniExt -> {
                    AdaptiveMarkerSniExt
                }

                else -> {
                    if (isAdaptiveOffsetExpression(splitMarker)) {
                        AdaptiveSplitPresetCustom
                    } else {
                        AdaptiveSplitPresetManual
                    }
                }
            }

    val hasAdaptiveSplitPreset: Boolean
        get() = adaptiveSplitPreset != AdaptiveSplitPresetManual

    val hasCustomAdaptiveSplitPreset: Boolean
        get() = adaptiveSplitPreset == AdaptiveSplitPresetCustom

    val adaptiveSplitVisualEditorSupported: Boolean
        get() = primaryTcpChainStep(tcpChainSteps)?.kind?.supportsAdaptiveMarker != false
}
