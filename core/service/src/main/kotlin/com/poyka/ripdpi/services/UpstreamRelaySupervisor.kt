package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
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
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
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
    private val relayProfileStore: RelayProfileStore,
    private val relayCredentialStore: RelayCredentialStore,
    private val masquePrivacyPassProvider: MasquePrivacyPassProvider = NoopMasquePrivacyPassProvider(),
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
        val runtime = relayFactory.create()
        val resolvedConfig = resolveRuntimeConfig(config)
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
        val privacyPassRuntime =
            if (effectiveConfig.kind == RelayKindMasque && masqueAuthMode == RelayMasqueAuthModePrivacyPass) {
                masquePrivacyPassProvider.resolve(profileId, effectiveConfig, credentials)
            } else {
                null
            }
        validateCredentials(profileId, effectiveConfig.kind, masqueAuthMode, credentials)
        validateSupportedFeatures(profileId, effectiveConfig, masqueAuthMode, privacyPassRuntime, requestedTlsProfile)
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
            chainExitServer = effectiveConfig.chainExitServer,
            chainExitPort = effectiveConfig.chainExitPort,
            chainExitServerName = effectiveConfig.chainExitServerName,
            chainExitPublicKey = effectiveConfig.chainExitPublicKey,
            chainExitShortId = effectiveConfig.chainExitShortId,
            masqueUrl = effectiveConfig.masqueUrl,
            masqueUseHttp2Fallback = effectiveConfig.masqueUseHttp2Fallback,
            masqueCloudflareMode = effectiveConfig.masqueCloudflareMode,
            localSocksHost = effectiveConfig.localSocksHost,
            localSocksPort = effectiveConfig.localSocksPort,
            udpEnabled = effectiveConfig.udpEnabled,
            tcpFallbackEnabled = effectiveConfig.tcpFallbackEnabled,
            vlessUuid = credentials?.vlessUuid,
            chainEntryUuid = credentials?.chainEntryUuid,
            chainExitUuid = credentials?.chainExitUuid,
            hysteriaPassword = credentials?.hysteriaPassword,
            hysteriaSalamanderKey = credentials?.hysteriaSalamanderKey,
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
            chainExitServer = profile.chainExitServer.ifBlank { config.chainExitServer },
            chainExitPort = profile.chainExitPort,
            chainExitServerName = profile.chainExitServerName.ifBlank { config.chainExitServerName },
            chainExitPublicKey = profile.chainExitPublicKey.ifBlank { config.chainExitPublicKey },
            chainExitShortId = profile.chainExitShortId.ifBlank { config.chainExitShortId },
            masqueUrl = profile.masqueUrl.ifBlank { config.masqueUrl },
            masqueUseHttp2Fallback = profile.masqueUseHttp2Fallback,
            masqueCloudflareMode = profile.masqueCloudflareMode,
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
        tlsFingerprintProfile: String,
    ) {
        require(!config.udpEnabled || config.kind == RelayKindHysteria2 || config.kind == RelayKindMasque) {
            "Relay UDP mode is only available for Hysteria2 and MASQUE profiles"
        }
        require(!(config.vlessTransport == RelayVlessTransportXhttp && config.udpEnabled)) {
            "xHTTP transport does not support UDP mode"
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
                "MASQUE privacy_pass auth requires a configured token provider for profile $profileId"
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
}

internal open class UpstreamRelaySupervisorFactory
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
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
            relayProfileStore,
            relayCredentialStore,
            NoopMasquePrivacyPassProvider(),
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
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                masquePrivacyPassProvider = masquePrivacyPassProvider,
                tlsFingerprintProfileProvider = tlsFingerprintProfileProvider,
            )
    }
