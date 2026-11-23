package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.LocalSocksCredentialRegistry
import com.poyka.ripdpi.core.LocalSocksCredentials
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import com.poyka.ripdpi.data.diagnostics.nativeFieldFailureStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Owns an ephemeral relay solely for an HTTP payload measurement; never activates a stored profile. */
@Singleton
class CandidateRelayPayloadProbe internal constructor(
    private val resolve: suspend (
        RelayProfileRecord,
        RelayCredentialRecord,
        CandidateRelayProbeEnvironment,
    ) -> ResolvedRipDpiRelayConfig,
    private val runtimeFactory: RipDpiRelayFactory,
    private val httpProbe: CandidateHttpPayloadProbe,
    private val environment: suspend () -> CandidateRelayProbeEnvironment,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CandidateRelayMeasurements {
    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var pending: CandidateSession? = null
    private val pendingCleanupState = MutableStateFlow(false)
    override val cleanupPending = pendingCleanupState.asStateFlow()

    @Inject
    internal constructor(
        configuration: CandidateRelayProbeConfiguration,
        runtimeFactory: RipDpiRelayFactory,
        httpProbe: CandidateHttpPayloadProbe,
    ) : this(
        resolve = configuration::prepare,
        runtimeFactory = runtimeFactory,
        httpProbe = httpProbe,
        environment = configuration::capture,
    )

    override suspend fun captureEnvironment(): CandidateRelayProbeEnvironment = environment()

    override suspend fun retryCleanup(): Boolean =
        mutex.withLock {
            val current = pending ?: return@withLock true
            val released = withContext(NonCancellable) { stopSession(current) }
            currentCoroutineContext().ensureActive()
            released
        }

    /**
     * Cancel-safe measurement: cleanup has a bounded wait. Incomplete native stop/start tasks
     * remain owned here and prevent any new candidate until cleanup finishes.
     */
    override suspend fun measure(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        probeUrl: String,
    ): CandidateRelayMeasurement =
        measureUsing(profile, credentials, HttpDeadlineMillis) { endpoint ->
            httpProbe.probe(endpoint, probeUrl)
        }

    suspend fun measurePayload(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        policy: PayloadFailoverPolicy,
    ): CandidateRelayMeasurement =
        measureUsing(
            profile,
            credentials,
            policy.timeoutSeconds * policy.probes.size * MillisecondsPerSecond *
                if (policy.quality == null) 1 else 2,
        ) { endpoint ->
            httpProbe.probePayload(endpoint, policy)
        }

    private suspend fun measureUsing(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        httpDeadlineMillis: Long,
        probe: suspend (RelayProbeEndpoint) -> CandidateHttpPayloadEvidence,
    ): CandidateRelayMeasurement =
        mutex.withLock {
            val previous = pending
            if (previous != null) {
                val released = withContext(NonCancellable) { stopSession(previous) }
                currentCoroutineContext().ensureActive()
                if (!released) return@withLock CandidateRelayMeasurement.CleanupPending
            }
            if (profile.kind == com.poyka.ripdpi.data.RelayKindOff ||
                (
                    profile.kind == com.poyka.ripdpi.data.RelayKindVless &&
                        profile.vlessTransport != com.poyka.ripdpi.data.RelayVlessTransportXhttp
                )
            ) {
                return@withLock CandidateRelayMeasurement.Unsupported
            }
            val before: CandidateRelayProbeEnvironment
            val config: ResolvedRipDpiRelayConfig
            val listenerCredentials = LocalSocksCredentials.generate()
            try {
                before = captureEnvironment()
                config =
                    resolve(profile, credentials, before)
                        .copy(
                            localSocksHost = "127.0.0.1",
                            localSocksPort = 0,
                            localSocksUsername = listenerCredentials.username,
                            localSocksPassword = listenerCredentials.password,
                        ).withNetworkMode(
                            if (before.vpnProtectionRequired) {
                                RelayRuntimeNetworkMode.Vpn
                            } else {
                                RelayRuntimeNetworkMode.Proxy
                            },
                        )
                if (before != captureEnvironment()) return@withLock CandidateRelayMeasurement.EnvironmentChanged
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnsupportedOperationException) {
                return@withLock CandidateRelayMeasurement.Unsupported
            } catch (rejected: ServiceStartupRejectedException) {
                // Stable code only: the message can name the server, and R8 renames reason classes.
                val reason = rejected.reason.startupJournalKind()
                Logger.w(
                    tag = LogTags.SERVICE,
                ) { "Candidate relay check rejected: kind=${profile.kind} reason=$reason" }
                return@withLock CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Configuration)
            } catch (_: Exception) {
                Logger.w(
                    tag = LogTags.SERVICE,
                ) { "Candidate relay check rejected: kind=${profile.kind} reason=configuration" }
                return@withLock CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Configuration)
            }
            measureResolved(config, listenerCredentials, before, httpDeadlineMillis, probe)
        }

    private fun createRuntimeOrNull() =
        try {
            runtimeFactory.create()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun measureResolved(
        config: ResolvedRipDpiRelayConfig,
        listenerCredentials: LocalSocksCredentials,
        before: CandidateRelayProbeEnvironment,
        httpDeadlineMillis: Long,
        probe: suspend (RelayProbeEndpoint) -> CandidateHttpPayloadEvidence,
    ): CandidateRelayMeasurement {
        val runtime = createRuntimeOrNull() ?: return CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Ready)
        val job =
            runtimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    runtime.start(config)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Readiness and the exit check reject the attempt; ownership still requires cleanup.
                }
            }
        val session = CandidateSession(runtime, job)
        pending = session
        var outcome: CandidateRelayMeasurement
        var stage = CandidateMeasurementStage.Ready
        try {
            val ready =
                withTimeoutOrNull(ReadyDeadlineMillis) {
                    runtime.awaitReady(ReadyDeadlineMillis)
                    true
                } == true
            outcome =
                if (!ready) {
                    CandidateRelayMeasurement.TimedOut(stage, runtime.nativeFailureStage())
                } else if (!job.isActive) {
                    CandidateRelayMeasurement.Failed(stage, runtime.nativeFailureStage())
                } else {
                    val endpoint = resolveLocalProxyEndpoint(runtime.pollTelemetry(), authToken = null)
                    require(endpoint.host == "127.0.0.1" || endpoint.host == "::1")
                    stage = CandidateMeasurementStage.Http
                    LocalSocksCredentialRegistry.register(endpoint.host, endpoint.port, listenerCredentials)
                    val result =
                        try {
                            withTimeoutOrNull(httpDeadlineMillis) {
                                probe(RelayProbeEndpoint(endpoint.host, endpoint.port))
                            }
                        } finally {
                            LocalSocksCredentialRegistry.unregister(endpoint.host, endpoint.port, listenerCredentials)
                        }
                    when {
                        result == null -> {
                            CandidateRelayMeasurement.TimedOut(stage, runtime.nativeFailureStage())
                        }

                        before != captureEnvironment() -> {
                            CandidateRelayMeasurement.EnvironmentChanged
                        }

                        !result.succeeded && result.timedOut -> {
                            CandidateRelayMeasurement.TimedOut(
                                stage,
                                result.failureStage ?: runtime.nativeFailureStage(),
                            )
                        }

                        !result.succeeded || !job.isActive -> {
                            CandidateRelayMeasurement.Failed(stage, result.failureStage ?: runtime.nativeFailureStage())
                        }

                        else -> {
                            CandidateRelayMeasurement.Succeeded(
                                result.latencyMillis,
                                CandidateConfigurationProofs.relay(config),
                                result.quality,
                            )
                        }
                    }
                }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stopSession(session) }
            throw cancelled
        } catch (_: Exception) {
            outcome = CandidateRelayMeasurement.Failed(stage)
        }
        val released = withContext(NonCancellable) { stopSession(session) }
        currentCoroutineContext().ensureActive()
        return if (released) outcome else CandidateRelayMeasurement.CleanupPending
    }

    /**
     * The typed native failure of this attempt's own runtime, mapped by the shared stage table. It is never stale:
     * the runtime exists only for this attempt.
     */
    private suspend fun RipDpiRelayRuntime.nativeFailureStage(): FieldFailureStage? =
        try {
            pollTelemetry().lastFailureClass?.let { nativeFieldFailureStage(it, failureStage = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** Failed or incomplete stop retains the exact native handle; subsequent checks retry it first. */
    private suspend fun stopSession(session: CandidateSession): Boolean {
        val previous = session.cleanup
        val alreadyReleased =
            session.job.isCompleted && previous?.isCompleted == true &&
                runCatching { previous.await().isSuccess }.getOrDefault(false)
        if (alreadyReleased) {
            pending = null
            pendingCleanupState.value = false
            return true
        }
        val cleanup =
            previous?.takeIf { it.isActive } ?: runtimeScope
                .async { runCatching { session.runtime.stop() } }
                .also { session.cleanup = it }
        val completed =
            try {
                withTimeoutOrNull(CleanupDeadlineMillis) {
                    cleanup.await().getOrThrow()
                    session.job.join()
                    true
                } == true
            } catch (_: Exception) {
                false
            }
        pendingCleanupState.value = !completed
        if (completed) pending = null
        return completed
    }

    private data class CandidateSession(
        val runtime: RipDpiRelayRuntime,
        val job: Job,
        var cleanup: Deferred<Result<Unit>>? = null,
    )
}

// Two attempts of the 15 s HTTP call timeout: a transient failure is retried once.
private const val HttpDeadlineMillis = 30_000L
private const val ReadyDeadlineMillis = 5_000L
private const val CleanupDeadlineMillis = 5_000L

private const val MillisecondsPerSecond = 1_000L
