// ROADMAP-architecture-refactor W1 -- carry-over until Phase 1 slice 1.5
@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.data

internal const val MaxSeqOverlapSize = 32
internal const val MaxHostLabelLength = 16
internal const val MaxDnsTlsRandRecFragmentSize = 4096

fun StrategyChainSet.usesIpFragmentation(): Boolean = usesIpFragmentation(tcpSteps = tcpSteps, udpSteps = udpSteps)

fun usesIpFragmentation(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): Boolean =
    tcpSteps.any { it.kind == TcpChainStepKind.IpFrag2 } ||
        udpSteps.any { it.kind == UdpChainStepKind.IpFrag2Udp }

fun validateStrategyChainUsage(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
    mode: Mode,
    useCommandLineSettings: Boolean,
) {
    validateTcpChain(tcpSteps)
    validateUdpChain(udpSteps)
    if (usesIpFragmentation(tcpSteps = tcpSteps, udpSteps = udpSteps)) {
        require(mode == Mode.VPN) { "ipfrag2 is only supported in VPN mode" }
        require(!useCommandLineSettings) { "ipfrag2 is not supported while command line settings are enabled" }
    }
    require(
        tcpSteps.none {
            it.ipv6ExtensionProfile != StrategyIpv6ExtensionProfileNone && it.kind != TcpChainStepKind.IpFrag2
        },
    ) { "IPv6 extension profiles are only supported for tcp ipfrag2" }
    require(
        udpSteps.none {
            it.ipv6ExtensionProfile != StrategyIpv6ExtensionProfileNone && it.kind != UdpChainStepKind.IpFrag2Udp
        },
    ) { "IPv6 extension profiles are only supported for udp ipfrag2_udp" }
}

internal fun validateTcpChain(steps: List<TcpChainStepModel>) {
    var sawSendStep = false
    var sawIpFrag2 = false
    var sawSeqOverlap = false
    var sendStepCount = 0
    var multidisorderCount = 0
    steps.forEachIndexed { index, step ->
        when (step.kind) {
            TcpChainStepKind.TlsRec,
            TcpChainStepKind.TlsRandRec,
            -> {
                require(!sawSendStep) { "${step.kind.wireName} must be declared before tcp send steps" }
            }

            else -> {
                sawSendStep = true
                if (step.kind == TcpChainStepKind.SeqOverlap) {
                    require(!sawSeqOverlap) { "seqovl must appear at most once per tcp chain" }
                    require(sendStepCount == 0) { "seqovl must be the first tcp send step" }
                    sawSeqOverlap = true
                }
                if (step.kind == TcpChainStepKind.MultiDisorder) {
                    multidisorderCount += 1
                } else {
                    require(multidisorderCount == 0) {
                        "multidisorder must be the only tcp send step family"
                    }
                }
                if (step.kind == TcpChainStepKind.IpFrag2) {
                    sawIpFrag2 = true
                    require(index == steps.lastIndex) { "ipfrag2 must be the only tcp send step" }
                } else {
                    require(!sawIpFrag2) { "ipfrag2 must be the only tcp send step" }
                }
                sendStepCount += 1
            }
        }
        if (step.kind == TcpChainStepKind.FakeSplit || step.kind == TcpChainStepKind.FakeDisorder) {
            require(index == steps.lastIndex) {
                "${step.kind.wireName} must be the last tcp send step"
            }
        }
        validateTcpStepOptions(step)
    }
    if (multidisorderCount > 0) {
        require(sendStepCount == multidisorderCount) {
            "multidisorder must be the only tcp send step family"
        }
        require(multidisorderCount >= 2) {
            "multidisorder must declare at least two markers"
        }
    }
}

internal fun validateUdpChain(steps: List<UdpChainStepModel>) {
    val normalized = steps.map(::normalizeUdpChainStepModel)
    normalized.forEach(::validateUdpStepOptions)
    if (normalized.any { it.kind == UdpChainStepKind.IpFrag2Udp }) {
        require(normalized.size == 1) { "ipfrag2_udp must be the only udp chain step" }
    }
}

internal fun validateTcpStepOptions(step: TcpChainStepModel) {
    validateTcpFlagMasks(
        kind = step.kind,
        tcpFlagsSet = step.tcpFlagsSet,
        tcpFlagsUnset = step.tcpFlagsUnset,
        tcpFlagsOrigSet = step.tcpFlagsOrigSet,
        tcpFlagsOrigUnset = step.tcpFlagsOrigUnset,
    )
    require(step.kind.supportsAdaptiveMarker || !isAdaptiveOffsetExpression(step.marker)) {
        "${step.kind.wireName} must not declare an adaptive marker"
    }
    require(step.kind != TcpChainStepKind.HostFake || !isAdaptiveOffsetExpression(step.midhostMarker)) {
        "hostfake must not declare an adaptive midhost marker"
    }
    if (step.kind.supportsFakeOrdering) {
        require(step.fakeOrder in SupportedFakeOrderValues) {
            "${step.kind.wireName} altorder must be 0, 1, 2, or 3"
        }
        require(
            step.fakeSeqMode == FakeSeqModeDuplicate || step.fakeSeqMode == FakeSeqModeSequential,
        ) { "${step.kind.wireName} seqmode must be duplicate or sequential" }
        require(
            step.kind != TcpChainStepKind.HostFake || step.fakeOrder == FakeOrderDefault ||
                step.midhostMarker.isNotBlank(),
        ) { "hostfake altorder requires midhost" }
    } else {
        require(step.fakeOrder == FakeOrderDefault) { "${step.kind.wireName} must not declare fakeOrder" }
        require(step.fakeSeqMode == FakeSeqModeDuplicate) { "${step.kind.wireName} must not declare fakeSeqMode" }
    }
    when (step.kind) {
        TcpChainStepKind.SeqOverlap -> {
            require(step.overlapSize in 1..MaxSeqOverlapSize) { "seqovl overlap must be between 1 and 32" }
            require(isValidSeqOverlapFakeMode(step.fakeMode)) {
                "seqovl fakeMode must be profile or rand"
            }
            require(step.fragmentCount == 0) { "seqovl must not declare fragmentCount" }
            require(step.minFragmentSize == 0) { "seqovl must not declare minFragmentSize" }
            require(step.maxFragmentSize == 0) { "seqovl must not declare maxFragmentSize" }
        }

        TcpChainStepKind.TlsRandRec -> {
            require(step.fragmentCount in 2..MaxHostLabelLength) { "tlsrandrec count must be between 2 and 16" }
            require(step.minFragmentSize in 1..MaxDnsTlsRandRecFragmentSize) {
                "tlsrandrec min must be between 1 and 4096"
            }
            require(step.maxFragmentSize in step.minFragmentSize..MaxDnsTlsRandRecFragmentSize) {
                "tlsrandrec max must be between min and 4096"
            }
            require(step.overlapSize == 0) { "tlsrandrec must not declare overlapSize" }
            require(step.ipv6ExtensionProfile == StrategyIpv6ExtensionProfileNone) {
                "tlsrandrec must not declare ipv6ExtensionProfile"
            }
        }

        else -> {
            require(step.overlapSize == 0) { "${step.kind.wireName} must not declare overlapSize" }
            require(step.fragmentCount == 0) { "${step.kind.wireName} must not declare fragmentCount" }
            require(step.minFragmentSize == 0) { "${step.kind.wireName} must not declare minFragmentSize" }
            require(step.maxFragmentSize == 0) { "${step.kind.wireName} must not declare maxFragmentSize" }
            require(
                step.kind == TcpChainStepKind.IpFrag2 || step.ipv6ExtensionProfile == StrategyIpv6ExtensionProfileNone,
            ) { "${step.kind.wireName} must not declare ipv6ExtensionProfile" }
        }
    }
}

internal fun validateUdpStepOptions(step: UdpChainStepModel) {
    validateNoTcpStatePredicates(step.activationFilter, "${step.kind.wireName} activationFilter")
    when (step.kind) {
        UdpChainStepKind.FakeBurst -> {
            require(step.count >= 0) { "fake_burst count must be non-negative" }
            require(step.splitBytes == 0) { "fake_burst must not declare splitBytes" }
            require(step.ipv6ExtensionProfile == StrategyIpv6ExtensionProfileNone) {
                "fake_burst must not declare ipv6ExtensionProfile"
            }
        }

        UdpChainStepKind.DummyPrepend,
        UdpChainStepKind.QuicSniSplit,
        UdpChainStepKind.QuicFakeVersion,
        UdpChainStepKind.QuicCryptoSplit,
        UdpChainStepKind.QuicPaddingLadder,
        UdpChainStepKind.QuicCidChurn,
        UdpChainStepKind.QuicPacketNumberGap,
        UdpChainStepKind.QuicVersionNegotiationDecoy,
        UdpChainStepKind.QuicMultiInitialRealistic,
        -> {
            require(step.count >= 0) { "${step.kind.wireName} count must be non-negative" }
            require(step.splitBytes == 0) { "${step.kind.wireName} must not declare splitBytes" }
            require(step.ipv6ExtensionProfile == StrategyIpv6ExtensionProfileNone) {
                "${step.kind.wireName} must not declare ipv6ExtensionProfile"
            }
        }

        UdpChainStepKind.IpFrag2Udp -> {
            require(step.count == 0) { "ipfrag2_udp must not declare count" }
            require(step.splitBytes > 0) { "ipfrag2_udp splitBytes must be greater than zero" }
        }
    }
}
