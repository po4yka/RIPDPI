package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig
import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpFactory
import com.poyka.ripdpi.core.RipDpiWarpRuntime
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

internal interface WarpRuntimeConfigResolver {
    suspend fun resolve(config: RipDpiWarpConfig): ResolvedRipDpiWarpConfig
}

@Singleton
internal class DefaultWarpRuntimeConfigResolver
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val credentialStore: WarpCredentialStore,
        private val endpointStore: WarpEndpointStore,
    ) : WarpRuntimeConfigResolver {
        override suspend fun resolve(config: RipDpiWarpConfig): ResolvedRipDpiWarpConfig {
            require(config.enabled) { "WARP runtime requested while disabled" }
            val profileId = appSettingsRepository.snapshot().warpProfileId.ifBlank { error("No active WARP profile configured") }
            val credentials =
                credentialStore.load(profileId)
                    ?: error("Missing WARP credentials for profile $profileId")
            val endpoint =
                when (config.endpointSelectionMode) {
                    "manual" -> config.manualEndpoint.toResolvedEndpoint()
                    else ->
                        endpointStore
                            .load(profileId, GlobalWarpEndpointScopeKey)
                            ?.toResolvedEndpoint()
                } ?: error("Missing WARP endpoint for profile $profileId")
            val privateKey = credentials.privateKey?.takeIf(String::isNotBlank) ?: error("WARP private key missing")
            val publicKey = credentials.publicKey?.takeIf(String::isNotBlank) ?: error("WARP public key missing")
            val peerPublicKey =
                credentials.peerPublicKey?.takeIf(String::isNotBlank)
                    ?: error("WARP peer public key missing")
            return ResolvedRipDpiWarpConfig(
                enabled = config.enabled,
                profileId = profileId,
                accountKind = credentials.accountKind,
                deviceId = credentials.deviceId,
                accessToken = credentials.accessToken,
                clientId = credentials.clientId,
                privateKey = privateKey,
                publicKey = publicKey,
                peerPublicKey = peerPublicKey,
                interfaceAddressV4 = credentials.interfaceAddressV4,
                interfaceAddressV6 = credentials.interfaceAddressV6,
                endpoint = endpoint,
                routeMode = config.routeMode,
                routeHosts = config.routeHosts,
                builtInRulesEnabled = config.builtInRulesEnabled,
                endpointSelectionMode = config.endpointSelectionMode,
                manualEndpoint = config.manualEndpoint,
                scannerEnabled = config.scannerEnabled,
                scannerParallelism = config.scannerParallelism,
                scannerMaxRttMs = config.scannerMaxRttMs,
                amnezia = config.amnezia,
                localSocksHost = config.localSocksHost,
                localSocksPort = config.localSocksPort,
            )
        }

        private fun com.poyka.ripdpi.data.WarpEndpointCacheEntry.toResolvedEndpoint(): ResolvedRipDpiWarpEndpoint =
            ResolvedRipDpiWarpEndpoint(
                host = host.orEmpty(),
                ipv4 = ipv4,
                ipv6 = ipv6,
                port = port,
                source = source,
            )

        private fun com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig.toResolvedEndpoint(): ResolvedRipDpiWarpEndpoint {
            val normalizedHost = host.ifBlank { ipv4.ifBlank { ipv6 } }
            require(normalizedHost.isNotBlank()) { "Manual WARP endpoint host is blank" }
            return ResolvedRipDpiWarpEndpoint(
                host = normalizedHost,
                ipv4 = ipv4.takeIf(String::isNotBlank),
                ipv6 = ipv6.takeIf(String::isNotBlank),
                port = port,
                source = "manual",
            )
        }
    }

internal class WarpRuntimeSupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val warpFactory: RipDpiWarpFactory,
    private val runtimeConfigResolver: WarpRuntimeConfigResolver,
    private val stopTimeoutMillis: Long = 5_000L,
) {
    private var warpRuntime: RipDpiWarpRuntime? = null
    private var warpJob: Job? = null

    val runtime: RipDpiWarpRuntime?
        get() = warpRuntime

    suspend fun start(
        config: RipDpiWarpConfig,
        onUnexpectedExit: suspend (Result<Int>) -> Unit,
    ) {
        check(warpJob == null) { "WARP fields not null" }
        val runtime = warpFactory.create()
        val resolvedConfig = runtimeConfigResolver.resolve(config)
        warpRuntime = runtime

        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    exitResult.complete(runCatching { runtime.start(resolvedConfig) })
                } finally {
                    if (!exitResult.isCompleted) {
                        exitResult.complete(Result.failure(IllegalStateException("WARP job cancelled")))
                    }
                }
            }
        warpJob = job

        try {
            runtime.awaitReady()
        } catch (readinessError: Exception) {
            try {
                runCatching { runtime.stop() }
                job.join()
            } finally {
                warpJob = null
                warpRuntime = null
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
        val runtime = warpRuntime
        if (runtime == null) {
            warpJob = null
            return
        }

        try {
            runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                warpJob?.join()
            }
        } finally {
            warpJob = null
            warpRuntime = null
        }
    }

    fun detach() {
        warpJob = null
        warpRuntime = null
    }

    suspend fun pollTelemetry(): NativeRuntimeSnapshot? = runCatching { warpRuntime?.pollTelemetry() }.getOrNull()
}

internal open class WarpRuntimeSupervisorFactory
    @Inject
    constructor(
        private val warpFactory: RipDpiWarpFactory,
        private val runtimeConfigResolver: WarpRuntimeConfigResolver,
    ) {
        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
        ): WarpRuntimeSupervisor =
            WarpRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                warpFactory = warpFactory,
                runtimeConfigResolver = runtimeConfigResolver,
            )
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WarpRuntimeConfigResolverModule {
    @Binds
    @Singleton
    abstract fun bindWarpRuntimeConfigResolver(
        resolver: DefaultWarpRuntimeConfigResolver,
    ): WarpRuntimeConfigResolver
}
