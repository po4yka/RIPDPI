package com.poyka.ripdpi.platform

import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.proto.AppSettings

internal const val hasExtendedPacketControls = false

internal fun AppSettings.rawPacketOperationsEnabled(): Boolean = false

internal fun StrategyChainSet.supportedInDistribution(): Boolean =
    tcpSteps.none { it.kind in externalTcpSteps } && udpSteps.none { it.kind == UdpChainStepKind.IpFrag2Udp }

private val externalTcpSteps =
    setOf(
        TcpChainStepKind.SeqOverlap,
        TcpChainStepKind.HostFake,
        TcpChainStepKind.MultiDisorder,
        TcpChainStepKind.FakeRst,
        TcpChainStepKind.IpFrag2,
    )
