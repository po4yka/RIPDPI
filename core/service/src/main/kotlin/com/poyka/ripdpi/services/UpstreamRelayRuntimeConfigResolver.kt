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
import com.poyka.ripdpi.core.RelayMieruSection
import com.poyka.ripdpi.core.RelayPluggableTransportSection
import com.poyka.ripdpi.core.RelayShadowTlsSection
import com.poyka.ripdpi.core.RelayShadowsocksSection
import com.poyka.ripdpi.core.RelaySshSection
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
import com.poyka.ripdpi.data.normalizeImportedTlsFingerprint
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

internal data class LocalNetworkAwareRelayConfigResolution(
    val config: ResolvedRipDpiRelayConfig,
    val localNetworkDependent: Boolean,
)

internal interface LocalNetworkAwareRelayRuntimeConfigResolver {
    suspend fun resolveWithLocalNetworkDependency(
        config: RipDpiRelayConfig,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig,
    ): LocalNetworkAwareRelayConfigResolution
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
        private val relayRuntimeProfileReader: RelayRuntimeProfileReader,
        private val relayKindResolverRegistry: RelayKindResolverRegistry,
        private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
        private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
        private val torRuntimePathProvider: TorRuntimePathProvider,
        private val torPluggableTransportProvider: TorPluggableTransportProvider,
    ) : UpstreamRelayRuntimeConfigResolver {
        internal constructor(
            relayProfileStore: RelayProfileStore,
            relayCredentialStore: RelayCredentialStore,
            relayKindResolverRegistry: RelayKindResolverRegistry,
            tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
            runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
            torRuntimePathProvider: TorRuntimePathProvider,
            torPluggableTransportProvider: TorPluggableTransportProvider,
        ) : this(
            relayRuntimeProfileReader = RelayRuntimeProfileReader(relayProfileStore, relayCredentialStore),
            relayKindResolverRegistry = relayKindResolverRegistry,
            tlsFingerprintProfileProvider = tlsFingerprintProfileProvider,
            runtimeExperimentSelectionProvider = runtimeExperimentSelectionProvider,
            torRuntimePathProvider = torRuntimePathProvider,
            torPluggableTransportProvider = torPluggableTransportProvider,
        )

        override suspend fun resolve(
            config: RipDpiRelayConfig,
            quicMigrationConfig: OwnedRelayQuicMigrationConfig,
        ): ResolvedRipDpiRelayConfig {
            val profileId = config.profileId.ifBlank { DefaultRelayProfileId }
            val persisted = relayRuntimeProfileReader.read(profileId)
            val storedProfile = persisted.profile
            val requestedTlsProfile =
                storedProfile
                    ?.vlessFingerprint
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::normalizeImportedTlsFingerprint)
                    ?: normalizeTlsFingerprintProfile(tlsFingerprintProfileProvider.currentProfile())
            val credentials = persisted.credentials
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
            mieru = mieruSection(),
            ssh = sshSection(),
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
            vlessFlow = effectiveConfig.vlessFlow,
            vlessTransport = effectiveConfig.vlessTransport,
            xhttpPath = effectiveConfig.xhttpPath,
            xhttpHost = effectiveConfig.xhttpHost,
            xhttpMode = effectiveConfig.xhttpMode,
            vlessMuxProtocol = effectiveConfig.vlessMuxProtocol,
            vlessMuxMaxConcurrentStreams = effectiveConfig.vlessMuxMaxConcurrentStreams,
            vlessMuxPerConnectionKbps = effectiveConfig.vlessMuxPerConnectionKbps,
            vlessMuxPaddingMax = effectiveConfig.vlessMuxPaddingMax,
            vlessUuid = credentials?.vlessUuid,
        )

    // Chain hops as an ordered [RelayChainSection] list (entry, intermediate…,
    // exit). When a referenced-profile resolution (`chainRelay`) is present, its
    // ordered N-hop list is the source of truth and every hop — including the
    // intermediate hops authored in the editor — is emitted; the wire-DTO
    // flatten in [toResolvedConfig] re-derives the flat entry/exit scalar pair
    // and carries the full ordered list via `chainHops` for N > 2. Absent a
    // resolution (the legacy inline two-hop path) the flat entry/exit fields are
    // used directly.
    private fun chainSection(): RelayChainSection =
        RelayChainSection(hops = chainRelay?.let { resolvedChainHopRefs(it) } ?: legacyInlineChainHopRefs())

    private fun resolvedChainHopRefs(chain: ResolvedChainRelayConfig): List<ResolvedChainRelayHopRef> =
        chain.hops.map { hop ->
            ResolvedChainRelayHopRef(
                config = hop.config,
                server = hop.server,
                serverPort = hop.serverPort,
                serverName = hop.serverName,
                publicKey = hop.publicKey,
                shortId = hop.shortId,
                flow = hop.config.vlessFlow,
                xhttpMode = hop.config.xhttpMode,
                profileId = hop.profileId,
                uuid = hop.uuid.ifBlank { null },
            )
        }

    private fun legacyInlineChainHopRefs(): List<ResolvedChainRelayHopRef> =
        listOf(
            ResolvedChainRelayHopRef(
                config = null,
                server = effectiveConfig.chainEntryServer,
                serverPort = effectiveConfig.chainEntryPort,
                serverName = effectiveConfig.chainEntryServerName,
                publicKey = effectiveConfig.chainEntryPublicKey,
                shortId = effectiveConfig.chainEntryShortId,
                flow = com.poyka.ripdpi.data.RelayVlessFlowVision,
                xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
                profileId = effectiveConfig.chainEntryProfileId,
                uuid = credentials?.chainEntryUuid,
            ),
            ResolvedChainRelayHopRef(
                config = null,
                server = effectiveConfig.chainExitServer,
                serverPort = effectiveConfig.chainExitPort,
                serverName = effectiveConfig.chainExitServerName,
                publicKey = effectiveConfig.chainExitPublicKey,
                shortId = effectiveConfig.chainExitShortId,
                flow = com.poyka.ripdpi.data.RelayVlessFlowVision,
                xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
                profileId = effectiveConfig.chainExitProfileId,
                uuid = credentials?.chainExitUuid,
            ),
        )

    private fun masqueSection(): RelayMasqueSection =
        RelayMasqueSection(
            masqueUrl = effectiveConfig.masqueUrl,
            masqueTcpProtocol = effectiveConfig.masqueTcpProtocol,
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
            hysteriaInsecure = credentials?.hysteriaInsecure ?: false,
        )

    // Mieru reuses the common server/serverPort as its endpoint (the native
    // builder reads the relay endpoint there); the username and password are
    // secure-store credentials, mirroring how the NaiveProxy credentials are
    // sourced.
    private fun mieruSection(): RelayMieruSection =
        RelayMieruSection(
            mieruServer = effectiveConfig.server,
            mieruPort = effectiveConfig.serverPort,
            mieruUsername = credentials?.mieruUsername,
            mieruPassword = credentials?.mieruPassword,
            mieruProtocol = effectiveConfig.mieruProtocol,
            mieruMultiplexing = effectiveConfig.mieruMultiplexing,
            mieruMtu = effectiveConfig.mieruMtu,
        )

    // SSH reuses the common server/serverPort as its endpoint (the native
    // builder reads the relay endpoint there); the username, password, private
    // key, and private-key passphrase are secure-store credentials, mirroring
    // how the Mieru credentials are sourced. The pinned host-key fingerprint and
    // the strict-host-key toggle are non-credential config.
    private fun sshSection(): RelaySshSection =
        RelaySshSection(
            sshHost = effectiveConfig.server,
            sshPort = effectiveConfig.serverPort,
            sshUsername = credentials?.sshUsername,
            sshAuthType = effectiveConfig.sshAuthType,
            sshPassword = credentials?.sshPassword,
            sshPrivateKey = credentials?.sshPrivateKey,
            sshPrivateKeyPassphrase = credentials?.sshPrivateKeyPassphrase,
            sshHostKeyFingerprint = effectiveConfig.sshHostKeyFingerprint.ifEmpty { null },
            sshStrictHostKey = effectiveConfig.sshStrictHostKey,
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
        relayRuntimeProfileReader = RelayRuntimeProfileReader(relayProfileStore, relayCredentialStore),
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
        resolver: PermissionCheckedRelayConfigResolver,
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
