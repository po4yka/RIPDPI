package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFinalmaskConfig
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

private fun buildResolvedRelayConfig(
    profileId: String,
    resolution: RelayResolverResult,
    credentials: RelayCredentialRecord?,
    quicMigrationConfig: OwnedRelayQuicMigrationConfig,
): ResolvedRipDpiRelayConfig =
    ResolvedRelayConfigBuilder(
        profileId = profileId,
        resolution = resolution,
        credentials = credentials,
        quicMigrationConfig = quicMigrationConfig,
    ).build()

private class ResolvedRelayConfigBuilder(
    private val profileId: String,
    private val resolution: RelayResolverResult,
    private val credentials: RelayCredentialRecord?,
    private val quicMigrationConfig: OwnedRelayQuicMigrationConfig,
) {
    private val effectiveConfig = resolution.effectiveConfig

    fun build(): ResolvedRipDpiRelayConfig =
        baseProjection()
            .withResolvedChainRelay()
            .withQuicMigration()
            .withCredentialProjection()
            .withMasqueProjection()

    private fun baseProjection(): ResolvedRipDpiRelayConfig =
        ResolvedRipDpiRelayConfig(
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
            chainEntryServer = effectiveConfig.chainEntryServer,
            chainEntryPort = effectiveConfig.chainEntryPort,
            chainEntryServerName = effectiveConfig.chainEntryServerName,
            chainEntryPublicKey = effectiveConfig.chainEntryPublicKey,
            chainEntryShortId = effectiveConfig.chainEntryShortId,
            chainEntryProfileId = effectiveConfig.chainEntryProfileId,
            chainExitServer = effectiveConfig.chainExitServer,
            chainExitPort = effectiveConfig.chainExitPort,
            chainExitServerName = effectiveConfig.chainExitServerName,
            chainExitPublicKey = effectiveConfig.chainExitPublicKey,
            chainExitShortId = effectiveConfig.chainExitShortId,
            chainExitProfileId = effectiveConfig.chainExitProfileId,
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
            appsScriptScriptIds = effectiveConfig.appsScriptScriptIds,
            appsScriptGoogleIp = effectiveConfig.appsScriptGoogleIp,
            appsScriptFrontDomain = effectiveConfig.appsScriptFrontDomain,
            appsScriptSniHosts = effectiveConfig.appsScriptSniHosts,
            appsScriptVerifySsl = effectiveConfig.appsScriptVerifySsl,
            appsScriptParallelRelay = effectiveConfig.appsScriptParallelRelay,
            appsScriptDirectHosts = effectiveConfig.appsScriptDirectHosts,
            finalmask = effectiveConfig.finalmask.toResolvedFinalmaskConfig(),
        )

    private fun ResolvedRipDpiRelayConfig.withResolvedChainRelay(): ResolvedRipDpiRelayConfig {
        val chainRelay = resolution.resolvedChainRelay ?: return this
        return copy(
            chainEntryServer = chainRelay.entry.server,
            chainEntryPort = chainRelay.entry.serverPort,
            chainEntryServerName = chainRelay.entry.serverName,
            chainEntryPublicKey = chainRelay.entry.publicKey,
            chainEntryShortId = chainRelay.entry.shortId,
            chainEntryProfileId = chainRelay.entry.profileId,
            chainExitServer = chainRelay.exit.server,
            chainExitPort = chainRelay.exit.serverPort,
            chainExitServerName = chainRelay.exit.serverName,
            chainExitPublicKey = chainRelay.exit.publicKey,
            chainExitShortId = chainRelay.exit.shortId,
            chainExitProfileId = chainRelay.exit.profileId,
        )
    }

    private fun ResolvedRipDpiRelayConfig.withQuicMigration(): ResolvedRipDpiRelayConfig =
        copy(
            quicBindLowPort = quicMigrationConfig.bindLowPort,
            quicMigrateAfterHandshake = quicMigrationConfig.migrateAfterHandshake,
        )

    private fun ResolvedRipDpiRelayConfig.withCredentialProjection(): ResolvedRipDpiRelayConfig =
        copy(
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
            masqueAuthToken = credentials?.masqueAuthToken,
            masqueClientCertificateChainPem = credentials?.masqueClientCertificateChainPem,
            masqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem,
            cloudflareTunnelToken = credentials?.cloudflareTunnelToken,
            cloudflareTunnelCredentialsJson = credentials?.cloudflareTunnelCredentialsJson,
            appsScriptAuthKey = credentials?.appsScriptAuthKey,
        )

    private fun ResolvedRipDpiRelayConfig.withMasqueProjection(): ResolvedRipDpiRelayConfig =
        copy(
            tlsFingerprintProfile = resolution.effectiveTlsProfile,
            masqueAuthMode = resolution.masqueAuthMode,
            masqueCloudflareGeohashHeader = resolution.masqueCloudflareGeohashHeader,
            masquePrivacyPassProviderUrl = resolution.privacyPassRuntime?.providerUrl,
            masquePrivacyPassProviderAuthToken = resolution.privacyPassRuntime?.providerAuthToken,
        )
}

private fun RipDpiRelayFinalmaskConfig.toResolvedFinalmaskConfig(): ResolvedRelayFinalmaskConfig =
    ResolvedRelayFinalmaskConfig(
        type = type,
        headerHex = headerHex,
        trailerHex = trailerHex,
        randRange = randRange,
        sudokuSeed = sudokuSeed,
        fragmentPackets = fragmentPackets,
        fragmentMinBytes = fragmentMinBytes,
        fragmentMaxBytes = fragmentMaxBytes,
    )

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
