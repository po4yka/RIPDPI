package com.poyka.ripdpi.data

/** Kind-level UDP support shared by profile mapping, normalization and service admission. */
fun relayKindSupportsUdpAssociation(kindId: String): Boolean =
    when (kindId) {
        RelayKindVless,
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
        when (kindId) {
            RelayKindVless -> {
                NativeVlessCapabilities.supportsUdp(RelaySecurityLayerTls, vlessTransport, vlessFlow)
            }

            RelayKindVlessReality -> {
                NativeVlessCapabilities.supportsUdp(RelaySecurityLayerReality, vlessTransport, vlessFlow)
            }

            else -> {
                true
            }
        }

/** Check the cipher as well when importing or resolving a profile with credentials. */
fun relayProfileSupportsUdpAssociation(
    kindId: String,
    udpEnabled: Boolean,
    vlessTransport: String,
    vlessFlow: String,
    shadowsocksMethod: String?,
): Boolean =
    relayProfileSupportsUdpAssociation(kindId, udpEnabled, vlessTransport, vlessFlow) &&
        (kindId != RelayKindShadowsocks || isShadowsocks2022Method(shadowsocksMethod))
