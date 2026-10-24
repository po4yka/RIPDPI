package com.poyka.ripdpi.data

/** Kind-level UDP support shared by profile mapping, normalization and service admission. */
fun relayKindSupportsUdpAssociation(kindId: String): Boolean =
    when (kindId) {
        RelayKindVlessReality,
        RelayKindHysteria2,
        RelayKindMasque,
        RelayKindAnyTls,
        RelayKindShadowsocks,
        RelayKindTrojan,
        RelayKindTuicV5,
        -> true

        else -> false
    }

/** Apply the explicit UDP toggle and the native Reality transport/flow limits. */
fun relayProfileSupportsUdpAssociation(
    kindId: String,
    udpEnabled: Boolean,
    vlessTransport: String,
    vlessFlow: String,
): Boolean =
    udpEnabled && relayKindSupportsUdpAssociation(kindId) &&
        (
            kindId != RelayKindVlessReality ||
                (
                    vlessTransport == RelayVlessTransportRealityTcp &&
                        vlessFlow.trim() in setOf(RelayVlessFlowVision, RelayVlessFlowVisionUdp443)
                )
        )
