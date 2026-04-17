@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
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
        override suspend fun resolve(
            config: RipDpiRelayConfig,
            quicMigrationConfig: OwnedRelayQuicMigrationConfig,
        ): ResolvedRipDpiRelayConfig {
            val profileId = config.profileId.ifBlank { DefaultRelayProfileId }
            val storedProfile = relayProfileStore.load(profileId)
            val requestedTlsProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfileProvider.currentProfile())
            val effectiveConfig =
                mergeRelayConfig(config, storedProfile).let { merged ->
                    when (merged.kind) {
                        RelayKindCloudflareTunnel -> {
                            merged.copy(
                                vlessTransport = RelayVlessTransportXhttp,
                                udpEnabled = false,
                            )
                        }

                        RelayKindMasque -> {
                            merged.copy(
                                masqueUseHttp2Fallback =
                                    if (requestedTlsProfile == TlsFingerprintProfileChromeStable) {
                                        true
                                    } else {
                                        merged.masqueUseHttp2Fallback
                                    },
                            )
                        }

                        RelayKindSnowflake -> {
                            merged.copy(
                                udpEnabled = false,
                                ptSnowflakeBrokerUrl =
                                    merged.ptSnowflakeBrokerUrl.ifBlank {
                                        DefaultSnowflakeBrokerUrl
                                    },
                                ptSnowflakeFrontDomain =
                                    merged.ptSnowflakeFrontDomain.ifBlank {
                                        DefaultSnowflakeFrontDomain
                                    },
                            )
                        }

                        else -> {
                            merged
                        }
                    }
                }
            val credentials = relayCredentialStore.load(profileId)
            val masqueAuthMode = resolveMasqueAuthModeSupport(credentials)
            val privacyPassReadiness =
                if (effectiveConfig.kind == RelayKindMasque && masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
                    masquePrivacyPassProvider.readinessFor(effectiveConfig, credentials)
                } else {
                    null
                }
            val privacyPassRuntime =
                if (effectiveConfig.kind == RelayKindMasque && masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
                    masquePrivacyPassProvider.resolve(profileId, effectiveConfig, credentials)
                } else {
                    null
                }
            val resolvedChainRelay =
                if (effectiveConfig.kind == RelayKindChainRelay) {
                    resolveChainRelayConfigSupport(
                        chainProfileId = profileId,
                        config = effectiveConfig,
                        credentials = credentials,
                        relayProfileStore = relayProfileStore,
                        relayCredentialStore = relayCredentialStore,
                    )
                } else {
                    null
                }
            validateRelayCredentials(profileId, effectiveConfig.kind, masqueAuthMode, credentials)
            validateSupportedRelayFeatures(
                profileId = profileId,
                config = effectiveConfig,
                credentials = credentials,
                masqueAuthMode = masqueAuthMode,
                privacyPassRuntime = privacyPassRuntime,
                privacyPassReadiness = privacyPassReadiness,
                tlsFingerprintProfile = requestedTlsProfile,
                featureFlags = runtimeExperimentSelectionProvider.current().featureFlags,
            )
            val shadowTlsInner =
                if (effectiveConfig.kind == RelayKindShadowTlsV3) {
                    resolveShadowTlsInnerConfigSupport(
                        outerProfileId = profileId,
                        innerProfileId = effectiveConfig.shadowTlsInnerProfileId,
                        relayProfileStore = relayProfileStore,
                        relayCredentialStore = relayCredentialStore,
                    )
                } else {
                    null
                }
            val effectiveTlsProfile =
                if (effectiveConfig.kind == RelayKindCloudflareTunnel) {
                    TlsFingerprintProfileChromeStable
                } else {
                    requestedTlsProfile
                }
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
                chainEntryServer = resolvedChainRelay?.entry?.server ?: effectiveConfig.chainEntryServer,
                chainEntryPort = resolvedChainRelay?.entry?.serverPort ?: effectiveConfig.chainEntryPort,
                chainEntryServerName = resolvedChainRelay?.entry?.serverName ?: effectiveConfig.chainEntryServerName,
                chainEntryPublicKey = resolvedChainRelay?.entry?.publicKey ?: effectiveConfig.chainEntryPublicKey,
                chainEntryShortId = resolvedChainRelay?.entry?.shortId ?: effectiveConfig.chainEntryShortId,
                chainEntryProfileId = resolvedChainRelay?.entry?.profileId ?: effectiveConfig.chainEntryProfileId,
                chainExitServer = resolvedChainRelay?.exit?.server ?: effectiveConfig.chainExitServer,
                chainExitPort = resolvedChainRelay?.exit?.serverPort ?: effectiveConfig.chainExitPort,
                chainExitServerName = resolvedChainRelay?.exit?.serverName ?: effectiveConfig.chainExitServerName,
                chainExitPublicKey = resolvedChainRelay?.exit?.publicKey ?: effectiveConfig.chainExitPublicKey,
                chainExitShortId = resolvedChainRelay?.exit?.shortId ?: effectiveConfig.chainExitShortId,
                chainExitProfileId = resolvedChainRelay?.exit?.profileId ?: effectiveConfig.chainExitProfileId,
                masqueUrl = effectiveConfig.masqueUrl,
                masqueUseHttp2Fallback = effectiveConfig.masqueUseHttp2Fallback,
                masqueCloudflareGeohashEnabled = effectiveConfig.masqueCloudflareGeohashEnabled,
                tuicZeroRtt = effectiveConfig.tuicZeroRtt,
                tuicCongestionControl = effectiveConfig.tuicCongestionControl,
                shadowTlsInnerProfileId = effectiveConfig.shadowTlsInnerProfileId,
                shadowTlsInner = shadowTlsInner,
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
                chainEntryUuid = resolvedChainRelay?.entry?.uuid ?: credentials?.chainEntryUuid,
                chainExitUuid = resolvedChainRelay?.exit?.uuid ?: credentials?.chainExitUuid,
                hysteriaPassword = credentials?.hysteriaPassword,
                hysteriaSalamanderKey = credentials?.hysteriaSalamanderKey,
                tuicUuid = credentials?.tuicUuid,
                tuicPassword = credentials?.tuicPassword,
                shadowTlsPassword = credentials?.shadowTlsPassword,
                naiveUsername = credentials?.naiveUsername,
                naivePassword = credentials?.naivePassword,
                tlsFingerprintProfile = effectiveTlsProfile,
                masqueAuthMode = masqueAuthMode,
                masqueAuthToken = credentials?.masqueAuthToken,
                masqueClientCertificateChainPem = credentials?.masqueClientCertificateChainPem,
                masqueClientPrivateKeyPem = credentials?.masqueClientPrivateKeyPem,
                masqueCloudflareGeohashHeader =
                    if (effectiveConfig.kind == RelayKindMasque &&
                        masqueAuthMode == RelayMasqueAuthModeCloudflareMtls &&
                        effectiveConfig.masqueCloudflareGeohashEnabled
                    ) {
                        cloudflareMasqueGeohashResolver.resolveHeaderValue()
                    } else {
                        null
                    },
                masquePrivacyPassProviderUrl = privacyPassRuntime?.providerUrl,
                masquePrivacyPassProviderAuthToken = privacyPassRuntime?.providerAuthToken,
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
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UpstreamRelayRuntimeConfigResolverModule {
    @Binds
    @Singleton
    abstract fun bindUpstreamRelayRuntimeConfigResolver(
        resolver: DefaultUpstreamRelayRuntimeConfigResolver,
    ): UpstreamRelayRuntimeConfigResolver
}
