package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun validateConfigDraft(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean = false,
    relayProfiles: List<RelayProfileRecord> = emptyList(),
): ImmutableMap<String, String> =
    buildMap {
        if (!checkIp(draft.proxyIp)) {
            put(ConfigFieldProxyIp, "invalid_proxy_ip")
        }

        if (!validatePort(draft.proxyPort)) {
            put(ConfigFieldProxyPort, "invalid_port")
        }

        if (!validateIntRange(draft.maxConnections, 1, Short.MAX_VALUE.toInt())) {
            put(ConfigFieldMaxConnections, "out_of_range")
        }

        if (!validateIntRange(draft.bufferSize, 1, Int.MAX_VALUE / bufferSizeDiv)) {
            put(ConfigFieldBufferSize, "out_of_range")
        }

        if (draft.defaultTtl.isNotEmpty() && !validateIntRange(draft.defaultTtl, 0, defaultTtlMax)) {
            put(ConfigFieldDefaultTtl, "out_of_range")
        }

        if (draft.relayEnabled && !draft.useCommandLineSettings) {
            if (!validatePort(draft.relayLocalSocksPort)) {
                put(ConfigFieldRelayLocalSocksPort, "invalid_port")
            }
            when (draft.relayKind) {
                RelayKindVlessReality -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
                    val isVlessRealityIncomplete =
                        draft.relayServerName.isBlank() ||
                            draft.relayRealityPublicKey.isBlank() ||
                            draft.relayRealityShortId.isBlank() ||
                            draft.relayVlessUuid.isBlank()
                    if (isVlessRealityIncomplete) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayVlessTransport == RelayVlessTransportXhttp && draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindCloudflareTunnel -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    when (normalizeRelayCloudflareTunnelMode(draft.relayCloudflareTunnelMode)) {
                        RelayCloudflareTunnelModeConsumeExisting -> {
                            if (draft.relayVlessUuid.isBlank()) {
                                put(ConfigFieldRelayCredentials, "required")
                            }
                        }

                        RelayCloudflareTunnelModePublishLocalOrigin -> {
                            if (draft.relayCloudflarePublishLocalOriginUrl.isBlank()) {
                                put(ConfigFieldRelayCloudflarePublishOrigin, "required")
                            }
                            if (
                                draft.relayCloudflareTunnelToken.isBlank() &&
                                draft.relayCloudflareTunnelCredentialsJson.isBlank()
                            ) {
                                put(ConfigFieldRelayCredentials, "required")
                            }
                        }
                    }
                    if (draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindHysteria2 -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
                    if (draft.relayServerName.isBlank() || draft.relayHysteriaPassword.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                }

                RelayKindTuicV5 -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
                    if (
                        draft.relayServerName.isBlank() ||
                        draft.relayTuicUuid.isBlank() ||
                        draft.relayTuicPassword.isBlank()
                    ) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                }

                RelayKindTrojan -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
                    if (draft.relayServerName.isBlank() || draft.relayTrojanPassword.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                }

                RelayKindShadowTlsV3 -> {
                    if (draft.relayShadowTlsInnerProfileId.isBlank() || draft.relayShadowTlsPassword.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindNaiveProxy -> {
                    if (draft.relayServer.isBlank()) put(ConfigFieldRelayServer, "required")
                    if (!validatePort(draft.relayServerPort)) put(ConfigFieldRelayServerPort, "invalid_port")
                    if (draft.relayNaivePath.isNotBlank() && !draft.relayNaivePath.startsWith("/")) {
                        put(ConfigFieldRelayNaivePath, "absolute_path")
                    }
                    if (
                        draft.relayServerName.isBlank() ||
                        draft.relayNaiveUsername.isBlank() ||
                        draft.relayNaivePassword.isBlank()
                    ) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindSnowflake -> {
                    if (draft.relaySnowflakeBrokerUrl.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindWebTunnel -> {
                    if (draft.relayWebTunnelUrl.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindObfs4 -> {
                    if (draft.relayPtBridgeLine.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    if (draft.relayUdpEnabled) {
                        put(ConfigFieldRelayCredentials, "unsupported")
                    }
                }

                RelayKindChainRelay -> {
                    val hasEntryReference =
                        draft.relayChainEntryProfileId.isNotBlank() || draft.relayChainEntryServer.isNotBlank()
                    val hasExitReference =
                        draft.relayChainExitProfileId.isNotBlank() || draft.relayChainExitServer.isNotBlank()
                    if (!hasEntryReference || !hasExitReference) {
                        put(ConfigFieldRelayServer, "required")
                    }
                    val isChainRelayIncomplete =
                        (
                            draft.relayChainEntryProfileId.isBlank() &&
                                (
                                    draft.relayChainEntryServerName.isBlank() ||
                                        draft.relayChainEntryPublicKey.isBlank() ||
                                        draft.relayChainEntryShortId.isBlank() ||
                                        draft.relayChainEntryUuid.isBlank()
                                )
                        ) ||
                            (
                                draft.relayChainExitProfileId.isBlank() &&
                                    (
                                        draft.relayChainExitServerName.isBlank() ||
                                            draft.relayChainExitPublicKey.isBlank() ||
                                            draft.relayChainExitShortId.isBlank() ||
                                            draft.relayChainExitUuid.isBlank()
                                    )
                            )
                    if (isChainRelayIncomplete) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    validateChainRelayProfileSelection(draft, relayProfiles)?.let {
                        put(ConfigFieldRelayChain, it)
                    }
                }

                RelayKindMasque -> {
                    if (draft.relayMasqueUrl.isBlank()) {
                        put(ConfigFieldRelayCredentials, "required")
                    }
                    when (normalizeRelayMasqueAuthMode(draft.relayMasqueAuthMode)) {
                        RelayMasqueAuthModeBearer,
                        RelayMasqueAuthModePreshared,
                        -> {
                            if (draft.relayMasqueAuthToken.isBlank()) {
                                put(ConfigFieldRelayCredentials, "required")
                            }
                        }

                        RelayMasqueAuthModePrivacyPass -> {
                            if (!supportsMasquePrivacyPass) {
                                put(ConfigFieldRelayCredentials, "unsupported")
                            }
                        }

                        RelayMasqueAuthModeCloudflareMtls -> {
                            if (
                                draft.relayMasqueClientCertificateChainPem.isBlank() ||
                                draft.relayMasqueClientPrivateKeyPem.isBlank()
                            ) {
                                put(ConfigFieldRelayCredentials, "required")
                            }
                        }

                        else -> {
                            put(ConfigFieldRelayCredentials, "required")
                        }
                    }
                }
            }

            validateRelayFinalmaskDraft(draft)?.let { put(ConfigFieldRelayFinalmask, it) }

            if (draft.relayUdpEnabled && !draft.supportsUdpRelay()) {
                put(ConfigFieldRelayCredentials, "unsupported")
            }
        }

        if (!draft.useCommandLineSettings) {
            val chainValidation =
                parseStrategyChainDsl(draft.chainDsl).map { chain ->
                    validateStrategyChainUsage(
                        tcpSteps = chain.tcpSteps,
                        udpSteps = chain.udpSteps,
                        mode = draft.mode,
                        useCommandLineSettings = draft.useCommandLineSettings,
                    )
                }
            if (chainValidation.isFailure) {
                put(ConfigFieldStrategyChain, "invalid_chain")
            }
        }
    }.toImmutableMap()

private fun validateChainRelayProfileSelection(
    draft: ConfigDraft,
    relayProfiles: List<RelayProfileRecord>,
): String? =
    when {
        draft.relayChainEntryProfileId.isBlank() || draft.relayChainExitProfileId.isBlank() -> {
            "required"
        }

        draft.relayChainEntryProfileId == draft.relayChainExitProfileId -> {
            "same_hop"
        }

        relayProfiles.isEmpty() -> {
            "required"
        }

        else -> {
            validateResolvedChainProfiles(
                entryId = draft.relayChainEntryProfileId,
                exitId = draft.relayChainExitProfileId,
                relayProfiles = relayProfiles,
            )
        }
    }

private fun validateResolvedChainProfiles(
    entryId: String,
    exitId: String,
    relayProfiles: List<RelayProfileRecord>,
): String? {
    val byId = relayProfiles.associateBy { it.id }
    val entry = byId[entryId]
    val exit = byId[exitId]
    return when {
        entry == null || exit == null -> "required"
        !entry.isSupportedChainHop() || !exit.isSupportedChainHop() -> "unsupported"
        else -> null
    }
}

private fun RelayProfileRecord.isSupportedChainHop(): Boolean = kind != RelayKindOff && kind != RelayKindChainRelay
