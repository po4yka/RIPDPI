package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeHeaderCustom
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayFinalmaskTypeSudoku
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayPresetSuggestion
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import com.poyka.ripdpi.data.FailureClass as RuntimeFailureClass

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun validateConfigDraft(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean = false,
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

@Suppress("ReturnCount")
internal fun validateRelayFinalmaskDraft(draft: ConfigDraft): String? {
    val finalmaskType = normalizeRelayFinalmaskType(draft.relayFinalmaskType)
    if (finalmaskType == RelayFinalmaskTypeOff) {
        return null
    }
    if (
        draft.relayKind == RelayKindVlessReality &&
        draft.relayVlessTransport != RelayVlessTransportXhttp
    ) {
        return "unsupported"
    }
    if (!draft.supportsFinalmask()) {
        return "unsupported"
    }
    return when (finalmaskType) {
        RelayFinalmaskTypeHeaderCustom -> validateHeaderCustomFinalmaskDraft(draft)
        RelayFinalmaskTypeNoise -> "unsupported"
        RelayFinalmaskTypeSudoku -> if (draft.relayFinalmaskSudokuSeed.isBlank()) "required" else null
        RelayFinalmaskTypeFragment -> validateFragmentFinalmaskDraft(draft)
        else -> null
    }
}

internal fun AppSettings.Builder.applyConfigDraft(draft: ConfigDraft): AppSettings.Builder =
    apply {
        val defaults = AppSettingsSerializer.defaultValue
        setRipdpiMode(draft.mode.preferenceValue)
        setDnsIp(draft.dnsIp.ifBlank { defaults.dnsIp })
        setEnableCmdSettings(draft.useCommandLineSettings)
        setCmdArgs(draft.commandLineArgs)
        setProxyIp(draft.proxyIp.ifBlank { defaults.proxyIp })
        setProxyPort(draft.proxyPort.toIntOrNull() ?: defaults.proxyPort)
        setMaxConnections(draft.maxConnections.toIntOrNull() ?: defaults.maxConnections)
        setBufferSize(draft.bufferSize.toIntOrNull() ?: defaults.bufferSize)
        val chains = draft.resolvedChainSet()
        setStrategyChains(chains.tcpSteps, chains.udpSteps)
        setCustomTtl(draft.defaultTtl.isNotBlank())
        setDefaultTtl(draft.defaultTtl.toIntOrNull() ?: 0)
        setRelayEnabled(draft.relayEnabled && draft.relayKind != RelayKindOff)
        setRelayKind(draft.relayKind)
        setRelayProfileId(draft.relayProfileId.ifBlank { DefaultRelayProfileId })
        setRelayServer(draft.relayServer)
        setRelayServerPort(draft.relayServerPort.toIntOrNull() ?: defaultRelayPort)
        setRelayServerName(
            when (draft.relayKind) {
                RelayKindCloudflareTunnel -> draft.relayServerName.ifBlank { draft.relayServer }
                else -> draft.relayServerName
            },
        )
        setRelayRealityPublicKey(draft.relayRealityPublicKey)
        setRelayRealityShortId(draft.relayRealityShortId)
        setRelayVlessTransport(draft.relayVlessTransport)
        setRelayXhttpPath(draft.relayXhttpPath)
        setRelayXhttpHost(draft.relayXhttpHost)
        setRelayCloudflareTunnelMode(normalizeRelayCloudflareTunnelMode(draft.relayCloudflareTunnelMode))
        setRelayCloudflarePublishLocalOriginUrl(draft.relayCloudflarePublishLocalOriginUrl)
        setRelayCloudflareCredentialsRef(draft.relayCloudflareCredentialsRef)
        setRelayChainEntryServer("")
        setRelayChainEntryPort(defaultRelayPort)
        setRelayChainEntryServerName("")
        setRelayChainEntryPublicKey("")
        setRelayChainEntryShortId("")
        setRelayChainEntryProfileId(if (draft.relayKind == RelayKindChainRelay) draft.relayChainEntryProfileId else "")
        setRelayChainExitServer("")
        setRelayChainExitPort(defaultRelayPort)
        setRelayChainExitServerName("")
        setRelayChainExitPublicKey("")
        setRelayChainExitShortId("")
        setRelayChainExitProfileId(if (draft.relayKind == RelayKindChainRelay) draft.relayChainExitProfileId else "")
        setRelayMasqueUrl(draft.relayMasqueUrl)
        setRelayMasqueUseHttp2Fallback(draft.relayMasqueUseHttp2Fallback)
        setRelayMasqueCloudflareGeohashEnabled(draft.relayMasqueCloudflareGeohashEnabled)
        setRelayTuicZeroRtt(draft.relayTuicZeroRtt)
        setRelayTuicCongestionControl(normalizeRelayCongestionControl(draft.relayTuicCongestionControl))
        setRelayShadowtlsInnerProfileId(draft.relayShadowTlsInnerProfileId)
        setRelayNaivePath(draft.relayNaivePath)
        setRelayLocalSocksHost("127.0.0.1")
        setRelayLocalSocksPort(draft.relayLocalSocksPort.toIntOrNull() ?: DefaultRelayLocalSocksPort)
        setRelayUdpEnabled(
            draft.relayUdpEnabled &&
                (
                    draft.relayKind == RelayKindHysteria2 || draft.relayKind == RelayKindMasque ||
                        draft.relayKind == RelayKindTuicV5
                ),
        )
        setRelayTcpFallbackEnabled(draft.relayMasqueUseHttp2Fallback)
        setRelayFinalmaskType(normalizeRelayFinalmaskType(draft.relayFinalmaskType))
        setRelayFinalmaskHeaderHex(draft.relayFinalmaskHeaderHex)
        setRelayFinalmaskTrailerHex(draft.relayFinalmaskTrailerHex)
        setRelayFinalmaskRandRange(draft.relayFinalmaskRandRange)
        setRelayFinalmaskSudokuSeed(draft.relayFinalmaskSudokuSeed)
        setRelayFinalmaskFragmentPackets(draft.relayFinalmaskFragmentPackets.toIntOrNull() ?: 0)
        setRelayFinalmaskFragmentMinBytes(draft.relayFinalmaskFragmentMinBytes.toIntOrNull() ?: 0)
        setRelayFinalmaskFragmentMaxBytes(draft.relayFinalmaskFragmentMaxBytes.toIntOrNull() ?: 0)
    }

internal suspend fun prepareRelayDraftForPersistence(
    draft: ConfigDraft,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
): ConfigDraft =
    if (draft.relayKind == RelayKindChainRelay) {
        migrateLegacyChainRelayDraft(draft, relayProfileStore, relayCredentialStore)
    } else {
        draft
    }

internal suspend fun migrateLegacyChainRelayDraft(
    draft: ConfigDraft,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
): ConfigDraft {
    if (draft.relayKind != RelayKindChainRelay) {
        return draft
    }
    val chainProfileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
    val entryProfileId =
        draft.relayChainEntryProfileId.ifBlank {
            migrateLegacyChainHopProfile(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                profileId = chainProfileId + LegacyChainEntryProfileSuffix,
                server = draft.relayChainEntryServer,
                serverPort = draft.relayChainEntryPort,
                serverName = draft.relayChainEntryServerName,
                realityPublicKey = draft.relayChainEntryPublicKey,
                realityShortId = draft.relayChainEntryShortId,
                vlessUuid = draft.relayChainEntryUuid,
            )
        }
    val exitProfileId =
        draft.relayChainExitProfileId.ifBlank {
            migrateLegacyChainHopProfile(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                profileId = chainProfileId + LegacyChainExitProfileSuffix,
                server = draft.relayChainExitServer,
                serverPort = draft.relayChainExitPort,
                serverName = draft.relayChainExitServerName,
                realityPublicKey = draft.relayChainExitPublicKey,
                realityShortId = draft.relayChainExitShortId,
                vlessUuid = draft.relayChainExitUuid,
            )
        }
    return draft.copy(
        relayChainEntryServer = "",
        relayChainEntryPort = defaultRelayPort.toString(),
        relayChainEntryServerName = "",
        relayChainEntryPublicKey = "",
        relayChainEntryShortId = "",
        relayChainEntryUuid = "",
        relayChainEntryProfileId = entryProfileId,
        relayChainExitServer = "",
        relayChainExitPort = defaultRelayPort.toString(),
        relayChainExitServerName = "",
        relayChainExitPublicKey = "",
        relayChainExitShortId = "",
        relayChainExitUuid = "",
        relayChainExitProfileId = exitProfileId,
    )
}

internal suspend fun migrateLegacyChainHopProfile(
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
    profileId: String,
    server: String,
    serverPort: String,
    serverName: String,
    realityPublicKey: String,
    realityShortId: String,
    vlessUuid: String,
): String {
    if (server.isBlank()) {
        return ""
    }
    relayProfileStore.save(
        RelayProfileRecord(
            id = profileId,
            kind = RelayKindVlessReality,
            server = server,
            serverPort = serverPort.toIntOrNull() ?: defaultRelayPort,
            serverName = serverName,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            vlessTransport = RelayVlessTransportRealityTcp,
            udpEnabled = false,
        ),
    )
    relayCredentialStore.save(
        RelayCredentialRecord(
            profileId = profileId,
            vlessUuid = vlessUuid.ifBlank { null },
        ),
    )
    return profileId
}
