package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedShadowTlsInnerRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
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
    private val relayProfileStore: RelayProfileStore,
    private val relayCredentialStore: RelayCredentialStore,
    private val masquePrivacyPassProvider: MasquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
    private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider =
        object : OwnedTlsFingerprintProfileProvider {
            override fun currentProfile(): String = TlsFingerprintProfileChromeStable
        },
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var relayRuntime: RipDpiRelayRuntime? = null
    private var relayJob: Job? = null

    suspend fun start(
        config: RipDpiRelayConfig,
        onUnexpectedExit: suspend (Result<Int>) -> Unit,
    ) {
        check(relayJob == null) { "Relay fields not null" }
        val resolvedConfig = resolveRuntimeConfig(config)
        val runtime =
            if (resolvedConfig.kind == RelayKindNaiveProxy) {
                naiveProxyRuntimeFactory.create()
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

    private suspend fun resolveRuntimeConfig(config: RipDpiRelayConfig): ResolvedRipDpiRelayConfig {
        val profileId = config.profileId.ifBlank { DefaultRelayProfileId }
        val storedProfile = relayProfileStore.load(profileId)
        val requestedTlsProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfileProvider.currentProfile())
        val effectiveConfig =
            mergeConfig(config, storedProfile).let { merged ->
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

                    else -> {
                        merged
                    }
                }
            }
        val credentials = relayCredentialStore.load(profileId)
        val masqueAuthMode = resolveMasqueAuthMode(effectiveConfig, credentials)
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
        validateCredentials(profileId, effectiveConfig.kind, masqueAuthMode, credentials)
        validateSupportedFeatures(
            profileId = profileId,
            config = effectiveConfig,
            masqueAuthMode = masqueAuthMode,
            privacyPassRuntime = privacyPassRuntime,
            privacyPassReadiness = privacyPassReadiness,
            tlsFingerprintProfile = requestedTlsProfile,
        )
        val shadowTlsInner =
            if (effectiveConfig.kind == RelayKindShadowTlsV3) {
                resolveShadowTlsInnerConfig(
                    outerProfileId = profileId,
                    innerProfileId = effectiveConfig.shadowTlsInnerProfileId,
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
            masqueCloudflareMode = effectiveConfig.masqueCloudflareMode,
            tuicZeroRtt = effectiveConfig.tuicZeroRtt,
            tuicCongestionControl = effectiveConfig.tuicCongestionControl,
            shadowTlsInnerProfileId = effectiveConfig.shadowTlsInnerProfileId,
            shadowTlsInner = shadowTlsInner,
            naivePath = effectiveConfig.naivePath,
            localSocksHost = effectiveConfig.localSocksHost,
            localSocksPort = effectiveConfig.localSocksPort,
            udpEnabled = effectiveConfig.udpEnabled,
            tcpFallbackEnabled = effectiveConfig.tcpFallbackEnabled,
            vlessUuid = credentials?.vlessUuid,
            chainEntryUuid = credentials?.chainEntryUuid,
            chainExitUuid = credentials?.chainExitUuid,
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
            masqueCloudflareClientId = credentials?.masqueCloudflareClientId,
            masqueCloudflareKeyId = credentials?.masqueCloudflareKeyId,
            masqueCloudflarePrivateKeyPem = credentials?.masqueCloudflarePrivateKeyPem,
            masquePrivacyPassProviderUrl = privacyPassRuntime?.providerUrl,
            masquePrivacyPassProviderAuthToken = privacyPassRuntime?.providerAuthToken,
        )
    }

    private fun mergeConfig(
        config: RipDpiRelayConfig,
        profile: RelayProfileRecord?,
    ): RipDpiRelayConfig {
        if (profile == null) return config
        return config.copy(
            kind = profile.kind.ifBlank { config.kind },
            outboundBindIp = profile.outboundBindIp.ifBlank { config.outboundBindIp },
            server = profile.server.ifBlank { config.server },
            serverPort = profile.serverPort,
            serverName = profile.serverName.ifBlank { config.serverName },
            realityPublicKey = profile.realityPublicKey.ifBlank { config.realityPublicKey },
            realityShortId = profile.realityShortId.ifBlank { config.realityShortId },
            vlessTransport = profile.vlessTransport.ifBlank { config.vlessTransport },
            xhttpPath = profile.xhttpPath.ifBlank { config.xhttpPath },
            xhttpHost = profile.xhttpHost.ifBlank { config.xhttpHost },
            chainEntryServer = profile.chainEntryServer.ifBlank { config.chainEntryServer },
            chainEntryPort = profile.chainEntryPort,
            chainEntryServerName = profile.chainEntryServerName.ifBlank { config.chainEntryServerName },
            chainEntryPublicKey = profile.chainEntryPublicKey.ifBlank { config.chainEntryPublicKey },
            chainEntryShortId = profile.chainEntryShortId.ifBlank { config.chainEntryShortId },
            chainEntryProfileId = profile.chainEntryProfileId.ifBlank { config.chainEntryProfileId },
            chainExitServer = profile.chainExitServer.ifBlank { config.chainExitServer },
            chainExitPort = profile.chainExitPort,
            chainExitServerName = profile.chainExitServerName.ifBlank { config.chainExitServerName },
            chainExitPublicKey = profile.chainExitPublicKey.ifBlank { config.chainExitPublicKey },
            chainExitShortId = profile.chainExitShortId.ifBlank { config.chainExitShortId },
            chainExitProfileId = profile.chainExitProfileId.ifBlank { config.chainExitProfileId },
            masqueUrl = profile.masqueUrl.ifBlank { config.masqueUrl },
            masqueUseHttp2Fallback = profile.masqueUseHttp2Fallback,
            masqueCloudflareMode = profile.masqueCloudflareMode,
            tuicZeroRtt = profile.tuicZeroRtt,
            tuicCongestionControl = profile.tuicCongestionControl,
            shadowTlsInnerProfileId = profile.shadowTlsInnerProfileId.ifBlank { config.shadowTlsInnerProfileId },
            naivePath = profile.naivePath.ifBlank { config.naivePath },
            localSocksHost = profile.localSocksHost.ifBlank { config.localSocksHost },
            localSocksPort = profile.localSocksPort,
            udpEnabled = profile.udpEnabled,
            tcpFallbackEnabled = profile.tcpFallbackEnabled,
        )
    }

    private fun validateCredentials(
        profileId: String,
        relayKind: String,
        masqueAuthMode: String?,
        credentials: RelayCredentialRecord?,
    ) {
        val isValid =
            when (relayKind) {
                RelayKindVlessReality,
                RelayKindCloudflareTunnel,
                -> {
                    !credentials?.vlessUuid.isNullOrBlank()
                }

                RelayKindHysteria2 -> {
                    !credentials?.hysteriaPassword.isNullOrBlank()
                }

                RelayKindTuicV5 -> {
                    !credentials?.tuicUuid.isNullOrBlank() && !credentials.tuicPassword.isNullOrBlank()
                }

                RelayKindShadowTlsV3 -> {
                    !credentials?.shadowTlsPassword.isNullOrBlank()
                }

                RelayKindNaiveProxy -> {
                    !credentials?.naiveUsername.isNullOrBlank() && !credentials.naivePassword.isNullOrBlank()
                }

                RelayKindChainRelay -> {
                    !credentials?.chainEntryUuid.isNullOrBlank() &&
                        !credentials.chainExitUuid.isNullOrBlank()
                }

                RelayKindMasque -> {
                    when (masqueAuthMode) {
                        RelayMasqueAuthModeBearer,
                        RelayMasqueAuthModePreshared,
                        -> !credentials?.masqueAuthToken.isNullOrBlank()

                        RelayMasqueAuthModePrivacyPass -> true

                        else -> false
                    }
                }

                else -> {
                    true
                }
            }
        require(isValid) { "Relay credentials missing for profile $profileId" }
    }

    private fun validateSupportedFeatures(
        profileId: String,
        config: RipDpiRelayConfig,
        masqueAuthMode: String?,
        privacyPassRuntime: MasquePrivacyPassRuntimeConfig?,
        privacyPassReadiness: MasquePrivacyPassReadiness?,
        tlsFingerprintProfile: String,
    ) {
        require(
            !config.udpEnabled || config.kind == RelayKindHysteria2 || config.kind == RelayKindMasque ||
                config.kind == RelayKindTuicV5,
        ) {
            "Relay UDP mode is only available for Hysteria2, MASQUE, and TUIC profiles"
        }
        require(!(config.vlessTransport == RelayVlessTransportXhttp && config.udpEnabled)) {
            "xHTTP transport does not support UDP mode"
        }
        if (config.kind == RelayKindShadowTlsV3) {
            require(!config.udpEnabled) {
                "ShadowTLS v3 is TCP-only"
            }
            require(config.shadowTlsInnerProfileId.isNotBlank()) {
                "ShadowTLS v3 requires an inner profile reference"
            }
            require(config.vlessTransport == RelayVlessTransportRealityTcp) {
                "ShadowTLS outer profile must use direct TCP transport"
            }
        }
        if (config.kind == RelayKindNaiveProxy) {
            require(!config.udpEnabled) {
                "NaiveProxy does not support UDP mode"
            }
        }
        if (config.kind == RelayKindCloudflareTunnel && tlsFingerprintProfile != TlsFingerprintProfileChromeStable) {
            throw ServiceStartupRejectedException(
                FailureReason.RelayFingerprintPolicyRejected(
                    "Cloudflare Tunnel requires the Chrome stable TLS fingerprint profile",
                ),
            )
        }
        if (config.kind == RelayKindHysteria2 && tlsFingerprintProfile == TlsFingerprintProfileChromeStable) {
            throw ServiceStartupRejectedException(
                FailureReason.RelayFingerprintPolicyRejected(
                    "Hysteria2 is blocked until Chrome-like QUIC fingerprinting is implemented",
                ),
            )
        }
        if (config.kind == RelayKindMasque && masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
            requireNotNull(privacyPassRuntime) {
                masquePrivacyPassReadinessMessage(profileId, privacyPassReadiness)
            }
        }
        if (config.kind == RelayKindShadowTlsV3 && config.shadowTlsInnerProfileId == profileId) {
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected("ShadowTLS inner profile cannot reference itself"),
            )
        }
    }

    private suspend fun resolveShadowTlsInnerConfig(
        outerProfileId: String,
        innerProfileId: String,
    ): ResolvedShadowTlsInnerRelayConfig {
        val innerProfile =
            relayProfileStore.load(innerProfileId)
                ?: throw ServiceStartupRejectedException(
                    FailureReason.RelayConfigRejected("ShadowTLS inner profile $innerProfileId was not found"),
                )
        if (innerProfile.id == outerProfileId) {
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected("ShadowTLS inner profile cannot reference itself"),
            )
        }
        val innerCredentials = relayCredentialStore.load(innerProfileId)
        return when (innerProfile.kind) {
            RelayKindVlessReality -> {
                require(innerProfile.vlessTransport != RelayVlessTransportXhttp) {
                    "ShadowTLS currently supports only VLESS Reality TCP as an inner profile"
                }
                require(!innerCredentials?.vlessUuid.isNullOrBlank()) {
                    "Relay credentials missing for profile $innerProfileId"
                }
                ResolvedShadowTlsInnerRelayConfig(
                    kind = innerProfile.kind,
                    profileId = innerProfile.id,
                    server = innerProfile.server,
                    serverPort = innerProfile.serverPort,
                    serverName = innerProfile.serverName,
                    realityPublicKey = innerProfile.realityPublicKey,
                    realityShortId = innerProfile.realityShortId,
                    vlessTransport = innerProfile.vlessTransport.ifBlank { RelayVlessTransportRealityTcp },
                    vlessUuid = innerCredentials.vlessUuid,
                )
            }

            else -> {
                throw ServiceStartupRejectedException(
                    FailureReason.RelayConfigRejected(
                        "ShadowTLS inner profile kind ${innerProfile.kind} is not supported yet",
                    ),
                )
            }
        }
    }

    private fun resolveMasqueAuthMode(
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord?,
    ): String? =
        normalizeRelayMasqueAuthMode(
            value = credentials?.masqueAuthMode,
            cloudflareMode = config.masqueCloudflareMode,
        ) ?: if (!credentials?.masqueAuthToken.isNullOrBlank()) {
            RelayMasqueAuthModeBearer
        } else {
            null
        }

    private fun masquePrivacyPassReadinessMessage(
        profileId: String,
        readiness: MasquePrivacyPassReadiness?,
    ): String =
        when (readiness) {
            MasquePrivacyPassReadiness.MissingProviderUrl -> {
                "MASQUE privacy_pass auth requires a configured provider URL for profile $profileId"
            }

            MasquePrivacyPassReadiness.InvalidProviderUrl -> {
                "MASQUE privacy_pass auth provider URL is invalid for profile $profileId"
            }

            MasquePrivacyPassReadiness.UnsupportedRelayKind -> {
                "MASQUE privacy_pass auth is only available for MASQUE relay profiles"
            }

            MasquePrivacyPassReadiness.UnsupportedAuthMode -> {
                "MASQUE privacy_pass auth is not selected for profile $profileId"
            }

            MasquePrivacyPassReadiness.Ready,
            null,
            -> {
                "MASQUE privacy_pass auth requires a configured provider for profile $profileId"
            }
        }
}

internal open class UpstreamRelaySupervisorFactory
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val masquePrivacyPassProvider: MasquePrivacyPassProvider,
        private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider,
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
            relayProfileStore,
            relayCredentialStore,
            StaticMasquePrivacyPassProvider(),
            object : OwnedTlsFingerprintProfileProvider {
                override fun currentProfile(): String = TlsFingerprintProfileChromeStable
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
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                masquePrivacyPassProvider = masquePrivacyPassProvider,
                tlsFingerprintProfileProvider = tlsFingerprintProfileProvider,
            )
    }

internal class StaticMasquePrivacyPassProvider(
    private val available: Boolean = false,
    private val providerUrl: String = "",
    private val providerAuthToken: String? = null,
) : MasquePrivacyPassProvider {
    override fun isAvailable(): Boolean = available

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
