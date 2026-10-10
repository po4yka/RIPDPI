package com.poyka.ripdpi.diagnostics

internal fun ipFamilyConnectionLane(details: List<ProbeDetail>): ConnectionStageLane {
    val evidence = parseIpFamilyProbeEvidence(details)
    val tcpState =
        when (evidence?.status) {
            IpProbeStatus.REACHABLE -> ConnectionStageState.SUCCEEDED
            IpProbeStatus.FAILED -> ConnectionStageState.FAILED
            IpProbeStatus.TIMEOUT -> ConnectionStageState.TIMED_OUT
            IpProbeStatus.CANCELLED -> ConnectionStageState.CANCELLED
            IpProbeStatus.UNSUPPORTED -> ConnectionStageState.NOT_APPLICABLE
            else -> ConnectionStageState.UNKNOWN
        }
    val tcp =
        ConnectionStageMeasurement(
            ConnectionStage.TCP,
            when {
                evidence == null -> ConnectionStageState.UNKNOWN
                evidence.stage == IpProbeStage.TCP_CONNECT -> tcpState
                else -> ConnectionStageState.NOT_REACHED
            },
            durationMs =
                evidence?.durationMs?.takeIf {
                    evidence.stage == IpProbeStage.TCP_CONNECT && evidence.family != IpProbeFamily.NAT64
                },
            elapsedMs = evidence?.durationMs?.takeIf { evidence.stage == IpProbeStage.TCP_CONNECT },
        )
    val dns =
        when (evidence?.discoveryStatus) {
            Nat64DiscoveryStatus.DISCOVERED -> ConnectionStageState.SUCCEEDED
            Nat64DiscoveryStatus.TIMEOUT -> ConnectionStageState.TIMED_OUT
            Nat64DiscoveryStatus.FAILED, Nat64DiscoveryStatus.INVALID -> ConnectionStageState.FAILED
            Nat64DiscoveryStatus.NO_PREFIX -> ConnectionStageState.OBSERVED
            else -> ConnectionStageState.UNKNOWN
        }
    return connectionLane(
        ConnectionLaneKind.TCP,
        if (evidence?.family == IpProbeFamily.NAT64) {
            listOf(ConnectionStageMeasurement(ConnectionStage.DNS, dns), tcp)
        } else {
            listOf(tcp)
        },
        applicable =
            if (evidence?.family ==
                IpProbeFamily.NAT64
            ) {
                setOf(ConnectionStage.DNS, ConnectionStage.TCP)
            } else {
                setOf(ConnectionStage.TCP)
            },
        unverified = evidence?.reason == "network_scope_unverified",
    )
}
