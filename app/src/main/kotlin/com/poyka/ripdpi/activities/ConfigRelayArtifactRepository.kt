package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import javax.inject.Inject

class ConfigRelayArtifactRepository
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialRepository,
    ) {
        suspend fun prepareForPersistence(draft: ConfigDraft): ConfigDraft =
            prepareRelayDraftForPersistence(
                draft = draft,
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
            )

        suspend fun hydrate(draft: ConfigDraft): ConfigDraft {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            val profile = relayProfileStore.load(profileId)
            val credentials = relayCredentialStore.load(profileId)
            return draft.copy(
                relayPresetId = profile?.presetId.orEmpty(),
                relayVlessUuid = credentials?.vlessUuid.orEmpty(),
                relayHysteriaPassword = credentials?.hysteriaPassword.orEmpty(),
                relayHysteriaSalamanderKey = credentials?.hysteriaSalamanderKey.orEmpty(),
                relayTuicUuid = credentials?.tuicUuid.orEmpty(),
                relayTuicPassword = credentials?.tuicPassword.orEmpty(),
                relayShadowTlsPassword = credentials?.shadowTlsPassword.orEmpty(),
                relayNaiveUsername = credentials?.naiveUsername.orEmpty(),
                relayNaivePassword = credentials?.naivePassword.orEmpty(),
                relayCloudflareCredentialsRef =
                    profile
                        ?.cloudflareCredentialsRef
                        ?.ifBlank { draft.relayCloudflareCredentialsRef }
                        ?: draft.relayCloudflareCredentialsRef,
                relayCloudflareTunnelToken = credentials?.cloudflareTunnelToken.orEmpty(),
                relayCloudflareTunnelCredentialsJson = credentials?.cloudflareTunnelCredentialsJson.orEmpty(),
                relayPtBridgeLine = profile?.ptBridgeLine.orEmpty(),
                relayWebTunnelUrl = profile?.ptWebTunnelUrl.orEmpty(),
                relaySnowflakeBrokerUrl =
                    profile
                        ?.ptSnowflakeBrokerUrl
                        ?.ifBlank { DefaultSnowflakeBrokerUrl }
                        ?: DefaultSnowflakeBrokerUrl,
                relaySnowflakeFrontDomain =
                    profile
                        ?.ptSnowflakeFrontDomain
                        ?.ifBlank { DefaultSnowflakeFrontDomain }
                        ?: DefaultSnowflakeFrontDomain,
                relayChainEntryUuid = credentials?.chainEntryUuid.orEmpty(),
                relayChainExitUuid = credentials?.chainExitUuid.orEmpty(),
                relayMasqueAuthMode =
                    normalizeRelayMasqueAuthMode(credentials?.masqueAuthMode)
                        ?: draft.relayMasqueAuthMode,
                relayMasqueAuthToken = credentials?.masqueAuthToken.orEmpty(),
                relayMasqueClientCertificateChainPem = credentials?.masqueClientCertificateChainPem.orEmpty(),
                relayMasqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem.orEmpty(),
            )
        }

        suspend fun persist(draft: ConfigDraft) {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            relayProfileStore.save(draft.toRelayProfileRecord(profileId))
            relayCredentialStore.save(draft.toRelayCredentialRecord(profileId))
        }

        private fun ConfigDraft.toRelayProfileRecord(profileId: String): RelayProfileRecord =
            RelayProfileRecord(
                id = profileId,
                kind = relayKind,
                presetId = relayPresetId,
                server = relayServer,
                serverPort = relayServerPort.toIntOrNull() ?: 443,
                serverName = relayServerName,
                realityPublicKey = relayRealityPublicKey,
                realityShortId = relayRealityShortId,
                vlessTransport = relayVlessTransport,
                xhttpPath = relayXhttpPath,
                xhttpHost = relayXhttpHost,
                cloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(relayCloudflareTunnelMode),
                cloudflarePublishLocalOriginUrl = relayCloudflarePublishLocalOriginUrl,
                cloudflareCredentialsRef =
                    relayCloudflareCredentialsRef.ifBlank {
                        relayProfileId.ifBlank { DefaultRelayProfileId }
                    },
                chainEntryServer = "",
                chainEntryPort = 443,
                chainEntryServerName = "",
                chainEntryPublicKey = "",
                chainEntryShortId = "",
                chainEntryProfileId = if (relayKind == RelayKindChainRelay) relayChainEntryProfileId else "",
                chainExitServer = "",
                chainExitPort = 443,
                chainExitServerName = "",
                chainExitPublicKey = "",
                chainExitShortId = "",
                chainExitProfileId = if (relayKind == RelayKindChainRelay) relayChainExitProfileId else "",
                masqueUrl = relayMasqueUrl,
                masqueUseHttp2Fallback = relayMasqueUseHttp2Fallback,
                masqueCloudflareGeohashEnabled = relayMasqueCloudflareGeohashEnabled,
                tuicZeroRtt = relayTuicZeroRtt,
                tuicCongestionControl = normalizeRelayCongestionControl(relayTuicCongestionControl),
                shadowTlsInnerProfileId = relayShadowTlsInnerProfileId,
                naivePath = relayNaivePath,
                ptBridgeLine = relayPtBridgeLine,
                ptWebTunnelUrl = relayWebTunnelUrl,
                ptSnowflakeBrokerUrl = relaySnowflakeBrokerUrl.ifBlank { DefaultSnowflakeBrokerUrl },
                ptSnowflakeFrontDomain = relaySnowflakeFrontDomain.ifBlank { DefaultSnowflakeFrontDomain },
                udpEnabled = relayUdpEnabled && relayKind.supportsRelayUdpMode(),
                tcpFallbackEnabled = relayMasqueUseHttp2Fallback,
                localSocksPort = relayLocalSocksPort.toIntOrNull() ?: DefaultRelayLocalSocksPort,
                finalmaskType = normalizeRelayFinalmaskType(relayFinalmaskType),
                finalmaskHeaderHex = relayFinalmaskHeaderHex,
                finalmaskTrailerHex = relayFinalmaskTrailerHex,
                finalmaskRandRange = relayFinalmaskRandRange,
                finalmaskSudokuSeed = relayFinalmaskSudokuSeed,
                finalmaskFragmentPackets = relayFinalmaskFragmentPackets.toIntOrNull() ?: 0,
                finalmaskFragmentMinBytes = relayFinalmaskFragmentMinBytes.toIntOrNull() ?: 0,
                finalmaskFragmentMaxBytes = relayFinalmaskFragmentMaxBytes.toIntOrNull() ?: 0,
            )

        private fun ConfigDraft.toRelayCredentialRecord(profileId: String): RelayCredentialRecord =
            RelayCredentialRecord(
                profileId = profileId,
                vlessUuid = relayVlessUuid.ifBlank { null },
                chainEntryUuid = null,
                chainExitUuid = null,
                hysteriaPassword = relayHysteriaPassword.ifBlank { null },
                hysteriaSalamanderKey = relayHysteriaSalamanderKey.ifBlank { null },
                tuicUuid = relayTuicUuid.ifBlank { null },
                tuicPassword = relayTuicPassword.ifBlank { null },
                shadowTlsPassword = relayShadowTlsPassword.ifBlank { null },
                naiveUsername = relayNaiveUsername.ifBlank { null },
                naivePassword = relayNaivePassword.ifBlank { null },
                masqueAuthMode = normalizeRelayMasqueAuthMode(relayMasqueAuthMode),
                masqueAuthToken = relayMasqueAuthToken.ifBlank { null },
                masqueClientCertificateChainPem = relayMasqueClientCertificateChainPem.ifBlank { null },
                masqueClientPrivateKeyPem = relayMasqueClientPrivateKeyPem.ifBlank { null },
                cloudflareTunnelToken = relayCloudflareTunnelToken.ifBlank { null },
                cloudflareTunnelCredentialsJson = relayCloudflareTunnelCredentialsJson.ifBlank { null },
            )

        private fun String.supportsRelayUdpMode(): Boolean =
            this == RelayKindHysteria2 || this == RelayKindMasque || this == RelayKindTuicV5
    }
