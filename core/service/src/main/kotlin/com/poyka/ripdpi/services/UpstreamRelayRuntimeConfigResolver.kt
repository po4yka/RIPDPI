package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

internal interface UpstreamRelayRuntimeConfigResolver {
    suspend fun resolve(
        config: RipDpiRelayConfig,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig,
    ): ResolvedRipDpiRelayConfig
}

internal data class RelayResolverRequest(
    val profileId: String,
    val mergedConfig: RipDpiRelayConfig,
    val credentials: RelayCredentialRecord?,
    val requestedTlsProfile: String,
    val featureFlags: Map<String, Boolean>,
)

internal data class RelayResolverResult(
    val effectiveConfig: RipDpiRelayConfig,
    val effectiveTlsProfile: String,
    val masqueAuthMode: String? = null,
    val privacyPassRuntime: MasquePrivacyPassRuntimeConfig? = null,
    val resolvedChainRelay: ResolvedChainRelayConfig? = null,
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig? = null,
    val masqueCloudflareGeohashHeader: String? = null,
)

internal interface RelayKindResolver {
    fun supports(kind: String): Boolean

    suspend fun resolve(request: RelayResolverRequest): RelayResolverResult
}

@Singleton
internal class DefaultUpstreamRelayRuntimeConfigResolver
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val relayKindResolverRegistry: RelayKindResolverRegistry,
        private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
        private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    ) : UpstreamRelayRuntimeConfigResolver {
        override suspend fun resolve(
            config: RipDpiRelayConfig,
            quicMigrationConfig: OwnedRelayQuicMigrationConfig,
        ): ResolvedRipDpiRelayConfig {
            val profileId = config.profileId.ifBlank { DefaultRelayProfileId }
            val storedProfile = relayProfileStore.load(profileId)
            val requestedTlsProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfileProvider.currentProfile())
            val credentials = relayCredentialStore.load(profileId)
            val resolution =
                relayKindResolverRegistry.resolve(
                    RelayResolverRequest(
                        profileId = profileId,
                        mergedConfig = mergeRelayConfig(config, storedProfile),
                        credentials = credentials,
                        requestedTlsProfile = requestedTlsProfile,
                        featureFlags = runtimeExperimentSelectionProvider.current().featureFlags,
                    ),
                )
            return buildResolvedRelayConfig(
                profileId = profileId,
                resolution = resolution,
                credentials = credentials,
                quicMigrationConfig = quicMigrationConfig,
            )
        }
    }

@Suppress("detekt.LongMethod")
private fun buildResolvedRelayConfig(
    profileId: String,
    resolution: RelayResolverResult,
    credentials: RelayCredentialRecord?,
    quicMigrationConfig: OwnedRelayQuicMigrationConfig,
): ResolvedRipDpiRelayConfig {
    val effectiveConfig = resolution.effectiveConfig
    return ResolvedRipDpiRelayConfig(
        enabled = effectiveConfig.enabled,
        kind = effectiveConfig.kind,
        profileId = profileId,
        outboundBindIp = effectiveConfig.outboundBindIp,
        server = effectiveConfig.server,
        serverPort = effectiveConfig.serverPort,
        serverName = effectiveConfig.serverName,
        realityPublicKey = effectiveConfig.realityPublicKey,
        realityShortId = effectiveConfig.realityShortId,
        vlessTransport = effectiveConfig.vlessTransport,
        xhttpPath = effectiveConfig.xhttpPath,
        xhttpHost = effectiveConfig.xhttpHost,
        cloudflareTunnelMode = effectiveConfig.cloudflareTunnelMode,
        cloudflarePublishLocalOriginUrl = effectiveConfig.cloudflarePublishLocalOriginUrl,
        cloudflareCredentialsRef = effectiveConfig.cloudflareCredentialsRef,
        chainEntryServer = resolution.resolvedChainRelay?.entry?.server ?: effectiveConfig.chainEntryServer,
        chainEntryPort = resolution.resolvedChainRelay?.entry?.serverPort ?: effectiveConfig.chainEntryPort,
        chainEntryServerName = resolution.resolvedChainRelay?.entry?.serverName ?: effectiveConfig.chainEntryServerName,
        chainEntryPublicKey = resolution.resolvedChainRelay?.entry?.publicKey ?: effectiveConfig.chainEntryPublicKey,
        chainEntryShortId = resolution.resolvedChainRelay?.entry?.shortId ?: effectiveConfig.chainEntryShortId,
        chainEntryProfileId = resolution.resolvedChainRelay?.entry?.profileId ?: effectiveConfig.chainEntryProfileId,
        chainExitServer = resolution.resolvedChainRelay?.exit?.server ?: effectiveConfig.chainExitServer,
        chainExitPort = resolution.resolvedChainRelay?.exit?.serverPort ?: effectiveConfig.chainExitPort,
        chainExitServerName = resolution.resolvedChainRelay?.exit?.serverName ?: effectiveConfig.chainExitServerName,
        chainExitPublicKey = resolution.resolvedChainRelay?.exit?.publicKey ?: effectiveConfig.chainExitPublicKey,
        chainExitShortId = resolution.resolvedChainRelay?.exit?.shortId ?: effectiveConfig.chainExitShortId,
        chainExitProfileId = resolution.resolvedChainRelay?.exit?.profileId ?: effectiveConfig.chainExitProfileId,
        masqueUrl = effectiveConfig.masqueUrl,
        masqueUseHttp2Fallback = effectiveConfig.masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = effectiveConfig.masqueCloudflareGeohashEnabled,
        tuicZeroRtt = effectiveConfig.tuicZeroRtt,
        tuicCongestionControl = effectiveConfig.tuicCongestionControl,
        shadowTlsInnerProfileId = effectiveConfig.shadowTlsInnerProfileId,
        shadowTlsInner = resolution.shadowTlsInner,
        naivePath = effectiveConfig.naivePath,
        ptBridgeLine = effectiveConfig.ptBridgeLine,
        ptWebTunnelUrl = effectiveConfig.ptWebTunnelUrl,
        ptSnowflakeBrokerUrl = effectiveConfig.ptSnowflakeBrokerUrl,
        ptSnowflakeFrontDomain = effectiveConfig.ptSnowflakeFrontDomain,
        localSocksHost = effectiveConfig.localSocksHost,
        localSocksPort = effectiveConfig.localSocksPort,
        udpEnabled = effectiveConfig.udpEnabled,
        tcpFallbackEnabled = effectiveConfig.tcpFallbackEnabled,
        quicBindLowPort = quicMigrationConfig.bindLowPort,
        quicMigrateAfterHandshake = quicMigrationConfig.migrateAfterHandshake,
        vlessUuid = credentials?.vlessUuid,
        chainEntryUuid = resolution.resolvedChainRelay?.entry?.uuid ?: credentials?.chainEntryUuid,
        chainExitUuid = resolution.resolvedChainRelay?.exit?.uuid ?: credentials?.chainExitUuid,
        hysteriaPassword = credentials?.hysteriaPassword,
        hysteriaSalamanderKey = credentials?.hysteriaSalamanderKey,
        tuicUuid = credentials?.tuicUuid,
        tuicPassword = credentials?.tuicPassword,
        shadowTlsPassword = credentials?.shadowTlsPassword,
        naiveUsername = credentials?.naiveUsername,
        naivePassword = credentials?.naivePassword,
        tlsFingerprintProfile = resolution.effectiveTlsProfile,
        masqueAuthMode = resolution.masqueAuthMode,
        masqueAuthToken = credentials?.masqueAuthToken,
        masqueClientCertificateChainPem = credentials?.masqueClientCertificateChainPem,
        masqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem,
        masqueCloudflareGeohashHeader = resolution.masqueCloudflareGeohashHeader,
        masquePrivacyPassProviderUrl = resolution.privacyPassRuntime?.providerUrl,
        masquePrivacyPassProviderAuthToken = resolution.privacyPassRuntime?.providerAuthToken,
        cloudflareTunnelToken = credentials?.cloudflareTunnelToken,
        cloudflareTunnelCredentialsJson = credentials?.cloudflareTunnelCredentialsJson,
        appsScriptScriptIds = effectiveConfig.appsScriptScriptIds,
        appsScriptGoogleIp = effectiveConfig.appsScriptGoogleIp,
        appsScriptFrontDomain = effectiveConfig.appsScriptFrontDomain,
        appsScriptSniHosts = effectiveConfig.appsScriptSniHosts,
        appsScriptVerifySsl = effectiveConfig.appsScriptVerifySsl,
        appsScriptParallelRelay = effectiveConfig.appsScriptParallelRelay,
        appsScriptDirectHosts = effectiveConfig.appsScriptDirectHosts,
        appsScriptAuthKey = credentials?.appsScriptAuthKey,
        finalmask =
            ResolvedRelayFinalmaskConfig(
                type = effectiveConfig.finalmask.type,
                headerHex = effectiveConfig.finalmask.headerHex,
                trailerHex = effectiveConfig.finalmask.trailerHex,
                randRange = effectiveConfig.finalmask.randRange,
                sudokuSeed = effectiveConfig.finalmask.sudokuSeed,
                fragmentPackets = effectiveConfig.finalmask.fragmentPackets,
                fragmentMinBytes = effectiveConfig.finalmask.fragmentMinBytes,
                fragmentMaxBytes = effectiveConfig.finalmask.fragmentMaxBytes,
            ),
    )
}

internal fun createDefaultUpstreamRelayRuntimeConfigResolver(
    relayProfileStore: RelayProfileStore,
    relayCredentialStore: RelayCredentialStore,
    cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver,
    masquePrivacyPassProvider: MasquePrivacyPassProvider,
    tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
    runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
): UpstreamRelayRuntimeConfigResolver =
    DefaultUpstreamRelayRuntimeConfigResolver(
        relayProfileStore = relayProfileStore,
        relayCredentialStore = relayCredentialStore,
        relayKindResolverRegistry =
            createDefaultRelayKindResolverRegistry(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                cloudflareMasqueGeohashResolver = cloudflareMasqueGeohashResolver,
                masquePrivacyPassProvider = masquePrivacyPassProvider,
            ),
        tlsFingerprintProfileProvider = tlsFingerprintProfileProvider,
        runtimeExperimentSelectionProvider = runtimeExperimentSelectionProvider,
    )

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UpstreamRelayRuntimeConfigResolverModule {
    @Binds
    @Singleton
    abstract fun bindUpstreamRelayRuntimeConfigResolver(
        resolver: DefaultUpstreamRelayRuntimeConfigResolver,
    ): UpstreamRelayRuntimeConfigResolver
}
