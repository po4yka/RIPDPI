package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RelayAnyTlsSection
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
import com.poyka.ripdpi.core.RelayTorSection
import com.poyka.ripdpi.core.RelayTrojanSection
import com.poyka.ripdpi.core.RelayTuicSection
import com.poyka.ripdpi.core.RelayVlessSection
import com.poyka.ripdpi.core.ResolvedChainRelayHopRef
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.ResolvedTorPluggableTransportConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFinalmaskConfig
import com.poyka.ripdpi.core.toResolvedConfig
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

internal data class TorRuntimePaths(
    val stateDir: String,
    val cacheDir: String,
)

internal interface TorRuntimePathProvider {
    fun pathsFor(profileId: String): TorRuntimePaths
}

@Singleton
internal class AndroidTorRuntimePathProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : TorRuntimePathProvider {
        override fun pathsFor(profileId: String): TorRuntimePaths {
            val stateSegment = sanitizeTorPathSegment(profileId)
            return TorRuntimePaths(
                stateDir = File(context.noBackupFilesDir, "tor/$stateSegment/state").apply { mkdirs() }.absolutePath,
                cacheDir = File(context.cacheDir, "tor/$stateSegment/cache").apply { mkdirs() }.absolutePath,
            )
        }
    }

internal interface TorPluggableTransportProvider {
    fun transportsFor(config: RipDpiRelayConfig): List<ResolvedTorPluggableTransportConfig>
}

@Singleton
internal class ManagedTorPluggableTransportProvider
    @Inject
    constructor(
        private val manager: PluggableTransportManager,
    ) : TorPluggableTransportProvider {
        override fun transportsFor(config: RipDpiRelayConfig): List<ResolvedTorPluggableTransportConfig> =
            manager.torManagedTransports(config)
    }

internal class LocalTorRuntimePathProvider(
    private val rootDir: File = File(System.getProperty("java.io.tmpdir"), "ripdpi-tor"),
) : TorRuntimePathProvider {
    override fun pathsFor(profileId: String): TorRuntimePaths {
        val stateSegment = sanitizeTorPathSegment(profileId)
        return TorRuntimePaths(
            stateDir = File(rootDir, "$stateSegment/state").absolutePath,
            cacheDir = File(rootDir, "$stateSegment/cache").absolutePath,
        )
    }
}

internal class UnconfiguredTorPluggableTransportProvider : TorPluggableTransportProvider {
    override fun transportsFor(config: RipDpiRelayConfig): List<ResolvedTorPluggableTransportConfig> =
        error("Tor pluggable transport provider is not configured")
}

private fun sanitizeTorPathSegment(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

@Singleton
internal class DefaultUpstreamRelayRuntimeConfigResolver
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val relayKindResolverRegistry: RelayKindResolverRegistry,
        private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
        private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
        private val torRuntimePathProvider: TorRuntimePathProvider,
        private val torPluggableTransportProvider: TorPluggableTransportProvider,
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
                torRuntimePathProvider = torRuntimePathProvider,
                torPluggableTransportProvider = torPluggableTransportProvider,
            )
        }
    }

private fun buildResolvedRelayConfig(
    profileId: String,
    resolution: RelayResolverResult,
    credentials: RelayCredentialRecord?,
    quicMigrationConfig: OwnedRelayQuicMigrationConfig,
    torRuntimePathProvider: TorRuntimePathProvider,
    torPluggableTransportProvider: TorPluggableTransportProvider,
): ResolvedRipDpiRelayConfig =
    ResolvedRelayConfigBuilder(
        profileId = profileId,
        resolution = resolution,
        credentials = credentials,
        quicMigrationConfig = quicMigrationConfig,
        torRuntimePathProvider = torRuntimePathProvider,
        torPluggableTransportProvider = torPluggableTransportProvider,
    ).build()

private class ResolvedRelayConfigBuilder(
    private val profileId: String,
    private val resolution: RelayResolverResult,
    private val credentials: RelayCredentialRecord?,
    private val quicMigrationConfig: OwnedRelayQuicMigrationConfig,
    private val torRuntimePathProvider: TorRuntimePathProvider,
    private val torPluggableTransportProvider: TorPluggableTransportProvider,
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
            anyTls = anyTlsSection(),
            pluggableTransport = pluggableTransportSection(),
            tor = torSection(),
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
            udpEnabled = if (effectiveConfig.kind == RelayKindTor) false else effectiveConfig.udpEnabled,
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
    // referenced-profile resolution (`chainRelay`) is present. The two hops are
    // assembled as the ordered [RelayChainSection] hop list (entry, exit); the
    // wire-DTO flatten in [toResolvedConfig] re-derives the flat scalar pair.
    private fun chainSection(): RelayChainSection =
        RelayChainSection(
            hops =
                listOf(
                    ResolvedChainRelayHopRef(
                        config = chainRelay?.entry?.config,
                        server = chainRelay?.entry?.server ?: effectiveConfig.chainEntryServer,
                        serverPort = chainRelay?.entry?.serverPort ?: effectiveConfig.chainEntryPort,
                        serverName = chainRelay?.entry?.serverName ?: effectiveConfig.chainEntryServerName,
                        publicKey = chainRelay?.entry?.publicKey ?: effectiveConfig.chainEntryPublicKey,
                        shortId = chainRelay?.entry?.shortId ?: effectiveConfig.chainEntryShortId,
                        profileId = chainRelay?.entry?.profileId ?: effectiveConfig.chainEntryProfileId,
                        uuid = chainRelay?.entry?.uuid ?: credentials?.chainEntryUuid,
                    ),
                    ResolvedChainRelayHopRef(
                        config = chainRelay?.exit?.config,
                        server = chainRelay?.exit?.server ?: effectiveConfig.chainExitServer,
                        serverPort = chainRelay?.exit?.serverPort ?: effectiveConfig.chainExitPort,
                        serverName = chainRelay?.exit?.serverName ?: effectiveConfig.chainExitServerName,
                        publicKey = chainRelay?.exit?.publicKey ?: effectiveConfig.chainExitPublicKey,
                        shortId = chainRelay?.exit?.shortId ?: effectiveConfig.chainExitShortId,
                        profileId = chainRelay?.exit?.profileId ?: effectiveConfig.chainExitProfileId,
                        uuid = chainRelay?.exit?.uuid ?: credentials?.chainExitUuid,
                    ),
                ),
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

    private fun anyTlsSection(): RelayAnyTlsSection =
        RelayAnyTlsSection(
            anyTlsPassword = credentials?.anyTlsPassword,
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

    private fun torSection(): RelayTorSection {
        if (effectiveConfig.kind != RelayKindTor) {
            return RelayTorSection(
                torStateDir = "",
                torCacheDir = "",
                torBridgeLines = emptyList(),
                torTransports = emptyList(),
            )
        }
        val paths = torRuntimePathProvider.pathsFor(profileId)
        return RelayTorSection(
            torStateDir = paths.stateDir,
            torCacheDir = paths.cacheDir,
            torBridgeLines = listOf(effectiveConfig.ptBridgeLine).filter(String::isNotBlank),
            torTransports = torPluggableTransportProvider.transportsFor(effectiveConfig),
        )
    }

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

internal fun RipDpiRelayFinalmaskConfig.toResolvedFinalmaskConfig(): ResolvedRelayFinalmaskConfig =
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
    torRuntimePathProvider: TorRuntimePathProvider,
    torPluggableTransportProvider: TorPluggableTransportProvider,
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
        torRuntimePathProvider = torRuntimePathProvider,
        torPluggableTransportProvider = torPluggableTransportProvider,
    )

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UpstreamRelayRuntimeConfigResolverModule {
    @Binds
    @Singleton
    abstract fun bindUpstreamRelayRuntimeConfigResolver(
        resolver: DefaultUpstreamRelayRuntimeConfigResolver,
    ): UpstreamRelayRuntimeConfigResolver

    @Binds
    @Singleton
    abstract fun bindTorRuntimePathProvider(provider: AndroidTorRuntimePathProvider): TorRuntimePathProvider

    @Binds
    @Singleton
    abstract fun bindTorPluggableTransportProvider(
        provider: ManagedTorPluggableTransportProvider,
    ): TorPluggableTransportProvider
}
