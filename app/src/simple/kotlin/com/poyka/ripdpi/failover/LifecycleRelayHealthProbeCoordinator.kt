package com.poyka.ripdpi.failover

import com.poyka.ripdpi.services.RelayCapabilityProof
import com.poyka.ripdpi.services.RelayHealthDecision
import com.poyka.ripdpi.services.RelayHealthDecisionEngine
import com.poyka.ripdpi.services.RelayHealthObservation
import com.poyka.ripdpi.services.RelayHealthScope
import com.poyka.ripdpi.services.RelayProbePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val RelayProbeReuseIntervalMs = 20_000L

internal data class RelayHealthProbeRequest(
    val attemptId: String,
    val profileToken: String,
    val relayKind: String,
    val capabilityProof: RelayCapabilityProof,
    val scope: RelayHealthScope,
    val endpoint: FailoverProxyEndpoint,
    val plan: RelayProbePlan,
)

internal fun interface RelayHealthObservationProbe {
    suspend fun probe(request: RelayHealthProbeRequest): RelayHealthObservation
}

internal class LifecycleRelayHealthProbeCoordinator(
    private val lifecycleScope: CoroutineScope,
    private val clock: FailoverClock,
    private val probe: RelayHealthObservationProbe,
    private val decisionEngine: RelayHealthDecisionEngine = RelayHealthDecisionEngine(),
    private val reuseIntervalMs: Long = RelayProbeReuseIntervalMs,
) {
    private val admissionMutex = Mutex()
    private val decisionMutex = Mutex()
    private val inFlight = mutableMapOf<RelayProbeTuple, Deferred<RelayHealthDecision>>()
    private val recentDecisions = mutableMapOf<RelayProbeTuple, CachedRelayHealthDecision>()

    suspend fun confirm(request: RelayHealthProbeRequest): RelayHealthDecision {
        val tuple = request.tuple()
        val admission =
            admissionMutex.withLock {
                recentDecision(tuple)?.let { return@withLock ProbeAdmission.Cached(it) }
                inFlight[tuple]?.let { return@withLock ProbeAdmission.Join(it) }
                val promise = CompletableDeferred<RelayHealthDecision>()
                inFlight[tuple] = promise
                ProbeAdmission.Start(promise)
            }
        return when (admission) {
            is ProbeAdmission.Cached -> admission.decision
            is ProbeAdmission.Join -> admission.deferred.await()
            is ProbeAdmission.Start -> startAndAwait(request, tuple, admission.promise)
        }
    }

    suspend fun observe(observation: RelayHealthObservation): RelayHealthDecision {
        val decision = decisionMutex.withLock { decisionEngine.observe(observation) }
        admissionMutex.withLock {
            recentDecisions[observation.tuple()] = CachedRelayHealthDecision(clock.nowMillis(), decision)
        }
        return decision
    }

    private suspend fun startAndAwait(
        request: RelayHealthProbeRequest,
        tuple: RelayProbeTuple,
        promise: CompletableDeferred<RelayHealthDecision>,
    ): RelayHealthDecision {
        lifecycleScope.launch {
            try {
                val observation = probe.probe(request)
                val decision = decisionMutex.withLock { decisionEngine.observe(observation) }
                admissionMutex.withLock {
                    recentDecisions[tuple] = CachedRelayHealthDecision(clock.nowMillis(), decision)
                    if (inFlight[tuple] === promise) inFlight.remove(tuple)
                }
                promise.complete(decision)
            } catch (cancelled: CancellationException) {
                admissionMutex.withLock {
                    if (inFlight[tuple] === promise) inFlight.remove(tuple)
                }
                promise.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                admissionMutex.withLock {
                    if (inFlight[tuple] === promise) inFlight.remove(tuple)
                }
                promise.completeExceptionally(error)
            }
        }
        return promise.await()
    }

    private fun recentDecision(tuple: RelayProbeTuple): RelayHealthDecision? =
        recentDecisions[tuple]
            ?.takeIf { clock.nowMillis() - it.completedAtMs in 0 until reuseIntervalMs }
            ?.decision
}

private sealed interface ProbeAdmission {
    data class Cached(
        val decision: RelayHealthDecision,
    ) : ProbeAdmission

    data class Join(
        val deferred: Deferred<RelayHealthDecision>,
    ) : ProbeAdmission

    data class Start(
        val promise: CompletableDeferred<RelayHealthDecision>,
    ) : ProbeAdmission
}

private data class RelayProbeTuple(
    val profileToken: String,
    val relayKind: String,
    val capabilityProof: RelayCapabilityProof,
    val scope: RelayHealthScope,
)

private data class CachedRelayHealthDecision(
    val completedAtMs: Long,
    val decision: RelayHealthDecision,
)

private fun RelayHealthProbeRequest.tuple(): RelayProbeTuple =
    RelayProbeTuple(
        profileToken = profileToken,
        relayKind = relayKind,
        capabilityProof = capabilityProof,
        scope = scope,
    )

private fun RelayHealthObservation.tuple(): RelayProbeTuple =
    RelayProbeTuple(
        profileToken = profileToken,
        relayKind = relayKind,
        capabilityProof = capabilityProof,
        scope = scope,
    )
