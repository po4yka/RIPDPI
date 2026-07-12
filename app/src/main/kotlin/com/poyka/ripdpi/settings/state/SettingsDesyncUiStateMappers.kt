package com.poyka.ripdpi.settings.state

import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.FakeTransportUiState
import com.poyka.ripdpi.activities.TlsPreludeUiState
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.effectiveIpIdMode
import com.poyka.ripdpi.data.effectiveSplitMarker
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.effectiveTlsRecordMarker
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.tlsPreludeTcpChainStep
import com.poyka.ripdpi.data.usesSeqOverlapFakeProfile
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.collections.immutable.toImmutableList

internal data class ChainAnalysisResult(
    val tcpChainSteps: List<TcpChainStepModel>,
    val udpChainSteps: List<UdpChainStepModel>,
    val tlsPreludeSteps: List<TcpChainStepModel>,
    val primaryTcpStep: TcpChainStepModel?,
    val tlsRecStep: TcpChainStepModel?,
    val normalizedDesyncMethod: String,
    val desyncEnabled: Boolean,
    val isFake: Boolean,
    val usesFakeTransport: Boolean,
    val usesSeqOverlapFakeProfile: Boolean,
    val hasHostFake: Boolean,
    val hasDisoob: Boolean,
    val isOob: Boolean,
    val desyncHttpEnabled: Boolean,
    val desyncHttpsEnabled: Boolean,
    val desyncUdpEnabled: Boolean,
    val tlsRecEnabled: Boolean,
)

internal fun AppSettings.analyzeChainFlags(): ChainAnalysisResult {
    val tcpChainSteps = effectiveTcpChainSteps()
    val udpChainSteps = effectiveUdpChainSteps()
    val tlsPreludeSteps = tcpChainSteps.filter { it.kind.isTlsPrelude }
    val primaryTcpStep = primaryTcpChainStep(tcpChainSteps)
    val tlsRecStep = tlsPreludeTcpChainStep(tcpChainSteps)
    val normalizedDesyncMethod = primaryDesyncMethod(tcpChainSteps).ifEmpty { "none" }
    val desyncEnabled = primaryTcpStep != null
    val isFake =
        tcpChainSteps.any {
            it.kind == TcpChainStepKind.Fake ||
                it.kind == TcpChainStepKind.FakeSplit ||
                it.kind == TcpChainStepKind.FakeDisorder
        }
    val usesFakeTransport =
        tcpChainSteps.any {
            it.kind == TcpChainStepKind.Fake ||
                it.kind == TcpChainStepKind.FakeSplit ||
                it.kind == TcpChainStepKind.FakeDisorder ||
                it.kind == TcpChainStepKind.HostFake
        }
    val desyncAllUnchecked = !desyncHttp && !desyncHttps && !desyncUdp
    val desyncHttpEnabled = desyncAllUnchecked || desyncHttp
    val desyncHttpsEnabled = desyncAllUnchecked || desyncHttps
    val desyncUdpEnabled = desyncAllUnchecked || desyncUdp
    return ChainAnalysisResult(
        tcpChainSteps = tcpChainSteps,
        udpChainSteps = udpChainSteps,
        tlsPreludeSteps = tlsPreludeSteps,
        primaryTcpStep = primaryTcpStep,
        tlsRecStep = tlsRecStep,
        normalizedDesyncMethod = normalizedDesyncMethod,
        desyncEnabled = desyncEnabled,
        isFake = isFake,
        usesFakeTransport = usesFakeTransport,
        usesSeqOverlapFakeProfile = tcpChainSteps.any { it.usesSeqOverlapFakeProfile() },
        hasHostFake = tcpChainSteps.any { it.kind == TcpChainStepKind.HostFake },
        hasDisoob = tcpChainSteps.any { it.kind == TcpChainStepKind.Disoob },
        isOob = tcpChainSteps.any { it.kind == TcpChainStepKind.Oob || it.kind == TcpChainStepKind.Disoob },
        desyncHttpEnabled = desyncHttpEnabled,
        desyncHttpsEnabled = desyncHttpsEnabled,
        desyncUdpEnabled = desyncUdpEnabled,
        tlsRecEnabled = desyncHttpsEnabled && tlsRecStep != null,
    )
}

internal fun AppSettings.buildDesyncUiState(chain: ChainAnalysisResult): DesyncCoreUiState =
    DesyncCoreUiState(
        desyncMethod = chain.normalizedDesyncMethod,
        tcpChainSteps = chain.tcpChainSteps.toImmutableList(),
        udpChainSteps = chain.udpChainSteps.toImmutableList(),
        groupActivationFilter = effectiveGroupActivationFilter(),
        chainSummary = formatChainSummary(chain.tcpChainSteps, chain.udpChainSteps),
        chainDsl = formatStrategyChainDsl(chain.tcpChainSteps, chain.udpChainSteps),
        splitMarker = chain.primaryTcpStep?.marker ?: effectiveSplitMarker(),
        udpFakeCount = chain.udpChainSteps.sumOf { it.count.coerceAtLeast(0) },
        defaultTtl = defaultTtl,
        customTtl = customTtl,
    )

internal fun AppSettings.buildFakeUiState(): FakeTransportUiState =
    FakeTransportUiState(
        fakeTtl = fakeTtl.takeIf { it > 0 } ?: 8,
        adaptiveFakeTtlEnabled = adaptiveFakeTtlEnabled,
        adaptiveFakeTtlDelta = effectiveAdaptiveFakeTtlDelta(),
        adaptiveFakeTtlMin = effectiveAdaptiveFakeTtlMin(),
        adaptiveFakeTtlMax = effectiveAdaptiveFakeTtlMax(),
        adaptiveFakeTtlFallback = effectiveAdaptiveFakeTtlFallback(),
        fakeSni = fakeSni.ifEmpty { DefaultFakeSni },
        fakeOffsetMarker = effectiveFakeOffsetMarker(),
        httpFakeProfile = effectiveHttpFakeProfile(),
        fakeTlsUseOriginal = fakeTlsUseOriginal,
        fakeTlsRandomize = fakeTlsRandomize,
        fakeTlsDupSessionId = fakeTlsDupSessionId,
        fakeTlsPadEncap = fakeTlsPadEncap,
        fakeTlsSize = fakeTlsSize,
        fakeTlsSniMode = effectiveFakeTlsSniMode(),
        tlsFakeProfile = effectiveTlsFakeProfile(),
        udpFakeProfile = effectiveUdpFakeProfile(),
        oobData = oobData.ifEmpty { "a" },
        dropSack = dropSack,
        md5sig = fakeMd5Sig,
        tlsMinorEnabled = tlsMinorEnabled,
        tlsMinorValue = tlsMinorValue,
        ipIdMode = effectiveIpIdMode(),
    )

internal fun AppSettings.buildTlsPreludeUiState(chain: ChainAnalysisResult): TlsPreludeUiState =
    TlsPreludeUiState(
        tlsrecEnabled = chain.tlsRecStep != null,
        tlsrecMarker = chain.tlsRecStep?.marker ?: effectiveTlsRecordMarker(),
        tlsPreludeMode = chain.tlsRecStep?.kind?.wireName ?: "disabled",
        tlsPreludeStepCount = chain.tlsPreludeSteps.size,
        tlsRandRecFragmentCount =
            chain.tlsRecStep?.fragmentCount?.takeIf { it > 0 }
                ?: DefaultTlsRandRecFragmentCount,
        tlsRandRecMinFragmentSize =
            chain.tlsRecStep?.minFragmentSize?.takeIf { it > 0 }
                ?: DefaultTlsRandRecMinFragmentSize,
        tlsRandRecMaxFragmentSize =
            chain.tlsRecStep?.maxFragmentSize?.takeIf { it > 0 }
                ?: DefaultTlsRandRecMaxFragmentSize,
    )
