package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
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
            try {
                before = captureEnvironment()
                config =
                    resolve(profile, credentials, before)
                        .copy(localSocksHost = "127.0.0.1", localSocksPort = 0)
                        .withNetworkMode(
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
            } catch (_: Exception) {
                return@withLock CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Configuration)
            }
            measureResolved(config, probeUrl, before)
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
        probeUrl: String,
        before: CandidateRelayProbeEnvironment,
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
                    CandidateRelayMeasurement.TimedOut(stage)
                } else if (!job.isActive) {
                    CandidateRelayMeasurement.Failed(stage)
                } else {
                    val endpoint = resolveLocalProxyEndpoint(runtime.pollTelemetry(), authToken = null)
                    require(endpoint.host == "127.0.0.1" || endpoint.host == "::1")
                    stage = CandidateMeasurementStage.Http
                    val result =
                        withTimeoutOrNull(HttpDeadlineMillis) {
                            httpProbe.probe(RelayProbeEndpoint(endpoint.host, endpoint.port), probeUrl)
                        }
                    when {
                        result == null -> {
                            CandidateRelayMeasurement.TimedOut(stage)
                        }

                        before != captureEnvironment() -> {
                            CandidateRelayMeasurement.EnvironmentChanged
                        }

                        !result.succeeded || !job.isActive -> {
                            CandidateRelayMeasurement.Failed(stage)
                        }

                        else -> {
                            CandidateRelayMeasurement.Succeeded(
                                result.latencyMillis,
                                CandidateConfigurationProofs.relay(config),
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

private const val HttpDeadlineMillis = 15_000L
private const val ReadyDeadlineMillis = 5_000L
private const val CleanupDeadlineMillis = 5_000L
