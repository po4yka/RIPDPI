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

/** Apply transport and flow gates to a projected profile without credentials. */
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
                    NativeVlessCapabilities.supportsUdp(vlessTransport, vlessFlow)
                )
        )

/** Check the cipher as well when importing or resolving a profile with credentials. */
fun relayProfileSupportsUdpAssociation(
    kindId: String,
    udpEnabled: Boolean,
    vlessTransport: String,
    vlessFlow: String,
    shadowsocksMethod: String?,
): Boolean =
    relayProfileSupportsUdpAssociation(kindId, udpEnabled, vlessTransport, vlessFlow) &&
        (
            kindId != RelayKindShadowsocks ||
                shadowsocksMethod?.trim()?.lowercase() in
                setOf("2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")
        )
