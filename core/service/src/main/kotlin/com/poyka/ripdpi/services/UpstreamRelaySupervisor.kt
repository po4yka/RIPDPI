@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
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
    private val runtimeConfigResolver: UpstreamRelayRuntimeConfigResolver,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    constructor(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        relayFactory: RipDpiRelayFactory,
        naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
        cloudflarePublishRuntimeFactory: CloudflarePublishRuntimeFactory =
            object : CloudflarePublishRuntimeFactory {
                override fun create(): RipDpiRelayRuntime =
                    error("Cloudflare publish runtime factory is not configured")
            },
        pluggableTransportRuntimeFactory: PluggableTransportRuntimeFactory =
            object : PluggableTransportRuntimeFactory {
                override fun create(): RipDpiRelayRuntime =
                    error("Pluggable transport runtime factory is not configured")
            },
        relayProfileStore: RelayProfileStore,
        relayCredentialStore: RelayCredentialStore,
        cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver =
            object : CloudflareMasqueGeohashResolver {
                override suspend fun resolveHeaderValue(): String? = null
            },
        masquePrivacyPassProvider: MasquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
        tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider =
            object : OwnedTlsFingerprintProfileProvider {
                override fun currentProfile(): String = TlsFingerprintProfileChromeStable
            },
        runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider =
            object : RuntimeExperimentSelectionProvider {
                override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
            },
        stopTimeoutMillis: Long = 5_000L,
    ) : this(
        scope = scope,
        dispatcher = dispatcher,
        relayFactory = relayFactory,
        naiveProxyRuntimeFactory = naiveProxyRuntimeFactory,
        cloudflarePublishRuntimeFactory = cloudflarePublishRuntimeFactory,
        pluggableTransportRuntimeFactory = pluggableTransportRuntimeFactory,
        runtimeConfigResolver =
            createDefaultUpstreamRelayRuntimeConfigResolver(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                cloudflareMasqueGeohashResolver = cloudflareMasqueGeohashResolver,
                masquePrivacyPassProvider = masquePrivacyPassProvider,
                tlsFingerprintProfileProvider = tlsFingerprintProfileProvider,
                runtimeExperimentSelectionProvider = runtimeExperimentSelectionProvider,
            ),
        stopTimeoutMillis = stopTimeoutMillis,
    )

    private var relayRuntime: RipDpiRelayRuntime? = null
    private var relayJob: Job? = null

    suspend fun start(
        config: RipDpiRelayConfig,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig = OwnedRelayQuicMigrationConfig(),
        onUnexpectedExit: suspend (Result<Int>) -> Unit,
    ) {
        check(relayJob == null) { "Relay fields not null" }
        val resolvedConfig = runtimeConfigResolver.resolve(config, quicMigrationConfig)
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
}

internal open class UpstreamRelaySupervisorFactory
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
        private val cloudflarePublishRuntimeFactory: CloudflarePublishRuntimeFactory,
        private val pluggableTransportRuntimeFactory: PluggableTransportRuntimeFactory,
        private val runtimeConfigResolver: UpstreamRelayRuntimeConfigResolver,
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
            createDefaultUpstreamRelayRuntimeConfigResolver(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                cloudflareMasqueGeohashResolver =
                    object : CloudflareMasqueGeohashResolver {
                        override suspend fun resolveHeaderValue(): String? = null
                    },
                masquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
                tlsFingerprintProfileProvider =
                    object : OwnedTlsFingerprintProfileProvider {
                        override fun currentProfile(): String = TlsFingerprintProfileChromeStable
                    },
                runtimeExperimentSelectionProvider =
                    object : RuntimeExperimentSelectionProvider {
                        override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                    },
            ),
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
                runtimeConfigResolver = runtimeConfigResolver,
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
