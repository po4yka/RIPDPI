package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiTcpRotationCandidateConfig
import com.poyka.ripdpi.core.RipDpiTcpRotationConfig
import kotlinx.serialization.Serializable

@Serializable
internal data class NativeNumericRange(
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
internal data class NativeActivationFilter(
    val round: NativeNumericRange? = null,
    val payloadSize: NativeNumericRange? = null,
    val streamBytes: NativeNumericRange? = null,
    val tcpHasTimestamp: Boolean? = null,
    val tcpHasEch: Boolean? = null,
    val tcpWindowBelow: Int? = null,
    val tcpMssBelow: Int? = null,
)

@Serializable
internal data class NativeTcpChainStep(
    val kind: String,
    val marker: String,
    val midhostMarker: String,
    val fakeHostTemplate: String,
    val fakeOrder: String = "",
    val fakeSeqMode: String = "",
    val overlapSize: Int = 0,
    val fakeMode: String = "",
    val fragmentCount: Int,
    val minFragmentSize: Int,
    val maxFragmentSize: Int,
    val activationFilter: NativeActivationFilter? = null,
    val ipv6ExtensionProfile: String = "none",
    val tcpFlagsSet: String = "",
    val tcpFlagsUnset: String = "",
    val tcpFlagsOrigSet: String = "",
    val tcpFlagsOrigUnset: String = "",
)

@Serializable
internal data class NativeUdpChainStep(
    val kind: String,
    val count: Int,
    val splitBytes: Int = 0,
    val activationFilter: NativeActivationFilter? = null,
    val ipv6ExtensionProfile: String = "none",
)

@Serializable
internal data class NativeTcpRotationCandidate(
    val tcpSteps: List<NativeTcpChainStep> = emptyList(),
)

@Serializable
internal data class NativeTcpRotationConfig(
    val fails: Int = 3,
    val retrans: Int = 3,
    val seq: Int = 65_536,
    val rst: Int = 1,
    val timeSecs: Long = 60,
    val candidates: List<NativeTcpRotationCandidate> = emptyList(),
    val cancelOnFailure: Boolean? = null,
)

@Serializable
internal data class NativeChainConfig(
    val groupActivationFilter: NativeActivationFilter? = null,
    val tcpSteps: List<NativeTcpChainStep> =
        listOf(
            NativeTcpChainStep(
                kind = "split",
                marker = CanonicalDefaultSplitMarker,
                midhostMarker = "",
                fakeHostTemplate = "",
                fakeOrder = "",
                fakeSeqMode = "",
                overlapSize = 0,
                fakeMode = "",
                fragmentCount = 0,
                minFragmentSize = 0,
                maxFragmentSize = 0,
                tcpFlagsSet = "",
                tcpFlagsUnset = "",
                tcpFlagsOrigSet = "",
                tcpFlagsOrigUnset = "",
            ),
        ),
    val tcpRotation: NativeTcpRotationConfig? = null,
    val udpSteps: List<NativeUdpChainStep> = emptyList(),
    val anyProtocol: Boolean = false,
    val payloadDisable: List<String> = emptyList(),
)

internal object RangeCodec {
    fun toModel(value: NativeNumericRange): NumericRangeModel =
        NumericRangeModel(
            start = value.start,
            end = value.end,
        )

    fun toNative(value: NumericRangeModel): NativeNumericRange? =
        if (value.start == null && value.end == null) {
            null
        } else {
            NativeNumericRange(start = value.start, end = value.end)
        }

    fun toModel(value: NativeActivationFilter): ActivationFilterModel =
        normalizeActivationFilter(
            ActivationFilterModel(
                round = value.round?.let(::toModel) ?: NumericRangeModel(),
                payloadSize = value.payloadSize?.let(::toModel) ?: NumericRangeModel(),
                streamBytes = value.streamBytes?.let(::toModel) ?: NumericRangeModel(),
                tcpHasTimestamp = value.tcpHasTimestamp,
                tcpHasEch = value.tcpHasEch,
                tcpWindowBelow = value.tcpWindowBelow,
                tcpMssBelow = value.tcpMssBelow,
            ),
        )

    fun toNative(value: ActivationFilterModel): NativeActivationFilter? =
        normalizeActivationFilter(value).let { normalized ->
            val round = toNative(normalized.round)
            val payloadSize = toNative(normalized.payloadSize)
            val streamBytes = toNative(normalized.streamBytes)
            val allFields = sequenceOf(
                round,
                payloadSize,
                streamBytes,
                normalized.tcpHasTimestamp,
                normalized.tcpHasEch,
                normalized.tcpWindowBelow,
                normalized.tcpMssBelow,
            )
            if (allFields.all { it == null }) {
                null
            } else {
                NativeActivationFilter(
                    round = round,
                    payloadSize = payloadSize,
                    streamBytes = streamBytes,
                    tcpHasTimestamp = normalized.tcpHasTimestamp,
                    tcpHasEch = normalized.tcpHasEch,
                    tcpWindowBelow = normalized.tcpWindowBelow,
                    tcpMssBelow = normalized.tcpMssBelow,
                )
            }
        }
}

internal object ChainCodec {
    private fun nativeTcpStepToModel(step: NativeTcpChainStep): TcpChainStepModel? {
        val kind = TcpChainStepKind.fromWireName(step.kind) ?: return null
        return TcpChainStepModel(
            kind = kind,
            marker = step.marker,
            midhostMarker = step.midhostMarker,
            fakeHostTemplate = step.fakeHostTemplate,
            fakeOrder = step.fakeOrder,
            fakeSeqMode = step.fakeSeqMode,
            overlapSize = step.overlapSize,
            fakeMode = step.fakeMode,
            fragmentCount = step.fragmentCount,
            minFragmentSize = step.minFragmentSize,
            maxFragmentSize = step.maxFragmentSize,
            activationFilter = step.activationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
            ipv6ExtensionProfile = step.ipv6ExtensionProfile,
            tcpFlagsSet = step.tcpFlagsSet,
            tcpFlagsUnset = step.tcpFlagsUnset,
            tcpFlagsOrigSet = step.tcpFlagsOrigSet,
            tcpFlagsOrigUnset = step.tcpFlagsOrigUnset,
        )
    }

    private fun modelTcpStepToNative(stepModel: TcpChainStepModel): NativeTcpChainStep {
        val step = normalizeTcpChainStepModel(stepModel)
        return NativeTcpChainStep(
            kind = step.kind.wireName,
            marker = step.marker,
            midhostMarker = step.midhostMarker,
            fakeHostTemplate = step.fakeHostTemplate,
            fakeOrder = step.fakeOrder,
            fakeSeqMode = step.fakeSeqMode,
            overlapSize = step.overlapSize,
            fakeMode = step.fakeMode,
            fragmentCount = step.fragmentCount,
            minFragmentSize = step.minFragmentSize,
            maxFragmentSize = step.maxFragmentSize,
            activationFilter = RangeCodec.toNative(step.activationFilter),
            ipv6ExtensionProfile = step.ipv6ExtensionProfile,
            tcpFlagsSet = step.tcpFlagsSet,
            tcpFlagsUnset = step.tcpFlagsUnset,
            tcpFlagsOrigSet = step.tcpFlagsOrigSet,
            tcpFlagsOrigUnset = step.tcpFlagsOrigUnset,
        )
    }

    fun toModel(value: NativeChainConfig): RipDpiChainConfig =
        RipDpiChainConfig(
            groupActivationFilter =
                value.groupActivationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
            tcpSteps = value.tcpSteps.mapNotNull(::nativeTcpStepToModel),
            tcpRotation =
                value.tcpRotation?.let { rotation ->
                    RipDpiTcpRotationConfig(
                        fails = rotation.fails,
                        retrans = rotation.retrans,
                        seq = rotation.seq,
                        rst = rotation.rst,
                        timeSecs = rotation.timeSecs,
                        candidates =
                            rotation.candidates.map { candidate ->
                                RipDpiTcpRotationCandidateConfig(
                                    tcpSteps = candidate.tcpSteps.mapNotNull(::nativeTcpStepToModel),
                                )
                            },
                        cancelOnFailure = rotation.cancelOnFailure ?: true,
                    )
                },
            udpSteps =
                value.udpSteps.mapNotNull { step ->
                    val kind = UdpChainStepKind.fromWireName(step.kind) ?: return@mapNotNull null
                    UdpChainStepModel(
                        kind = kind,
                        count = step.count,
                        splitBytes = step.splitBytes,
                        activationFilter =
                            step.activationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
                        ipv6ExtensionProfile = step.ipv6ExtensionProfile,
                    )
                },
            anyProtocol = value.anyProtocol,
            payloadDisable = value.payloadDisable,
        )

    fun toNative(value: RipDpiChainConfig): NativeChainConfig =
        NativeChainConfig(
            groupActivationFilter = RangeCodec.toNative(value.groupActivationFilter),
            tcpSteps = value.tcpSteps.map(::modelTcpStepToNative),
            tcpRotation =
                value.tcpRotation?.let { rotation ->
                    NativeTcpRotationConfig(
                        fails = rotation.fails,
                        retrans = rotation.retrans,
                        seq = rotation.seq,
                        rst = rotation.rst,
                        timeSecs = rotation.timeSecs,
                        candidates =
                            rotation.candidates.map { candidate ->
                                NativeTcpRotationCandidate(
                                    tcpSteps = candidate.tcpSteps.map(::modelTcpStepToNative),
                                )
                            },
                        cancelOnFailure = rotation.cancelOnFailure,
                    )
                },
            udpSteps =
                value.udpSteps.map {
                    NativeUdpChainStep(
                        kind = it.kind.wireName,
                        count = it.count,
                        splitBytes = it.splitBytes,
                        activationFilter = RangeCodec.toNative(it.activationFilter),
                        ipv6ExtensionProfile = it.ipv6ExtensionProfile,
                    )
                },
            anyProtocol = value.anyProtocol,
            payloadDisable = value.payloadDisable,
        )
}
