package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

const val StrategyIpv6ExtensionProfileNone = "none"
const val StrategyIpv6ExtensionProfileHopByHop = "hopByHop"
const val StrategyIpv6ExtensionProfileHopByHop2 = "hopByHop2"
const val StrategyIpv6ExtensionProfileDestOpt = "destOpt"
const val StrategyIpv6ExtensionProfileHopByHopDestOpt = "hopByHopDestOpt"

@Serializable
enum class StrategyIpv6ExtensionProfile(
    val wireName: String,
) {
    None(StrategyIpv6ExtensionProfileNone),
    HopByHop(StrategyIpv6ExtensionProfileHopByHop),
    HopByHop2(StrategyIpv6ExtensionProfileHopByHop2),
    DestOpt(StrategyIpv6ExtensionProfileDestOpt),
    HopByHopDestOpt(StrategyIpv6ExtensionProfileHopByHopDestOpt),
    ;

    companion object {
        fun fromWireName(value: String): StrategyIpv6ExtensionProfile? =
            entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) }
    }
}

fun normalizeStrategyIpv6ExtensionProfile(value: String): String =
    StrategyIpv6ExtensionProfile
        .fromWireName(value)
        ?.wireName
        ?: StrategyIpv6ExtensionProfileNone

@Serializable
enum class TcpChainStepKind(
    val wireName: String,
) {
    Split("split"),
    SynData("syndata"),
    SeqOverlap("seqovl"),
    Disorder("disorder"),
    MultiDisorder("multidisorder"),
    Fake("fake"),
    FakeSplit("fakedsplit"),
    FakeDisorder("fakeddisorder"),
    HostFake("hostfake"),
    FakeRst("fakerst"),
    Oob("oob"),
    Disoob("disoob"),
    TlsRec("tlsrec"),
    TlsRandRec("tlsrandrec"),
    IpFrag2("ipfrag2"),
    ;

    companion object {
        fun fromWireName(value: String): TcpChainStepKind? =
            entries.firstOrNull {
                it.wireName ==
                    value.trim().lowercase()
            }
    }
}

val TcpChainStepKind.supportsAdaptiveMarker: Boolean
    get() =
        when (this) {
            TcpChainStepKind.HostFake,
            TcpChainStepKind.MultiDisorder,
            -> false

            else -> true
        }

fun TcpChainStepKind.desyncMethodLabel(): String? =
    when (this) {
        TcpChainStepKind.Split -> "split"
        TcpChainStepKind.SynData -> "syndata"
        TcpChainStepKind.SeqOverlap -> "seqovl"
        TcpChainStepKind.Disorder -> "disorder"
        TcpChainStepKind.MultiDisorder -> "multidisorder"
        TcpChainStepKind.Fake -> "fake"
        TcpChainStepKind.FakeSplit -> "fake"
        TcpChainStepKind.FakeDisorder -> "disorder"
        TcpChainStepKind.HostFake -> "fake"
        TcpChainStepKind.FakeRst -> "fakerst"
        TcpChainStepKind.Oob -> "oob"
        TcpChainStepKind.Disoob -> "disoob"
        TcpChainStepKind.TlsRec -> null
        TcpChainStepKind.TlsRandRec -> null
        TcpChainStepKind.IpFrag2 -> "ipfrag2"
    }

@Serializable
data class TcpChainStepModel(
    val kind: TcpChainStepKind,
    val marker: String,
    val midhostMarker: String = "",
    val fakeHostTemplate: String = "",
    val fakeOrder: String = FakeOrderDefault,
    val fakeSeqMode: String = FakeSeqModeDuplicate,
    val overlapSize: Int = 0,
    val fakeMode: String = "",
    val fragmentCount: Int = 0,
    val minFragmentSize: Int = 0,
    val maxFragmentSize: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
    val ipv6ExtensionProfile: String = StrategyIpv6ExtensionProfileNone,
    val tcpFlagsSet: String = "",
    val tcpFlagsUnset: String = "",
    val tcpFlagsOrigSet: String = "",
    val tcpFlagsOrigUnset: String = "",
)

@Serializable
enum class UdpChainStepKind(
    val wireName: String,
) {
    FakeBurst("fake_burst"),
    DummyPrepend("dummy_prepend"),
    QuicSniSplit("quic_sni_split"),
    QuicFakeVersion("quic_fake_version"),
    QuicCryptoSplit("quic_crypto_split"),
    QuicPaddingLadder("quic_padding_ladder"),
    QuicCidChurn("quic_cid_churn"),
    QuicPacketNumberGap("quic_packet_number_gap"),
    QuicVersionNegotiationDecoy("quic_version_negotiation_decoy"),
    QuicMultiInitialRealistic("quic_multi_initial_realistic"),
    IpFrag2Udp("ipfrag2_udp"),
    ;

    companion object {
        fun fromWireName(value: String): UdpChainStepKind? =
            entries.firstOrNull {
                it.wireName ==
                    value.trim().lowercase()
            }
    }
}

@Serializable
data class UdpChainStepModel(
    val count: Int,
    val kind: UdpChainStepKind = UdpChainStepKind.FakeBurst,
    val splitBytes: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
    val ipv6ExtensionProfile: String = StrategyIpv6ExtensionProfileNone,
)

@Serializable
data class StrategyChainSet(
    val tcpSteps: List<TcpChainStepModel> = emptyList(),
    val udpSteps: List<UdpChainStepModel> = emptyList(),
)

val TcpChainStepKind.isTlsPrelude: Boolean
    get() = this == TcpChainStepKind.TlsRec || this == TcpChainStepKind.TlsRandRec

fun primaryTcpChainStep(tcpSteps: List<TcpChainStepModel>): TcpChainStepModel? =
    tcpSteps.firstOrNull { !it.kind.isTlsPrelude }

fun primaryDesyncMethod(tcpSteps: List<TcpChainStepModel>): String =
    primaryTcpChainStep(tcpSteps)?.kind?.desyncMethodLabel() ?: "none"

fun tlsPreludeTcpChainStep(tcpSteps: List<TcpChainStepModel>): TcpChainStepModel? =
    tcpSteps.firstOrNull { it.kind.isTlsPrelude }

fun tlsPreludeTcpChainSteps(tcpSteps: List<TcpChainStepModel>): List<TcpChainStepModel> =
    tcpSteps.filter { it.kind.isTlsPrelude }

fun replaceTlsPreludeTcpChainSteps(
    tcpSteps: List<TcpChainStepModel>,
    newPreludeSteps: List<TcpChainStepModel>,
): List<TcpChainStepModel> {
    val updated =
        newPreludeSteps.map(::normalizeTcpChainStepModel) +
            tcpSteps.filterNot { it.kind.isTlsPrelude }
    validateTcpChain(updated)
    return updated
}

// ROADMAP-architecture-refactor W1
@Suppress("ReturnCount")
fun rewritePrimaryTcpMarker(
    tcpSteps: List<TcpChainStepModel>,
    marker: String,
): List<TcpChainStepModel> {
    val index = tcpSteps.indexOfFirst { !it.kind.isTlsPrelude }
    if (index < 0) {
        return tcpSteps
    }
    val step = tcpSteps[index]
    if (!step.kind.supportsAdaptiveMarker) {
        return tcpSteps
    }
    return tcpSteps.toMutableList().apply {
        this[index] = normalizeTcpChainStepModel(step.copy(marker = marker))
    }
}
