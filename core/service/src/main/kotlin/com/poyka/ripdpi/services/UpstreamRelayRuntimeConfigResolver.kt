package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
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

internal class RelayKindResolverDispatcher(
    private val relayKindResolvers: List<RelayKindResolver>,
) {
    suspend fun resolve(request: RelayResolverRequest): RelayResolverResult =
        relayKindResolvers.firstOrNull { it.supports(request.mergedConfig.kind) }?.resolve(request)
            ?: error("Missing relay resolver for kind ${request.mergedConfig.kind}")
}

@Singleton
internal class DefaultUpstreamRelayRuntimeConfigResolver
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver,
        private val masquePrivacyPassProvider: MasquePrivacyPassProvider,
        private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
        private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    ) : UpstreamRelayRuntimeConfigResolver {
        private val relayKindResolverDispatcher =
            RelayKindResolverDispatcher(
                relayKindResolvers =
                    listOf(
                        MasqueRelayKindResolver(
                            cloudflareMasqueGeohashResolver = cloudflareMasqueGeohashResolver,
                            masquePrivacyPassProvider = masquePrivacyPassProvider,
                        ),
                        CloudflareTunnelRelayKindResolver(),
                        SnowflakeRelayKindResolver(),
                        ChainRelayKindResolver(
                            relayProfileStore = relayProfileStore,
                            relayCredentialStore = relayCredentialStore,
                        ),
                        ShadowTlsRelayKindResolver(
                            relayProfileStore = relayProfileStore,
                            relayCredentialStore = relayCredentialStore,
                        ),
                        NaiveRelayKindResolver(),
                        LocalPathRelayKindResolver(),
                        DefaultRelayKindResolver(),
                    ),
            )

        override suspend fun resolve(
            config: RipDpiRelayConfig,
            quicMigrationConfig: OwnedRelayQuicMigrationConfig,
        ): ResolvedRipDpiRelayConfig {
            val profileId = config.profileId.ifBlank { DefaultRelayProfileId }
            val storedProfile = relayProfileStore.load(profileId)
            val requestedTlsProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfileProvider.currentProfile())
            val credentials = relayCredentialStore.load(profileId)
            val resolution =
                relayKindResolverDispatcher.resolve(
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

internal class MasqueRelayKindResolver(
    private val cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver,
    private val masquePrivacyPassProvider: MasquePrivacyPassProvider,
) : RelayKindResolver {
    override fun supports(kind: String): Boolean = kind == RelayKindMasque

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        val effectiveConfig =
            request.mergedConfig.copy(
                masqueUseHttp2Fallback =
                    if (request.requestedTlsProfile == TlsFingerprintProfileChromeStable) {
                        true
                    } else {
                        request.mergedConfig.masqueUseHttp2Fallback
                    },
            )
        val masqueAuthMode = resolveMasqueAuthModeSupport(request.credentials)
        val privacyPassReadiness =
            if (masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
                masquePrivacyPassProvider.readinessFor(effectiveConfig, request.credentials)
            } else {
                null
            }
        val privacyPassRuntime =
            if (masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
                masquePrivacyPassProvider.resolve(request.profileId, effectiveConfig, request.credentials)
            } else {
                null
            }

        validateMasqueRelayCredentials(request.profileId, masqueAuthMode, request.credentials)
        validateSharedRelayTransportFeatures(effectiveConfig)
        validateMasqueRelayFeatures(
            profileId = request.profileId,
            config = effectiveConfig,
            masqueAuthMode = masqueAuthMode,
            privacyPassRuntime = privacyPassRuntime,
            privacyPassReadiness = privacyPassReadiness,
            featureFlags = request.featureFlags,
        )
        validateFinalmaskFeature(effectiveConfig, request.featureFlags)

        val masqueCloudflareGeohashHeader =
            if (masqueAuthMode == RelayMasqueAuthModeCloudflareMtls &&
                effectiveConfig.masqueCloudflareGeohashEnabled
            ) {
                cloudflareMasqueGeohashResolver.resolveHeaderValue()
            } else {
                null
            }
        return RelayResolverResult(
            effectiveConfig = effectiveConfig,
            effectiveTlsProfile = request.requestedTlsProfile,
            masqueAuthMode = masqueAuthMode,
            privacyPassRuntime = privacyPassRuntime,
            masqueCloudflareGeohashHeader = masqueCloudflareGeohashHeader,
        )
    }
}

internal class CloudflareTunnelRelayKindResolver : RelayKindResolver {
    override fun supports(kind: String): Boolean = kind == RelayKindCloudflareTunnel

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        val effectiveConfig =
            request.mergedConfig.copy(
                vlessTransport = RelayVlessTransportXhttp,
                udpEnabled = false,
            )

        validateCloudflareTunnelCredentials(request.profileId, request.credentials)
        validateSharedRelayTransportFeatures(effectiveConfig)
        validateCloudflareTunnelFeatures(
            config = effectiveConfig,
            credentials = request.credentials,
            tlsFingerprintProfile = request.requestedTlsProfile,
            featureFlags = request.featureFlags,
        )
        validateFinalmaskFeature(effectiveConfig, request.featureFlags)

        return RelayResolverResult(
            effectiveConfig = effectiveConfig,
            effectiveTlsProfile = TlsFingerprintProfileChromeStable,
        )
    }
}

internal class SnowflakeRelayKindResolver : RelayKindResolver {
    override fun supports(kind: String): Boolean = kind == RelayKindSnowflake

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        val effectiveConfig =
            request.mergedConfig.copy(
                udpEnabled = false,
                ptSnowflakeBrokerUrl =
                    request.mergedConfig.ptSnowflakeBrokerUrl.ifBlank {
                        DefaultSnowflakeBrokerUrl
                    },
                ptSnowflakeFrontDomain =
                    request.mergedConfig.ptSnowflakeFrontDomain.ifBlank {
                        DefaultSnowflakeFrontDomain
                    },
            )

        validateSharedRelayTransportFeatures(effectiveConfig)
        validatePluggableTransportLoopbackFeatures(effectiveConfig)
        validateSnowflakeRelayFeatures(effectiveConfig)
        validateFinalmaskFeature(effectiveConfig, request.featureFlags)

        return RelayResolverResult(
            effectiveConfig = effectiveConfig,
            effectiveTlsProfile = request.requestedTlsProfile,
        )
    }
}

internal class ChainRelayKindResolver(
    private val relayProfileStore: RelayProfileStore,
    private val relayCredentialStore: RelayCredentialStore,
) : RelayKindResolver {
    override fun supports(kind: String): Boolean = kind == RelayKindChainRelay

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        val resolvedChainRelay =
            resolveChainRelayConfigSupport(
                chainProfileId = request.profileId,
                config = request.mergedConfig,
                credentials = request.credentials,
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
            )

        validateSharedRelayTransportFeatures(request.mergedConfig)
        validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

        return RelayResolverResult(
            effectiveConfig = request.mergedConfig,
            effectiveTlsProfile = request.requestedTlsProfile,
            resolvedChainRelay = resolvedChainRelay,
        )
    }
}

internal class ShadowTlsRelayKindResolver(
    private val relayProfileStore: RelayProfileStore,
    private val relayCredentialStore: RelayCredentialStore,
) : RelayKindResolver {
    override fun supports(kind: String): Boolean = kind == RelayKindShadowTlsV3

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        validateShadowTlsRelayCredentials(request.profileId, request.credentials)
        validateSharedRelayTransportFeatures(request.mergedConfig)
        validateShadowTlsRelayFeatures(request.profileId, request.mergedConfig)
        validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

        val shadowTlsInner =
            resolveShadowTlsInnerConfigSupport(
                outerProfileId = request.profileId,
                innerProfileId = request.mergedConfig.shadowTlsInnerProfileId,
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
            )

        return RelayResolverResult(
            effectiveConfig = request.mergedConfig,
            effectiveTlsProfile = request.requestedTlsProfile,
            shadowTlsInner = shadowTlsInner,
        )
    }
}

internal class NaiveRelayKindResolver : RelayKindResolver {
    override fun supports(kind: String): Boolean = kind == RelayKindNaiveProxy

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        validateNaiveRelayCredentials(request.profileId, request.credentials)
        validateSharedRelayTransportFeatures(request.mergedConfig)
        validateNaiveRelayFeatures(
            config = request.mergedConfig,
            featureFlags = request.featureFlags,
        )
        validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

        return RelayResolverResult(
            effectiveConfig = request.mergedConfig,
            effectiveTlsProfile = request.requestedTlsProfile,
        )
    }
}

internal class LocalPathRelayKindResolver : RelayKindResolver {
    override fun supports(kind: String): Boolean = kind == RelayKindWebTunnel || kind == RelayKindObfs4

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        validateSharedRelayTransportFeatures(request.mergedConfig)
        validatePluggableTransportLoopbackFeatures(request.mergedConfig)
        when (request.mergedConfig.kind) {
            RelayKindWebTunnel -> validateWebTunnelRelayFeatures(request.mergedConfig)
            RelayKindObfs4 -> validateObfs4RelayFeatures(request.mergedConfig)
        }
        validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

        return RelayResolverResult(
            effectiveConfig = request.mergedConfig,
            effectiveTlsProfile = request.requestedTlsProfile,
        )
    }
}

internal class DefaultRelayKindResolver : RelayKindResolver {
    override fun supports(kind: String): Boolean = true

    override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
        validateDefaultRelayCredentials(
            profileId = request.profileId,
            relayKind = request.mergedConfig.kind,
            credentials = request.credentials,
        )
        validateSharedRelayTransportFeatures(request.mergedConfig)
        validateDefaultRelayFeatures(
            config = request.mergedConfig,
            tlsFingerprintProfile = request.requestedTlsProfile,
        )
        validateFinalmaskFeature(request.mergedConfig, request.featureFlags)

        return RelayResolverResult(
            effectiveConfig = request.mergedConfig,
            effectiveTlsProfile = request.requestedTlsProfile,
        )
    }
}

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
        cloudflareMasqueGeohashResolver = cloudflareMasqueGeohashResolver,
        masquePrivacyPassProvider = masquePrivacyPassProvider,
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
