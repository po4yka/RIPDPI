@file:Suppress("LongMethod", "TooGenericExceptionCaught", "SwallowedException")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig
import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpFactory
import com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig
import com.poyka.ripdpi.core.RipDpiWarpRuntime
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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
        private val enrollmentOrchestrator: WarpEnrollmentOrchestrator,
    ) : WarpRuntimeConfigResolver {
        override suspend fun resolve(config: RipDpiWarpConfig): ResolvedRipDpiWarpConfig {
            require(config.enabled) { "WARP runtime requested while disabled" }
            val profileId =
                appSettingsRepository
                    .snapshot()
                    .warpProfileId
                    .ifBlank { error("No active WARP profile configured") }
            val initialCredentials = credentialStore.load(profileId)
            val initialEndpoint =
                if (config.endpointSelectionMode == "manual") {
                    null
                } else {
                    endpointStore.load(profileId, GlobalWarpEndpointScopeKey)
                }
            if (needsRefresh(initialCredentials, initialEndpoint)) {
                try {
                    enrollmentOrchestrator.refreshActiveProfile(GlobalWarpEndpointScopeKey)
                } catch (error: WarpProvisioningException.AuthFailure) {
                    throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed(
                            error.message ?: "WARP provisioning authentication failed",
                        ),
                    )
                } catch (error: WarpProvisioningException.MalformedResponse) {
                    throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed(
                            error.message ?: "WARP provisioning returned malformed data",
                        ),
                    )
                } catch (error: Exception) {
                    throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed(
                            error.message ?: "WARP provisioning refresh failed",
                        ),
                    )
                }
            }
            val credentials =
                credentialStore.load(profileId)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("Missing WARP credentials for profile $profileId"),
                    )
            val endpoint =
                when (config.endpointSelectionMode) {
                    "manual" -> {
                        config.manualEndpoint.toResolvedEndpoint()
                    }

                    else -> {
                        endpointStore
                            .load(profileId, GlobalWarpEndpointScopeKey)
                            ?.toResolvedEndpoint()
                    }
                } ?: throw ServiceStartupRejectedException(
                    FailureReason.WarpEndpointUnavailable("Missing WARP endpoint for profile $profileId"),
                )
            val privateKey =
                credentials.privateKey?.takeIf(String::isNotBlank)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("WARP private key missing"),
                    )
            val publicKey =
                credentials.publicKey?.takeIf(String::isNotBlank)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("WARP public key missing"),
                    )
            val peerPublicKey =
                credentials.peerPublicKey?.takeIf(String::isNotBlank)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("WARP peer public key missing"),
                    )
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

        private fun needsRefresh(
            credentials: com.poyka.ripdpi.data.WarpCredentials?,
            endpoint: com.poyka.ripdpi.data.WarpEndpointCacheEntry?,
        ): Boolean =
            credentials == null ||
                credentials.privateKey.isNullOrBlank() ||
                credentials.publicKey.isNullOrBlank() ||
                credentials.peerPublicKey.isNullOrBlank() ||
                endpoint == null

        private fun com.poyka.ripdpi.data.WarpEndpointCacheEntry.toResolvedEndpoint(): ResolvedRipDpiWarpEndpoint =
            ResolvedRipDpiWarpEndpoint(
                host = host.orEmpty(),
                ipv4 = ipv4,
                ipv6 = ipv6,
                port = port,
                source = source,
            )

        private fun RipDpiWarpManualEndpointConfig.toResolvedEndpoint(): ResolvedRipDpiWarpEndpoint {
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
    private var stopRequested: Boolean = false
    private var exitReporting: AtomicBoolean? = null

    val runtime: RipDpiWarpRuntime?
        get() = warpRuntime

    suspend fun start(
        config: RipDpiWarpConfig,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        check(warpJob == null) { "WARP fields not null" }
        val runtime = warpFactory.create()
        val resolvedConfig = runtimeConfigResolver.resolve(config)
        warpRuntime = runtime
        stopRequested = false
        val shouldReportExit = AtomicBoolean(true)
        exitReporting = shouldReportExit

        val exitCause = CompletableDeferred<SupervisorExitCause>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = runCatching { runtime.start(resolvedConfig) }
                    exitCause.complete(result.toSupervisorExitCause(stopRequested = stopRequested))
                } finally {
                    if (!exitCause.isCompleted) {
                        exitCause.complete(
                            Result
                                .failure<Int>(CancellationException("WARP job cancelled"))
                                .toSupervisorExitCause(stopRequested = stopRequested),
                        )
                    }
                }
            }
        warpJob = job

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                if (!shouldReportExit.get()) {
                    return@launch
                }
                onUnexpectedExit(exitCause.await())
            }
        }

        @Suppress("TooGenericExceptionCaught")
        try {
            runtime.awaitReady()
        } catch (readinessError: Exception) {
            shouldReportExit.set(false)
            try {
                stopRequested = true
                runCatching { runtime.stop() }
                job.join()
            } finally {
                warpJob = null
                warpRuntime = null
                exitReporting = null
                stopRequested = false
            }
            val startupCause =
                (exitCause.await() as? SupervisorExitCause.StartupFailure)
                    ?: SupervisorExitCause.StartupFailure(readinessError)
            throw SupervisorStartupFailureException(startupCause)
        }
    }

    suspend fun stop() {
        val runtime = warpRuntime
        if (runtime == null) {
            warpJob = null
            exitReporting = null
            stopRequested = false
            return
        }

        try {
            stopRequested = true
            runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                warpJob?.join()
            }
        } finally {
            warpJob = null
            warpRuntime = null
            exitReporting = null
            stopRequested = false
        }
    }

    fun detach() {
        exitReporting?.set(false)
        warpJob = null
        warpRuntime = null
        exitReporting = null
        stopRequested = false
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
    abstract fun bindWarpRuntimeConfigResolver(resolver: DefaultWarpRuntimeConfigResolver): WarpRuntimeConfigResolver
}
