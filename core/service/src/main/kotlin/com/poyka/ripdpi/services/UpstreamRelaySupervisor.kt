package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.TlsFingerprintProfileNativeDefault
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
    private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider =
        object : OwnedTlsFingerprintProfileProvider {
            override fun currentProfile(): String = TlsFingerprintProfileNativeDefault
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
        val effectiveConfig = mergeConfig(config, storedProfile)
        val credentials = relayCredentialStore.load(profileId)
        validateCredentials(profileId, effectiveConfig.kind, effectiveConfig.masqueCloudflareMode, credentials)
        validateSupportedFeatures(profileId, effectiveConfig, credentials)
        return ResolvedRipDpiRelayConfig(
            enabled = effectiveConfig.enabled,
            kind = effectiveConfig.kind,
            profileId = profileId,
            server = effectiveConfig.server,
            serverPort = effectiveConfig.serverPort,
            serverName = effectiveConfig.serverName,
            realityPublicKey = effectiveConfig.realityPublicKey,
            realityShortId = effectiveConfig.realityShortId,
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
            tlsFingerprintProfile = tlsFingerprintProfileProvider.currentProfile(),
            masqueAuthMode = credentials?.masqueAuthMode,
            masqueAuthToken = credentials?.masqueAuthToken,
            masqueCloudflareClientId = credentials?.masqueCloudflareClientId,
            masqueCloudflareKeyId = credentials?.masqueCloudflareKeyId,
            masqueCloudflarePrivateKeyPem = credentials?.masqueCloudflarePrivateKeyPem,
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
        cloudflareMasqueMode: Boolean,
        credentials: RelayCredentialRecord?,
    ) {
        val hasMasqueCloudflareKeys =
            !credentials?.masqueCloudflareClientId.isNullOrBlank() &&
                !credentials?.masqueCloudflareKeyId.isNullOrBlank() &&
                !credentials?.masqueCloudflarePrivateKeyPem.isNullOrBlank()
        val isValid =
            when (relayKind) {
                RelayKindVlessReality -> {
                    !credentials?.vlessUuid.isNullOrBlank()
                }

                RelayKindHysteria2 -> {
                    !credentials?.hysteriaPassword.isNullOrBlank()
                }

                RelayKindChainRelay -> {
                    !credentials?.chainEntryUuid.isNullOrBlank() &&
                        !credentials?.chainExitUuid.isNullOrBlank()
                }

                RelayKindMasque -> {
                    if (cloudflareMasqueMode) {
                        hasMasqueCloudflareKeys
                    } else {
                        !credentials?.masqueAuthToken.isNullOrBlank()
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
        credentials: RelayCredentialRecord?,
    ) {
        require(!config.udpEnabled) { "Relay UDP mode is not implemented for profile $profileId" }
        if (config.kind == RelayKindHysteria2) {
            require(credentials?.hysteriaSalamanderKey.isNullOrBlank()) {
                "Hysteria2 Salamander obfuscation is not implemented for profile $profileId"
            }
        }
        if (config.kind == RelayKindMasque) {
            require(!config.masqueCloudflareMode) {
                "Cloudflare MASQUE auth is not implemented for profile $profileId"
            }
        }
    }
}

internal open class UpstreamRelaySupervisorFactory
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider =
            object : OwnedTlsFingerprintProfileProvider {
                override fun currentProfile(): String = TlsFingerprintProfileNativeDefault
            },
    ) {
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
                tlsFingerprintProfileProvider = tlsFingerprintProfileProvider,
            )
    }
