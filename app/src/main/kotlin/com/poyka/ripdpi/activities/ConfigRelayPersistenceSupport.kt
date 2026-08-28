package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode

internal fun ConfigDraft.withRelayArtifacts(
    profile: RelayProfileRecord?,
    credentials: RelayCredentialRecord?,
): ConfigDraft =
    copy(
        sourceRelayProfile = profile,
        sourceRelayCredentials = credentials,
        relayPresetId = profile?.presetId.orEmpty(),
        relayVlessUuid = credentials?.vlessUuid.orEmpty(),
        relayAnyTlsPassword = credentials?.anyTlsPassword.orEmpty(),
        relayHysteriaPassword = credentials?.hysteriaPassword.orEmpty(),
        relayHysteriaSalamanderKey = credentials?.hysteriaSalamanderKey.orEmpty(),
        relayTuicUuid = credentials?.tuicUuid.orEmpty(),
        relayTuicPassword = credentials?.tuicPassword.orEmpty(),
        relayShadowTlsPassword = credentials?.shadowTlsPassword.orEmpty(),
        relayTrojanPassword = credentials?.trojanPassword.orEmpty(),
        relayNaiveUsername = credentials?.naiveUsername.orEmpty(),
        relayNaivePassword = credentials?.naivePassword.orEmpty(),
        relayCloudflareCredentialsRef =
            profile
                ?.cloudflareCredentialsRef
                ?.ifBlank { relayCloudflareCredentialsRef }
                ?: relayCloudflareCredentialsRef,
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
                ?: relayMasqueAuthMode,
        relayMasqueAuthToken = credentials?.masqueAuthToken.orEmpty(),
        relayMasqueClientCertificateChainPem = credentials?.masqueClientCertificateChainPem.orEmpty(),
        relayMasqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem.orEmpty(),
    )

internal fun ConfigDraft.toRelayProfileRecord(profileId: String): RelayProfileRecord =
    (matchingSourceRelayProfile(profileId) ?: RelayProfileRecord()).copy(
        id = profileId,
        kind = relayKind,
        presetId = relayPresetId,
        server = relayServer,
        serverPort = relayServerPort.toIntOrNull() ?: defaultRelayPort,
        serverName = if (relayKind == RelayKindAnyTls) relayServerName.ifBlank { relayServer } else relayServerName,
        realityPublicKey = relayRealityPublicKey,
        realityShortId = relayRealityShortId,
        vlessTransport = relayVlessTransport,
        xhttpPath = relayXhttpPath,
        xhttpHost = relayXhttpHost,
        xhttpMode = relayXhttpMode,
        cloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(relayCloudflareTunnelMode),
        cloudflarePublishLocalOriginUrl = relayCloudflarePublishLocalOriginUrl,
        cloudflareCredentialsRef =
            relayCloudflareCredentialsRef.ifBlank {
                relayProfileId.ifBlank { DefaultRelayProfileId }
            },
        chainEntryServer = "",
        chainEntryPort = defaultRelayPort,
        chainEntryServerName = "",
        chainEntryPublicKey = "",
        chainEntryShortId = "",
        chainEntryProfileId = if (relayKind == RelayKindChainRelay) relayChainEntryProfileId else "",
        chainExitServer = "",
        chainExitPort = defaultRelayPort,
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

internal fun ConfigDraft.toRelayCredentialRecord(profileId: String): RelayCredentialRecord =
    (matchingSourceRelayCredentials(profileId) ?: RelayCredentialRecord(profileId)).copy(
        profileId = profileId,
        updatedAtEpochMillis = System.currentTimeMillis(),
        vlessUuid = relayVlessUuid.ifBlank { null },
        chainEntryUuid = null,
        chainExitUuid = null,
        anyTlsPassword = relayAnyTlsPassword.ifBlank { null },
        hysteriaPassword = relayHysteriaPassword.ifBlank { null },
        hysteriaSalamanderKey = relayHysteriaSalamanderKey.ifBlank { null },
        tuicUuid = relayTuicUuid.ifBlank { null },
        tuicPassword = relayTuicPassword.ifBlank { null },
        shadowTlsPassword = relayShadowTlsPassword.ifBlank { null },
        trojanPassword = relayTrojanPassword.ifBlank { null },
        naiveUsername = relayNaiveUsername.ifBlank { null },
        naivePassword = relayNaivePassword.ifBlank { null },
        masqueAuthMode = normalizeRelayMasqueAuthMode(relayMasqueAuthMode),
        masqueAuthToken = relayMasqueAuthToken.ifBlank { null },
        masqueClientCertificateChainPem = relayMasqueClientCertificateChainPem.ifBlank { null },
        masqueClientPrivateKeyPem = relayMasqueClientPrivateKeyPem.ifBlank { null },
        cloudflareTunnelToken = relayCloudflareTunnelToken.ifBlank { null },
        cloudflareTunnelCredentialsJson = relayCloudflareTunnelCredentialsJson.ifBlank { null },
    )

private fun ConfigDraft.matchingSourceRelayProfile(profileId: String): RelayProfileRecord? =
    sourceRelayProfile?.takeIf { it.id == profileId && it.kind == relayKind }

private fun ConfigDraft.matchingSourceRelayCredentials(profileId: String): RelayCredentialRecord? =
    sourceRelayCredentials?.takeIf {
        it.profileId == profileId && matchingSourceRelayProfile(profileId) != null
    }

internal fun ConfigDraft.applyRelayDraftEdit(transform: ConfigDraft.() -> ConfigDraft): ConfigDraft =
    transform().discardReboundRelaySource(this)

private fun ConfigDraft.discardReboundRelaySource(previous: ConfigDraft): ConfigDraft =
    if (relayProfileId != previous.relayProfileId || relayKind != previous.relayKind) {
        copy(sourceRelayProfile = null, sourceRelayCredentials = null)
    } else {
        this
    }

internal suspend fun prepareRelayDraftForPersistence(
    draft: ConfigDraft,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialRepository,
    profileMutations: ProfileMutationCoordinator? = null,
): ConfigDraft =
    if (draft.relayKind == RelayKindChainRelay) {
        migrateLegacyChainRelayDraft(draft, relayProfileStore, relayCredentialStore, profileMutations)
    } else {
        draft
    }

internal suspend fun migrateLegacyChainRelayDraft(
    draft: ConfigDraft,
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialRepository,
    profileMutations: ProfileMutationCoordinator? = null,
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
                profileMutations = profileMutations,
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
                profileMutations = profileMutations,
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
    relayCredentialStore: RelayCredentialRepository,
    profileId: String,
    server: String,
    serverPort: String,
    serverName: String,
    realityPublicKey: String,
    realityShortId: String,
    vlessUuid: String,
    profileMutations: ProfileMutationCoordinator? = null,
): String {
    if (server.isBlank()) {
        return ""
    }
    val profile =
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
        )
    val credentials =
        RelayCredentialRecord(
            profileId = profileId,
            vlessUuid = vlessUuid.ifBlank { null },
        )
    if (profileMutations == null) {
        relayProfileStore.save(profile)
        relayCredentialStore.save(credentials)
    } else {
        profileMutations.upsertRelay(profile, credentials, enabled = false, select = false)
    }
    return profileId
}
