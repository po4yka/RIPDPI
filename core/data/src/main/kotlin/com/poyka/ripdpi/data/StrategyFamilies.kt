package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

data class StrategyLaneFamilies(
    val tcpStrategyFamily: String? = null,
    val quicStrategyFamily: String? = null,
    val dnsStrategyFamily: String? = null,
    val dnsStrategyLabel: String? = null,
)

fun AppSettings.deriveStrategyLaneFamilies(activeDns: ActiveDnsSettings = activeDnsSettings()): StrategyLaneFamilies =
    deriveStrategyLaneFamilies(
        tcpSteps = effectiveTcpChainSteps(),
        udpSteps = effectiveUdpChainSteps(),
        desyncUdp = desyncUdp,
        quicInitialMode = effectiveQuicInitialMode(),
        quicFakeProfile = effectiveQuicFakeProfile(),
        activeDns = activeDns,
    )

fun deriveStrategyLaneFamilies(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
    desyncUdp: Boolean,
    quicInitialMode: String,
    quicFakeProfile: String,
    activeDns: ActiveDnsSettings? = null,
): StrategyLaneFamilies =
    deriveStrategyLaneFamilies(
        tcpSteps = tcpSteps,
        tcpRotationCandidateCount = 0,
        udpSteps = udpSteps,
        desyncUdp = desyncUdp,
        quicInitialMode = quicInitialMode,
        quicFakeProfile = quicFakeProfile,
        activeDns = activeDns,
    )

fun deriveStrategyLaneFamilies(
    tcpSteps: List<TcpChainStepModel>,
    tcpRotationCandidateCount: Int = 0,
    udpSteps: List<UdpChainStepModel>,
    desyncUdp: Boolean,
    quicInitialMode: String,
    quicFakeProfile: String,
    activeDns: ActiveDnsSettings? = null,
): StrategyLaneFamilies =
    StrategyLaneFamilies(
        tcpStrategyFamily = deriveTcpStrategyFamily(tcpSteps, tcpRotationCandidateCount),
        quicStrategyFamily =
            deriveQuicStrategyFamily(
                udpSteps = udpSteps,
                desyncUdp = desyncUdp,
                quicInitialMode = quicInitialMode,
                quicFakeProfile = quicFakeProfile,
            ),
        dnsStrategyFamily = activeDns?.strategyFamily(),
        dnsStrategyLabel = activeDns?.strategyLabel(),
    )

@Suppress("CyclomaticComplexMethod")
fun deriveTcpStrategyFamily(tcpSteps: List<TcpChainStepModel>): String? = deriveTcpStrategyFamily(tcpSteps, 0)

@Suppress("CyclomaticComplexMethod")
fun deriveTcpStrategyFamily(
    tcpSteps: List<TcpChainStepModel>,
    tcpRotationCandidateCount: Int = 0,
): String? {
    val primary = primaryTcpChainStep(tcpSteps)
    val tlsPrelude = tlsPreludeTcpChainStep(tcpSteps)
    val baseFamily =
        if (primary == null) {
            when (tlsPrelude?.kind) {
                TcpChainStepKind.TlsRandRec -> "tlsrandrec"
                TcpChainStepKind.TlsRec -> "tlsrec"
                else -> null
            }
        } else {
            when {
                primary.kind == TcpChainStepKind.SeqOverlap && tlsPrelude != null -> {
                    "tlsrec_seqovl"
                }

                primary.kind == TcpChainStepKind.SeqOverlap -> {
                    "seqovl"
                }

                primary.kind == TcpChainStepKind.HostFake -> {
                    "hostfake"
                }

                primary.kind == TcpChainStepKind.IpFrag2 -> {
                    "ipfrag2"
                }

                primary.kind == TcpChainStepKind.FakeSplit || primary.kind == TcpChainStepKind.FakeDisorder -> {
                    "fake_approx"
                }

                primary.kind == TcpChainStepKind.Split && tlsPrelude != null -> {
                    "tlsrec_split"
                }

                primary.kind == TcpChainStepKind.MultiDisorder && tlsPrelude != null -> {
                    "tlsrec_multidisorder"
                }

                primary.kind == TcpChainStepKind.Disorder && tlsPrelude != null -> {
                    "tlsrec_disorder"
                }

                tlsPrelude != null &&
                    (
                        primary.kind == TcpChainStepKind.Fake ||
                            primary.kind == TcpChainStepKind.Oob ||
                            primary.kind == TcpChainStepKind.Disoob
                    ) -> {
                    "tlsrec_fake"
                }

                else -> {
                    primary.kind.wireName
                }
            }
        }
    return if (baseFamily != null && tcpRotationCandidateCount > 0) {
        "circular_$baseFamily"
    } else {
        baseFamily
    }
}

@Suppress("ReturnCount")
fun deriveQuicStrategyFamily(
    udpSteps: List<UdpChainStepModel>,
    desyncUdp: Boolean,
    quicInitialMode: String,
    quicFakeProfile: String,
): String {
    if (!desyncUdp || !quicInitialModeUsesRouting(quicInitialMode)) {
        return "quic_disabled"
    }
    val primaryStep =
        udpSteps.firstOrNull { step ->
            step.kind == UdpChainStepKind.IpFrag2Udp || step.count > 0
        }
            ?: return "quic_disabled"
    return primaryStep.kind.toQuicStrategyFamily(quicFakeProfile)
}

private fun UdpChainStepKind.toQuicStrategyFamily(quicFakeProfile: String): String =
    when (this) {
        UdpChainStepKind.FakeBurst -> quicBurstFamily(quicFakeProfile)
        UdpChainStepKind.DummyPrepend -> "quic_dummy_prepend"
        UdpChainStepKind.QuicSniSplit -> "quic_sni_split"
        UdpChainStepKind.QuicFakeVersion -> "quic_fake_version"
        UdpChainStepKind.QuicCryptoSplit -> "quic_crypto_split"
        UdpChainStepKind.QuicPaddingLadder -> "quic_padding_ladder"
        UdpChainStepKind.QuicCidChurn -> "quic_cid_churn"
        UdpChainStepKind.QuicPacketNumberGap -> "quic_packet_number_gap"
        UdpChainStepKind.QuicVersionNegotiationDecoy -> "quic_version_negotiation_decoy"
        UdpChainStepKind.QuicMultiInitialRealistic -> "quic_multi_initial_realistic"
        UdpChainStepKind.IpFrag2Udp -> "quic_ipfrag2"
    }

private fun quicBurstFamily(quicFakeProfile: String): String =
    when (normalizeQuicFakeProfile(quicFakeProfile)) {
        QuicFakeProfileDisabled -> "quic_disabled"
        QuicFakeProfileCompatDefault -> "quic_compat_burst"
        QuicFakeProfileRealisticInitial -> "quic_realistic_burst"
        else -> "quic_burst"
    }

fun ActiveDnsSettings.strategyFamily(): String =
    when {
        isPlainUdp -> "dns_plain_udp"
        isDoh -> "dns_encrypted_doh"
        isDot -> "dns_encrypted_dot"
        isDnsCrypt -> "dns_encrypted_dnscrypt"
        isEncrypted -> "dns_encrypted"
        else -> "dns_plain_udp"
    }

fun ActiveDnsSettings.strategyLabel(): String =
    when {
        isPlainUdp -> "Plain DNS"
        isEncrypted -> "$providerDisplayName ${protocolDisplayName(encryptedDnsProtocol)}"
        else -> summary()
    }

@Suppress("CyclomaticComplexMethod")
fun strategyLaneFamilyLabel(family: String): String {
    val normalized = family.trim().lowercase()
    if (normalized.startsWith("circular_")) {
        return "Circular rotation (${strategyLaneFamilyLabel(normalized.removePrefix("circular_"))})"
    }
    return when (normalized) {
        "hostfake" -> "Hostfake"
        "seqovl" -> "Sequence overlap"
        "tlsrec_seqovl" -> "TLS record + sequence overlap"
        "fake_approx" -> "Fake approximation"
        "split" -> "Split"
        "multidisorder" -> "Multi-disorder"
        "disorder" -> "Disorder"
        "fake" -> "Fake"
        "oob" -> "OOB"
        "disoob" -> "Disorder OOB"
        "tlsrec" -> "TLS record"
        "tlsrandrec" -> "TLS random record"
        "ipfrag2" -> "IP fragmentation"
        "ech_split" -> "ECH extension split"
        "ech_tlsrec" -> "ECH TLS record split"
        "tlsrec_split" -> "TLS record split"
        "tlsrec_multidisorder" -> "TLS record multi-disorder"
        "tlsrec_disorder" -> "TLS record disorder"
        "tlsrec_fake" -> "TLS record fake"
        "quic_ipfrag2" -> "QUIC IP fragmentation"
        "quic_disabled" -> "QUIC disabled"
        "quic_compat_burst" -> "QUIC compat burst"
        "quic_realistic_burst" -> "QUIC realistic burst"
        "quic_burst" -> "QUIC burst"
        "quic_dummy_prepend" -> "QUIC dummy prepend"
        "quic_sni_split" -> "QUIC SNI split"
        "quic_fake_version" -> "QUIC fake version"
        "quic_crypto_split" -> "QUIC CRYPTO split"
        "quic_padding_ladder" -> "QUIC padding ladder"
        "quic_cid_churn" -> "QUIC CID churn"
        "quic_packet_number_gap" -> "QUIC packet number gap"
        "quic_version_negotiation_decoy" -> "QUIC version negotiation decoy"
        "quic_multi_initial_realistic" -> "QUIC multi-initial realistic"
        "dns_plain_udp" -> "Plain DNS"
        "dns_encrypted_doh" -> "Encrypted DNS (DoH)"
        "dns_encrypted_dot" -> "Encrypted DNS (DoT)"
        "dns_encrypted_dnscrypt" -> "Encrypted DNS (DNSCrypt)"
        "dns_encrypted" -> "Encrypted DNS"
        else -> family.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
