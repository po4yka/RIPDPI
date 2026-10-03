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
    private val capabilityProbe: RelayCapabilityProbe,
    private val environment: suspend () -> CandidateRelayProbeEnvironment,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var pending: CandidateSession? = null

    @Inject
    internal constructor(
        configuration: CandidateRelayProbeConfiguration,
        runtimeFactory: RipDpiRelayFactory,
        capabilityProbe: RelayCapabilityProbe,
    ) : this(
        resolve = configuration::prepare,
        runtimeFactory = runtimeFactory,
        capabilityProbe = capabilityProbe,
        environment = configuration::capture,
    )

    suspend fun captureEnvironment(): CandidateRelayProbeEnvironment = environment()

    /**
     * Cancel-safe measurement: cleanup has a bounded wait. Incomplete native stop/start tasks
     * remain owned here and prevent any new candidate until cleanup finishes.
     */
    suspend fun measure(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        probeUrl: String,
    ): Long? =
        try {
            mutex.withLock {
                withTimeoutOrNull(CandidateDeadlineMillis) {
                    withContext(NonCancellable) { pending?.let { stopSession(it) } }
                    currentCoroutineContext().ensureActive()
                    val before = captureEnvironment()
                    val config =
                        resolve(profile, credentials, before)
                            .copy(localSocksHost = "127.0.0.1", localSocksPort = 0)
                            .withNetworkMode(
                                if (before.vpnProtectionRequired) {
                                    RelayRuntimeNetworkMode.Vpn
                                } else {
                                    RelayRuntimeNetworkMode.Proxy
                                },
                            )
                    if (before != captureEnvironment()) return@withTimeoutOrNull null
                    measureResolved(config, probeUrl, before)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun measureResolved(
        config: ResolvedRipDpiRelayConfig,
        probeUrl: String,
        before: CandidateRelayProbeEnvironment,
    ): Long? {
        val runtime = runtimeFactory.create()
        val job =
            runtimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    runtime.start(config)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Readiness or the explicit exit check fails the measurement.
                }
            }
        val session = CandidateSession(runtime, job)
        pending = session
        return try {
            runtime.awaitReady(ReadyDeadlineMillis)
            if (!job.isActive) return null
            val endpoint = resolveLocalProxyEndpoint(runtime.pollTelemetry(), authToken = null)
            require(endpoint.host == "127.0.0.1" || endpoint.host == "::1")
            val result =
                capabilityProbe.probe(
                    RelayProbeEndpoint(endpoint.host, endpoint.port),
                    probeUrl,
                    EgressRequirements(tcpConnect = true, udpAssociate = false),
                )
            result.latencyMs.takeIf { result.succeeded && job.isActive && before == captureEnvironment() }
        } finally {
            val cleanup = withContext(NonCancellable) { runCatching { stopSession(session) } }
            currentCoroutineContext().ensureActive()
            cleanup.getOrThrow()
        }
    }

    /** Retains incomplete cleanup for a retry before any next candidate is created. */
    private suspend fun stopSession(session: CandidateSession) {
        val previousCleanup = session.cleanup
        if (session.job.isCompleted && previousCleanup?.isCompleted == true) {
            pending = null
            previousCleanup.await().getOrThrow()
        } else {
            val cleanup =
                previousCleanup?.takeIf { it.isActive } ?: runtimeScope
                    .async {
                        runCatching { session.runtime.stop() }
                    }.also { session.cleanup = it }
            try {
                val completed =
                    withTimeoutOrNull(CleanupDeadlineMillis) {
                        cleanup.await().getOrThrow()
                        session.job.join()
                        true
                    } == true
                if (!completed) throw RuntimeCleanupPendingException()
            } finally {
                if (session.job.isCompleted && cleanup.isCompleted) pending = null
            }
        }
    }

    private data class CandidateSession(
        val runtime: RipDpiRelayRuntime,
        val job: Job,
        var cleanup: Deferred<Result<Unit>>? = null,
    )
}

private const val CandidateDeadlineMillis = 15_000L
private const val ReadyDeadlineMillis = 5_000L
private const val CleanupDeadlineMillis = 5_000L
