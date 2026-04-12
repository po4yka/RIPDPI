@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRelayFinalmaskConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.StrategyFeatureCloudflareConsumeValidation
import com.poyka.ripdpi.data.StrategyFeatureCloudflarePublish
import com.poyka.ripdpi.data.StrategyFeatureFinalmask
import com.poyka.ripdpi.data.StrategyFeatureMasqueCloudflareDirect
import com.poyka.ripdpi.data.StrategyFeatureNaiveProxyWatchdog
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

internal class UpstreamRelaySupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val relayFactory: RipDpiRelayFactory,
    private val naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
    private val cloudflarePublishRuntimeFactory: CloudflarePublishRuntimeFactory =
        object : CloudflarePublishRuntimeFactory {
            override fun create(): RipDpiRelayRuntime = error("Cloudflare publish runtime factory is not configured")
        },
    private val pluggableTransportRuntimeFactory: PluggableTransportRuntimeFactory =
        object : PluggableTransportRuntimeFactory {
            override fun create(): RipDpiRelayRuntime = error("Pluggable transport runtime factory is not configured")
        },
    private val relayProfileStore: RelayProfileStore,
    private val relayCredentialStore: RelayCredentialStore,
    private val cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver =
        object : CloudflareMasqueGeohashResolver {
            override suspend fun resolveHeaderValue(): String? = null
        },
    private val masquePrivacyPassProvider: MasquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
    private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider =
        object : OwnedTlsFingerprintProfileProvider {
            override fun currentProfile(): String = TlsFingerprintProfileChromeStable
        },
    private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider =
        object : RuntimeExperimentSelectionProvider {
            override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
        },
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var relayRuntime: RipDpiRelayRuntime? = null
    private var relayJob: Job? = null

    suspend fun start(
        config: RipDpiRelayConfig,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig = OwnedRelayQuicMigrationConfig(),
        onUnexpectedExit: suspend (Result<Int>) -> Unit,
    ) {
        check(relayJob == null) { "Relay fields not null" }
        val resolvedConfig = resolveRuntimeConfig(config, quicMigrationConfig)
        val runtime =
            if (resolvedConfig.kind == RelayKindNaiveProxy) {
                naiveProxyRuntimeFactory.create()
            } else if (
                resolvedConfig.kind == RelayKindCloudflareTunnel &&
                resolvedConfig.cloudflareTunnelMode == RelayCloudflareTunnelModePublishLocalOrigin
            ) {
                cloudflarePublishRuntimeFactory.create()
            } else if (isPluggableTransportRelay(resolvedConfig.kind)) {
                pluggableTransportRuntimeFactory.create()
            } else {
                relayFactory.create()
            }
        relayRuntime = runtime

        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    exitResult.complete(runCatching { runtime.start(resolvedConfig) })
                } finally {
                    if (!exitResult.isCompleted) {
                        exitResult.complete(Result.failure(IllegalStateException("Relay job cancelled")))
                    }
                }
            }
        relayJob = job

        @Suppress("TooGenericExceptionCaught")
        try {
            runtime.awaitReady()
        } catch (readinessError: Exception) {
            try {
                runCatching { runtime.stop() }
                job.join()
            } finally {
                relayJob = null
                relayRuntime = null
            }
            throw readinessError
        }

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                onUnexpectedExit(exitResult.await())
            }
        }
    }

    suspend fun stop() {
        val runtime = relayRuntime
        if (runtime == null) {
            relayJob = null
            return
        }

        try {
            runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                relayJob?.join()
            }
        } finally {
            relayJob = null
            relayRuntime = null
        }
    }

    fun detach() {
        relayJob = null
        relayRuntime = null
    }

    suspend fun pollTelemetry(): NativeRuntimeSnapshot? = runCatching { relayRuntime?.pollTelemetry() }.getOrNull()

    private suspend fun resolveRuntimeConfig(
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
                            ptSnowflakeBrokerUrl = merged.ptSnowflakeBrokerUrl.ifBlank { DefaultSnowflakeBrokerUrl },
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

internal open class UpstreamRelaySupervisorFactory
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
        private val cloudflarePublishRuntimeFactory: CloudflarePublishRuntimeFactory,
        private val pluggableTransportRuntimeFactory: PluggableTransportRuntimeFactory,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver,
        private val masquePrivacyPassProvider: MasquePrivacyPassProvider,
        private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
        private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    ) {
        constructor(
            relayFactory: RipDpiRelayFactory,
            relayProfileStore: RelayProfileStore,
            relayCredentialStore: RelayCredentialStore,
        ) : this(
            relayFactory,
            object : NaiveProxyRuntimeFactory {
                override fun create(): RipDpiRelayRuntime =
                    error("NaiveProxy runtime factory is not configured in this test harness")
            },
            object : CloudflarePublishRuntimeFactory {
                override fun create(): RipDpiRelayRuntime =
                    error("Cloudflare publish runtime factory is not configured in this test harness")
            },
            object : PluggableTransportRuntimeFactory {
                override fun create(): RipDpiRelayRuntime =
                    error("Pluggable transport runtime factory is not configured in this test harness")
            },
            relayProfileStore,
            relayCredentialStore,
            object : CloudflareMasqueGeohashResolver {
                override suspend fun resolveHeaderValue(): String? = null
            },
            StaticMasquePrivacyPassProvider(),
            object : OwnedTlsFingerprintProfileProvider {
                override fun currentProfile(): String = TlsFingerprintProfileChromeStable
            },
            object : RuntimeExperimentSelectionProvider {
                override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
            },
        )

        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): UpstreamRelaySupervisor =
            UpstreamRelaySupervisor(
                scope = scope,
                dispatcher = dispatcher,
                relayFactory = relayFactory,
                naiveProxyRuntimeFactory = naiveProxyRuntimeFactory,
                cloudflarePublishRuntimeFactory = cloudflarePublishRuntimeFactory,
                pluggableTransportRuntimeFactory = pluggableTransportRuntimeFactory,
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                cloudflareMasqueGeohashResolver = cloudflareMasqueGeohashResolver,
                masquePrivacyPassProvider = masquePrivacyPassProvider,
                tlsFingerprintProfileProvider = tlsFingerprintProfileProvider,
                runtimeExperimentSelectionProvider = runtimeExperimentSelectionProvider,
            )
    }

internal fun Map<String, Boolean>.isEnabled(flagId: String): Boolean = this[flagId] == true

internal fun isPluggableTransportRelay(kind: String): Boolean =
    kind == RelayKindSnowflake ||
        kind == RelayKindWebTunnel ||
        kind == RelayKindObfs4

internal class StaticMasquePrivacyPassProvider(
    private val available: Boolean = false,
    private val providerUrl: String = "",
    private val providerAuthToken: String? = null,
) : MasquePrivacyPassProvider {
    override fun isAvailable(): Boolean = available

    override fun buildStatus(): MasquePrivacyPassBuildStatus =
        if (available) {
            MasquePrivacyPassBuildStatus.Available
        } else {
            MasquePrivacyPassBuildStatus.MissingProviderUrl
        }

    override fun readinessFor(
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord?,
    ): MasquePrivacyPassReadiness =
        if (available) {
            MasquePrivacyPassReadiness.Ready
        } else {
            MasquePrivacyPassReadiness.MissingProviderUrl
        }

    override suspend fun resolve(
        profileId: String,
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord?,
    ): MasquePrivacyPassRuntimeConfig? =
        if (available) {
            MasquePrivacyPassRuntimeConfig(
                providerUrl = providerUrl,
                providerAuthToken = providerAuthToken,
            )
        } else {
            null
        }
}
