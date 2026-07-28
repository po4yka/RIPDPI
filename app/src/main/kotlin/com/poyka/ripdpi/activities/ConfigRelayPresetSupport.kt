package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.services.relayTransportCapabilities

internal fun ConfigDraft.applyRelayPresetDefinition(preset: RelayPresetDefinition): ConfigDraft =
    copy(
        relayEnabled = true,
        relayKind = preset.relayKind,
        relayPresetId = preset.id,
        relayChainEntryProfileId =
            if (preset.relayKind == RelayKindChainRelay) {
                preset.chainEntryProfileId
            } else {
                ""
            },
        relayChainExitProfileId =
            if (preset.relayKind == RelayKindChainRelay) {
                preset.chainExitProfileId
            } else {
                ""
            },
        relayChainEntryServer = "",
        relayChainEntryPort = defaultRelayPort.toString(),
        relayChainEntryServerName = "",
        relayChainEntryPublicKey = "",
        relayChainEntryShortId = "",
        relayChainEntryUuid = "",
        relayChainExitServer = "",
        relayChainExitPort = defaultRelayPort.toString(),
        relayChainExitServerName = "",
        relayChainExitPublicKey = "",
        relayChainExitShortId = "",
        relayChainExitUuid = "",
        relayShadowTlsInnerProfileId =
            if (preset.relayKind == RelayKindShadowTlsV3) {
                preset.shadowTlsInnerProfileId
            } else {
                ""
            },
        relayTuicZeroRtt = if (preset.relayKind == RelayKindTuicV5) preset.tuicZeroRtt else relayTuicZeroRtt,
        relayTuicCongestionControl =
            if (preset.relayKind == RelayKindTuicV5) {
                normalizeRelayCongestionControl(preset.tuicCongestionControl)
            } else {
                relayTuicCongestionControl
            },
        relayNaivePath = if (preset.relayKind == RelayKindNaiveProxy) preset.naivePath else "",
        relayUdpEnabled = preset.udpEnabled && preset.relayKind.supportsRelayUdpMode(),
    )

internal fun String.supportsRelayUdpMode(): Boolean = relayTransportCapabilities(this)?.udpAssociate == true
