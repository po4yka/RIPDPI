package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RelayAppsScriptSection
import com.poyka.ripdpi.core.RelayChainSection
import com.poyka.ripdpi.core.RelayCloudflareSection
import com.poyka.ripdpi.core.RelayCommonSection
import com.poyka.ripdpi.core.RelayConfigSections
import com.poyka.ripdpi.core.RelayHysteria2Section
import com.poyka.ripdpi.core.RelayMasqueSection
import com.poyka.ripdpi.core.RelayPluggableTransportSection
import com.poyka.ripdpi.core.RelayShadowTlsSection
import com.poyka.ripdpi.core.RelayShadowsocksSection
import com.poyka.ripdpi.core.RelayTrojanSection
import com.poyka.ripdpi.core.RelayTuicSection
import com.poyka.ripdpi.core.RelayVlessSection
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFinalmaskConfig
import com.poyka.ripdpi.core.toResolvedConfig
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
    private val chainRelay = resolution.resolvedChainRelay

    /**
     * Assemble the flat relay wire DTO by first building each concern's
     * [RelayConfigSections] slice — every projection (credentials, resolved
     * chain hops, QUIC migration, MASQUE / TLS) is applied as the section is
     * built — then flattening with [toResolvedConfig]. The flatten step is a
     * pure, rename-free 1:1 field mapping, so the wire JSON is unchanged.
     */
    fun build(): ResolvedRipDpiRelayConfig =
        RelayConfigSections(
            common = commonSection(),
            vless = vlessSection(),
            chain = chainSection(),
            masque = masqueSection(),
            tuic = tuicSection(),
            shadowTls = shadowTlsSection(),
            trojan = trojanSection(),
            shadowsocks = shadowsocksSection(),
            hysteria2 = hysteria2Section(),
            pluggableTransport = pluggableTransportSection(),
            cloudflare = cloudflareSection(),
            appsScript = appsScriptSection(),
            finalmask = effectiveConfig.finalmask.toResolvedFinalmaskConfig(),
        ).toResolvedConfig()

    private fun commonSection(): RelayCommonSection =
        RelayCommonSection(
            enabled = effectiveConfig.enabled,
            kind = effectiveConfig.kind,
            profileId = profileId,
            outboundBindIp = effectiveConfig.outboundBindIp,
            server = effectiveConfig.server,
            serverPort = effectiveConfig.serverPort,
            serverName = effectiveConfig.serverName,
            localSocksHost = effectiveConfig.localSocksHost,
            localSocksPort = effectiveConfig.localSocksPort,
            udpEnabled = effectiveConfig.udpEnabled,
            tcpFallbackEnabled = effectiveConfig.tcpFallbackEnabled,
            quicBindLowPort = quicMigrationConfig.bindLowPort,
            quicMigrateAfterHandshake = quicMigrationConfig.migrateAfterHandshake,
            tlsFingerprintProfile = resolution.effectiveTlsProfile,
        )

    private fun vlessSection(): RelayVlessSection =
        RelayVlessSection(
            realityPublicKey = effectiveConfig.realityPublicKey,
            realityShortId = effectiveConfig.realityShortId,
            vlessTransport = effectiveConfig.vlessTransport,
            xhttpPath = effectiveConfig.xhttpPath,
            xhttpHost = effectiveConfig.xhttpHost,
            vlessUuid = credentials?.vlessUuid,
        )

    // Chain hop fields fall back to the legacy inline settings when no
    // referenced-profile resolution (`chainRelay`) is present.
    private fun chainSection(): RelayChainSection =
        RelayChainSection(
            chainEntryServer = chainRelay?.entry?.server ?: effectiveConfig.chainEntryServer,
            chainEntryPort = chainRelay?.entry?.serverPort ?: effectiveConfig.chainEntryPort,
            chainEntryServerName = chainRelay?.entry?.serverName ?: effectiveConfig.chainEntryServerName,
            chainEntryPublicKey = chainRelay?.entry?.publicKey ?: effectiveConfig.chainEntryPublicKey,
            chainEntryShortId = chainRelay?.entry?.shortId ?: effectiveConfig.chainEntryShortId,
            chainEntryProfileId = chainRelay?.entry?.profileId ?: effectiveConfig.chainEntryProfileId,
            chainEntryUuid = chainRelay?.entry?.uuid ?: credentials?.chainEntryUuid,
            chainExitServer = chainRelay?.exit?.server ?: effectiveConfig.chainExitServer,
            chainExitPort = chainRelay?.exit?.serverPort ?: effectiveConfig.chainExitPort,
            chainExitServerName = chainRelay?.exit?.serverName ?: effectiveConfig.chainExitServerName,
            chainExitPublicKey = chainRelay?.exit?.publicKey ?: effectiveConfig.chainExitPublicKey,
            chainExitShortId = chainRelay?.exit?.shortId ?: effectiveConfig.chainExitShortId,
            chainExitProfileId = chainRelay?.exit?.profileId ?: effectiveConfig.chainExitProfileId,
            chainExitUuid = chainRelay?.exit?.uuid ?: credentials?.chainExitUuid,
        )

    private fun masqueSection(): RelayMasqueSection =
        RelayMasqueSection(
            masqueUrl = effectiveConfig.masqueUrl,
            masqueUseHttp2Fallback = effectiveConfig.masqueUseHttp2Fallback,
            masqueCloudflareGeohashEnabled = effectiveConfig.masqueCloudflareGeohashEnabled,
            masqueAuthMode = resolution.masqueAuthMode,
            masqueAuthToken = credentials?.masqueAuthToken,
            masqueClientCertificateChainPem = credentials?.masqueClientCertificateChainPem,
            masqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem,
            masqueCloudflareGeohashHeader = resolution.masqueCloudflareGeohashHeader,
            masquePrivacyPassProviderUrl = resolution.privacyPassRuntime?.providerUrl,
            masquePrivacyPassProviderAuthToken = resolution.privacyPassRuntime?.providerAuthToken,
        )

    private fun tuicSection(): RelayTuicSection =
        RelayTuicSection(
            tuicZeroRtt = effectiveConfig.tuicZeroRtt,
            tuicCongestionControl = effectiveConfig.tuicCongestionControl,
            tuicUuid = credentials?.tuicUuid,
            tuicPassword = credentials?.tuicPassword,
        )

    private fun shadowTlsSection(): RelayShadowTlsSection =
        RelayShadowTlsSection(
            shadowTlsInnerProfileId = effectiveConfig.shadowTlsInnerProfileId,
            shadowTlsInner = resolution.shadowTlsInner,
            shadowTlsPassword = credentials?.shadowTlsPassword,
        )

    private fun trojanSection(): RelayTrojanSection =
        RelayTrojanSection(
            trojanPassword = credentials?.trojanPassword,
            trojanRootCertificatePem = null,
        )

    private fun shadowsocksSection(): RelayShadowsocksSection =
        RelayShadowsocksSection(
            shadowsocksMethod = credentials?.shadowsocksMethod,
            shadowsocksPassword = credentials?.shadowsocksPassword,
        )

    private fun hysteria2Section(): RelayHysteria2Section =
        RelayHysteria2Section(
            hysteriaPassword = credentials?.hysteriaPassword,
            hysteriaSalamanderKey = credentials?.hysteriaSalamanderKey,
        )

    private fun pluggableTransportSection(): RelayPluggableTransportSection =
        RelayPluggableTransportSection(
            naivePath = effectiveConfig.naivePath,
            naiveUsername = credentials?.naiveUsername,
            naivePassword = credentials?.naivePassword,
            ptBridgeLine = effectiveConfig.ptBridgeLine,
            ptWebTunnelUrl = effectiveConfig.ptWebTunnelUrl,
            ptSnowflakeBrokerUrl = effectiveConfig.ptSnowflakeBrokerUrl,
            ptSnowflakeFrontDomain = effectiveConfig.ptSnowflakeFrontDomain,
        )

    private fun cloudflareSection(): RelayCloudflareSection =
        RelayCloudflareSection(
            cloudflareTunnelMode = effectiveConfig.cloudflareTunnelMode,
            cloudflarePublishLocalOriginUrl = effectiveConfig.cloudflarePublishLocalOriginUrl,
            cloudflareCredentialsRef = effectiveConfig.cloudflareCredentialsRef,
            cloudflareTunnelToken = credentials?.cloudflareTunnelToken,
            cloudflareTunnelCredentialsJson = credentials?.cloudflareTunnelCredentialsJson,
        )

    private fun appsScriptSection(): RelayAppsScriptSection =
        RelayAppsScriptSection(
            appsScriptScriptIds = effectiveConfig.appsScriptScriptIds,
            appsScriptGoogleIp = effectiveConfig.appsScriptGoogleIp,
            appsScriptFrontDomain = effectiveConfig.appsScriptFrontDomain,
            appsScriptSniHosts = effectiveConfig.appsScriptSniHosts,
            appsScriptVerifySsl = effectiveConfig.appsScriptVerifySsl,
            appsScriptParallelRelay = effectiveConfig.appsScriptParallelRelay,
            appsScriptDirectHosts = effectiveConfig.appsScriptDirectHosts,
            appsScriptAuthKey = credentials?.appsScriptAuthKey,
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
