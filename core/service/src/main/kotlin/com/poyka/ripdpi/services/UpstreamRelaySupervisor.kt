@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal data class UpstreamRelayResolverDependencies(
    val cloudflareMasqueGeohashResolver: CloudflareMasqueGeohashResolver =
        object : CloudflareMasqueGeohashResolver {
            override suspend fun resolveHeaderValue(): String? = null
        },
    val masquePrivacyPassProvider: MasquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
    val tlsFingerprintProfileProvider: OwnedTlsFingerprintProfileProvider =
        object : OwnedTlsFingerprintProfileProvider {
            override fun currentProfile(): String = TlsFingerprintProfileChromeStable
        },
    val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider =
        object : RuntimeExperimentSelectionProvider {
            override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
        },
    val torRuntimeProviders: TorRelayRuntimeProviders = TorRelayRuntimeProviders(),
)

internal class UpstreamRelaySupervisor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val relayFactory: RipDpiRelayFactory,
    private val naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
    private val googleAppsScriptRelayRuntimeFactory: GoogleAppsScriptRelayRuntimeFactory =
        object : GoogleAppsScriptRelayRuntimeFactory {
            override fun create(): RipDpiRelayRuntime =
                error("Google Apps Script relay runtime factory is not configured")
        },
    private val cloudflarePublishRuntimeFactory: CloudflarePublishRuntimeFactory =
        object : CloudflarePublishRuntimeFactory {
            override fun create(): RipDpiRelayRuntime = error("Cloudflare publish runtime factory is not configured")
        },
    private val pluggableTransportRuntimeFactory: PluggableTransportRuntimeFactory =
        object : PluggableTransportRuntimeFactory {
            override fun create(): RipDpiRelayRuntime = error("Pluggable transport runtime factory is not configured")
        },
    private val runtimeConfigResolver: UpstreamRelayRuntimeConfigResolver,
    private val networkMode: RelayRuntimeNetworkMode = RelayRuntimeNetworkMode.Proxy,
    private val initialRelayRaceRunnerFactory: InitialRelayRaceRunnerFactory = InitialRelayRaceRunnerFactory(),
    private val stopTimeoutMillis: Long = 5_000L,
) {
    constructor(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        relayFactory: RipDpiRelayFactory,
        naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
        googleAppsScriptRelayRuntimeFactory: GoogleAppsScriptRelayRuntimeFactory =
            object : GoogleAppsScriptRelayRuntimeFactory {
                override fun create(): RipDpiRelayRuntime =
                    error("Google Apps Script relay runtime factory is not configured")
            },
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
        resolverDependencies: UpstreamRelayResolverDependencies = UpstreamRelayResolverDependencies(),
        networkMode: RelayRuntimeNetworkMode = RelayRuntimeNetworkMode.Proxy,
    ) : this(
        scope = scope,
        dispatcher = dispatcher,
        relayFactory = relayFactory,
        naiveProxyRuntimeFactory = naiveProxyRuntimeFactory,
        googleAppsScriptRelayRuntimeFactory = googleAppsScriptRelayRuntimeFactory,
        cloudflarePublishRuntimeFactory = cloudflarePublishRuntimeFactory,
        pluggableTransportRuntimeFactory = pluggableTransportRuntimeFactory,
        runtimeConfigResolver =
            createDefaultUpstreamRelayRuntimeConfigResolver(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                cloudflareMasqueGeohashResolver = resolverDependencies.cloudflareMasqueGeohashResolver,
                masquePrivacyPassProvider = resolverDependencies.masquePrivacyPassProvider,
                tlsFingerprintProfileProvider = resolverDependencies.tlsFingerprintProfileProvider,
                runtimeExperimentSelectionProvider = resolverDependencies.runtimeExperimentSelectionProvider,
                torRuntimePathProvider = resolverDependencies.torRuntimeProviders.pathProvider,
                torPluggableTransportProvider = resolverDependencies.torRuntimeProviders.pluggableTransportProvider,
            ),
        networkMode = networkMode,
    )

    private var activeSlot: RelayRuntimeSlot? = null

    suspend fun start(
        requirements: EgressRequirements,
        config: RipDpiRelayConfig,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig = OwnedRelayQuicMigrationConfig(),
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ) {
        check(activeSlot == null) { "Relay runtime is already active" }
        val slot =
            startSlot(
                config = config,
                quicMigrationConfig = quicMigrationConfig,
                localPortOverride = null,
                requirements = requirements,
                onUnexpectedExit = onUnexpectedExit,
            )
        promoteSlot(slot)
    }

    suspend fun startRace(
        plan: InitialRelayRacePlan,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig = OwnedRelayQuicMigrationConfig(),
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
        onState: (InitialTransportRaceSnapshot) -> Unit = {},
    ): PromotedRelayRuntime =
        initialRelayRaceRunnerFactory
            .create(
                startCandidate = { candidate ->
                    startSlot(
                        config =
                            RipDpiRelayConfig(
                                enabled = true,
                                kind = candidate.relayKind,
                                profileId = candidate.profileId,
                            ),
                        quicMigrationConfig = quicMigrationConfig,
                        localPortOverride = EphemeralPort,
                        requirements = plan.requirements,
                        onUnexpectedExit = onUnexpectedExit,
                    )
                },
                promoteCandidate = ::promoteSlot,
                stopDiscardedCandidates = ::stopDiscardedSlots,
            ).run(plan, onState)

    private suspend fun startSlot(
        config: RipDpiRelayConfig,
        quicMigrationConfig: OwnedRelayQuicMigrationConfig,
        localPortOverride: Int?,
        requirements: EgressRequirements,
        onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    ): RelayRuntimeSlot {
        val resolvedConfig =
            runtimeConfigResolver
                .resolve(config, quicMigrationConfig)
                .let { resolved ->
                    if (localPortOverride == null) resolved else resolved.copy(localSocksPort = localPortOverride)
                }.withNetworkMode(networkMode)
        validateRelayEgressRequirements(resolvedConfig, requirements)
        val runtime = createRuntime(resolvedConfig)
        val stopRequested = AtomicBoolean(false)
        val shouldReportExit = AtomicBoolean(false)

        val exitCause = CompletableDeferred<SupervisorExitCause>()
        val job =
            scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = runCatching { runtime.start(resolvedConfig) }
                    exitCause.complete(result.toSupervisorExitCause(stopRequested = stopRequested.get()))
                } finally {
                    if (!exitCause.isCompleted) {
                        exitCause.complete(
                            Result
                                .failure<Int>(CancellationException("Relay job cancelled"))
                                .toSupervisorExitCause(stopRequested = stopRequested.get()),
                        )
                    }
                }
            }
        val slot =
            RelayRuntimeSlot(
                runtime = runtime,
                job = job,
                exitCause = exitCause,
                stopRequested = stopRequested,
                shouldReportExit = shouldReportExit,
                exitReported = AtomicBoolean(false),
                onUnexpectedExit = onUnexpectedExit,
                udpEnabled = resolvedConfig.udpEnabled,
            )

        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                if (activeSlot !== slot) {
                    return@launch
                }
                if (!shouldReportExit.get()) {
                    return@launch
                }
                reportExitIfNeeded(slot)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        try {
            runtime.awaitReady()
            if (localPortOverride != null) {
                slot.endpoint = resolveLocalProxyEndpoint(runtime.pollTelemetry(), authToken = null)
            }
            return slot
        } catch (readinessError: CancellationException) {
            shouldReportExit.set(false)
            stopRequested.set(true)
            runCatching { runtime.stop() }
            job.join()
            throw readinessError
        } catch (
            @Suppress("TooGenericExceptionCaught") readinessError: Exception,
        ) {
            shouldReportExit.set(false)
            stopRequested.set(true)
            runCatching { runtime.stop() }
            job.join()
            val startupCause =
                (exitCause.await() as? SupervisorExitCause.StartupFailure)
                    ?: SupervisorExitCause.StartupFailure(readinessError)
            throw SupervisorStartupFailureException(startupCause)
        }
    }

    suspend fun stop() {
        val slot = activeSlot ?: return

        try {
            slot.stopRequested.set(true)
            slot.runtime.stop()
            withTimeoutOrNull(stopTimeoutMillis) {
                slot.job.join()
            }
            reportExitIfNeeded(slot)
        } finally {
            if (activeSlot === slot) {
                activeSlot = null
            }
        }
    }

    fun detach() {
        activeSlot?.shouldReportExit?.set(false)
        activeSlot = null
    }

    suspend fun pollTelemetry(): RuntimeTelemetryOutcome {
        val runtime = activeSlot?.runtime ?: return RuntimeTelemetryOutcome.NoData
        return runCatching { runtime.pollTelemetry() }
            .fold(
                onSuccess = { RuntimeTelemetryOutcome.Snapshot(it) },
                onFailure = { error ->
                    RuntimeTelemetryOutcome.EngineError(
                        message = error.message ?: "Relay telemetry polling failed",
                        causeClass = error.javaClass.name,
                    )
                },
            )
    }

    private fun createRuntime(config: ResolvedRipDpiRelayConfig): RipDpiRelayRuntime =
        if (config.kind == RelayKindNaiveProxy) {
            naiveProxyRuntimeFactory.create()
        } else if (config.kind == RelayKindGoogleAppsScript) {
            googleAppsScriptRelayRuntimeFactory.create()
        } else if (
            config.kind == RelayKindCloudflareTunnel &&
            config.cloudflareTunnelMode == RelayCloudflareTunnelModePublishLocalOrigin
        ) {
            cloudflarePublishRuntimeFactory.create()
        } else if (isPluggableTransportRelay(config.kind)) {
            pluggableTransportRuntimeFactory.create()
        } else {
            relayFactory.create()
        }

    private suspend fun promoteSlot(slot: RelayRuntimeSlot) {
        slot.shouldReportExit.set(true)
        activeSlot = slot
        if (!slot.job.isActive) {
            reportExitIfNeeded(slot)
        }
    }

    private suspend fun reportExitIfNeeded(slot: RelayRuntimeSlot) {
        if (
            activeSlot === slot &&
            slot.shouldReportExit.get() &&
            slot.exitReported.compareAndSet(false, true)
        ) {
            slot.onUnexpectedExit(slot.exitCause.await())
        }
    }

    private suspend fun stopDiscardedSlots(
        slots: Collection<RelayRuntimeSlot>,
        winner: RelayRuntimeSlot?,
    ) {
        slots.filter { it !== winner }.forEach { slot ->
            slot.shouldReportExit.set(false)
            slot.stopRequested.set(true)
            runCatching { slot.runtime.stop() }
            withTimeoutOrNull(stopTimeoutMillis) { slot.job.join() }
        }
    }

    private companion object {
        const val EphemeralPort = 0
    }
}

internal data class TorRelayRuntimeProviders(
    val pathProvider: TorRuntimePathProvider = LocalTorRuntimePathProvider(),
    val pluggableTransportProvider: TorPluggableTransportProvider = UnconfiguredTorPluggableTransportProvider(),
)

internal open class UpstreamRelaySupervisorFactory
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val naiveProxyRuntimeFactory: NaiveProxyRuntimeFactory,
        private val googleAppsScriptRelayRuntimeFactory: GoogleAppsScriptRelayRuntimeFactory,
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
            object : GoogleAppsScriptRelayRuntimeFactory {
                override fun create(): RipDpiRelayRuntime =
                    error("Google Apps Script relay runtime factory is not configured in this test harness")
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
                torRuntimePathProvider = LocalTorRuntimePathProvider(),
                torPluggableTransportProvider = UnconfiguredTorPluggableTransportProvider(),
            ),
        )

        open fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            networkMode: RelayRuntimeNetworkMode = RelayRuntimeNetworkMode.Proxy,
        ): UpstreamRelaySupervisor =
            UpstreamRelaySupervisor(
                scope = scope,
                dispatcher = dispatcher,
                relayFactory = relayFactory,
                naiveProxyRuntimeFactory = naiveProxyRuntimeFactory,
                googleAppsScriptRelayRuntimeFactory = googleAppsScriptRelayRuntimeFactory,
                cloudflarePublishRuntimeFactory = cloudflarePublishRuntimeFactory,
                pluggableTransportRuntimeFactory = pluggableTransportRuntimeFactory,
                runtimeConfigResolver = runtimeConfigResolver,
                networkMode = networkMode,
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
