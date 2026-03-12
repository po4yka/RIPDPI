package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

data class StrategyLaneFamilies(
    val tcpStrategyFamily: String? = null,
    val quicStrategyFamily: String? = null,
    val dnsStrategyFamily: String? = null,
    val dnsStrategyLabel: String? = null,
)

fun AppSettings.deriveStrategyLaneFamilies(
    activeDns: ActiveDnsSettings = activeDnsSettings(),
): StrategyLaneFamilies =
    deriveStrategyLaneFamilies(
        tcpSteps = effectiveTcpChainSteps(),
        desyncUdp = desyncUdp,
        quicInitialMode = effectiveQuicInitialMode(),
        quicFakeProfile = effectiveQuicFakeProfile(),
        activeDns = activeDns,
    )

fun deriveStrategyLaneFamilies(
    tcpSteps: List<TcpChainStepModel>,
    desyncUdp: Boolean,
    quicInitialMode: String,
    quicFakeProfile: String,
    activeDns: ActiveDnsSettings? = null,
): StrategyLaneFamilies =
    StrategyLaneFamilies(
        tcpStrategyFamily = deriveTcpStrategyFamily(tcpSteps),
        quicStrategyFamily =
            deriveQuicStrategyFamily(
                desyncUdp = desyncUdp,
                quicInitialMode = quicInitialMode,
                quicFakeProfile = quicFakeProfile,
            ),
        dnsStrategyFamily = activeDns?.strategyFamily(),
        dnsStrategyLabel = activeDns?.strategyLabel(),
    )

fun deriveTcpStrategyFamily(tcpSteps: List<TcpChainStepModel>): String? {
    val primary = primaryTcpChainStep(tcpSteps)
    val tlsPrelude = tlsPreludeTcpChainStep(tcpSteps)
    if (primary == null) {
        return when (tlsPrelude?.kind) {
            TcpChainStepKind.TlsRandRec -> "tlsrandrec"
            TcpChainStepKind.TlsRec -> "tlsrec"
            else -> null
        }
    }
    return when {
        primary.kind == TcpChainStepKind.HostFake -> "hostfake"
        primary.kind == TcpChainStepKind.FakeSplit || primary.kind == TcpChainStepKind.FakeDisorder -> "fake_approx"
        primary.kind == TcpChainStepKind.Split && tlsPrelude != null -> "tlsrec_split"
        primary.kind == TcpChainStepKind.Disorder && tlsPrelude != null -> "tlsrec_disorder"
        tlsPrelude != null &&
            (
                primary.kind == TcpChainStepKind.Fake ||
                    primary.kind == TcpChainStepKind.Oob ||
                    primary.kind == TcpChainStepKind.Disoob
            ) ->
            "tlsrec_fake"
        else -> primary.kind.wireName
    }
}

fun deriveQuicStrategyFamily(
    desyncUdp: Boolean,
    quicInitialMode: String,
    quicFakeProfile: String,
): String {
    if (!desyncUdp || !quicInitialModeUsesRouting(quicInitialMode)) {
        return "quic_disabled"
    }
    return when (normalizeQuicFakeProfile(quicFakeProfile)) {
        QuicFakeProfileDisabled -> "quic_disabled"
        QuicFakeProfileCompatDefault -> "quic_compat_burst"
        QuicFakeProfileRealisticInitial -> "quic_realistic_burst"
        else -> "quic_burst"
    }
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

fun strategyLaneFamilyLabel(family: String): String =
    when (family.trim().lowercase()) {
        "hostfake" -> "Hostfake"
        "fake_approx" -> "Fake approximation"
        "split" -> "Split"
        "disorder" -> "Disorder"
        "fake" -> "Fake"
        "oob" -> "OOB"
        "disoob" -> "Disorder OOB"
        "tlsrec" -> "TLS record"
        "tlsrandrec" -> "TLS random record"
        "tlsrec_split" -> "TLS record split"
        "tlsrec_disorder" -> "TLS record disorder"
        "tlsrec_fake" -> "TLS record fake"
        "quic_disabled" -> "QUIC disabled"
        "quic_compat_burst" -> "QUIC compat burst"
        "quic_realistic_burst" -> "QUIC realistic burst"
        "quic_burst" -> "QUIC burst"
        "dns_plain_udp" -> "Plain DNS"
        "dns_encrypted_doh" -> "Encrypted DNS (DoH)"
        "dns_encrypted_dot" -> "Encrypted DNS (DoT)"
        "dns_encrypted_dnscrypt" -> "Encrypted DNS (DNSCrypt)"
        "dns_encrypted" -> "Encrypted DNS"
        else -> family.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
