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
private const val RelayProbeAttemptsPerStartup = 2

internal data class RelayHealthProbeRequest(
    val attemptId: String,
    val profileToken: String,
    val relayKind: String,
    val capabilityProof: RelayCapabilityProof,
    val scope: RelayHealthScope,
    val endpoint: FailoverProxyEndpoint,
    val plan: RelayProbePlan,
    val relayRuntimeFailing: Boolean = false,
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
    private val attemptBudget: RelayProbeAttemptBudget = RelayProbeAttemptBudget(),
) {
    private val admissionMutex = Mutex()
    private val decisionMutex = Mutex()
    private val recentDecisions = mutableMapOf<RelayProbeTuple, CachedRelayHealthDecision>()

    suspend fun confirm(request: RelayHealthProbeRequest): RelayHealthDecision {
        val tuple = request.tuple()
        val admission =
            admissionMutex.withLock {
                recentDecision(tuple)?.let { return@withLock ProbeAdmission.Cached(it) }
                val promise = CompletableDeferred<RelayHealthDecision>()
                attemptBudget.admit(request.budgetKey(), promise)
            }
        return when (admission) {
            is ProbeAdmission.Cached -> admission.decision
            is ProbeAdmission.Join -> admission.deferred.await()
            is ProbeAdmission.Start -> startAndAwait(request, tuple, request.budgetKey(), admission.promise)
        }
    }

    suspend fun observe(observation: RelayHealthObservation): RelayHealthDecision {
        val decision = decisionMutex.withLock { decisionEngine.observe(observation) }
        admissionMutex.withLock {
            recentDecisions[observation.tuple()] = CachedRelayHealthDecision(clock.nowMillis(), decision)
        }
        attemptBudget.recordDecision(observation.budgetKey(), decision)
        return decision
    }

    private suspend fun startAndAwait(
        request: RelayHealthProbeRequest,
        tuple: RelayProbeTuple,
        budgetKey: RelayProbeBudgetKey,
        promise: CompletableDeferred<RelayHealthDecision>,
    ): RelayHealthDecision {
        lifecycleScope.launch {
            try {
                val observation = probe.probe(request)
                val decision = decisionMutex.withLock { decisionEngine.observe(observation) }
                admissionMutex.withLock {
                    recentDecisions[tuple] = CachedRelayHealthDecision(clock.nowMillis(), decision)
                }
                attemptBudget.complete(budgetKey, promise, decision)
                promise.complete(decision)
            } catch (cancelled: CancellationException) {
                attemptBudget.release(budgetKey, promise)
                promise.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                attemptBudget.release(budgetKey, promise)
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

internal class RelayProbeAttemptBudget(
    private val maxAttemptsPerTuple: Int = RelayProbeAttemptsPerStartup,
) {
    private val entries = mutableMapOf<RelayProbeBudgetKey, RelayProbeBudgetEntry>()

    init {
        require(maxAttemptsPerTuple > 0)
    }

    @Synchronized
    fun admit(
        key: RelayProbeBudgetKey,
        promise: CompletableDeferred<RelayHealthDecision>,
    ): ProbeAdmission {
        val entry = entries.getOrPut(key, ::RelayProbeBudgetEntry)
        entry.inFlight?.let { return ProbeAdmission.Join(it) }
        if (entry.startedAttempts >= maxAttemptsPerTuple) {
            entry.lastDecision?.let { return ProbeAdmission.Cached(it.withoutReplayingFailure()) }
        }
        entry.startedAttempts++
        entry.inFlight = promise
        return ProbeAdmission.Start(promise)
    }

    @Synchronized
    fun complete(
        key: RelayProbeBudgetKey,
        promise: CompletableDeferred<RelayHealthDecision>,
        decision: RelayHealthDecision,
    ) {
        val entry = entries.getOrPut(key, ::RelayProbeBudgetEntry)
        if (entry.inFlight === promise) entry.inFlight = null
        entry.lastDecision = decision
    }

    @Synchronized
    fun release(
        key: RelayProbeBudgetKey,
        promise: CompletableDeferred<RelayHealthDecision>,
    ) {
        val entry = entries[key] ?: return
        if (entry.inFlight === promise) {
            entry.inFlight = null
            entry.startedAttempts = (entry.startedAttempts - 1).coerceAtLeast(0)
        }
    }

    @Synchronized
    fun recordDecision(
        key: RelayProbeBudgetKey,
        decision: RelayHealthDecision,
    ) {
        entries.getOrPut(key, ::RelayProbeBudgetEntry).lastDecision = decision
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

private fun RelayHealthDecision.withoutReplayingFailure(): RelayHealthDecision =
    when (this) {
        is RelayHealthDecision.ConfirmedFailed -> {
            RelayHealthDecision.Inconclusive(
                attemptId = attemptId,
                decidedAtMs = decidedAtMs,
                positiveEvidenceWatermark = positiveEvidenceWatermark,
                reason = com.poyka.ripdpi.services.RelayHealthInconclusiveReason.AwaitingRelayFailureConfirmation,
            )
        }

        else -> {
            this
        }
    }

internal sealed interface ProbeAdmission {
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

internal data class RelayProbeBudgetKey(
    val persistentNetworkHash: String?,
    val profileToken: String,
    val relayKind: String,
    val capabilityProof: RelayCapabilityProof,
)

private data class RelayProbeBudgetEntry(
    var startedAttempts: Int = 0,
    var inFlight: Deferred<RelayHealthDecision>? = null,
    var lastDecision: RelayHealthDecision? = null,
)

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

private fun RelayHealthProbeRequest.budgetKey(): RelayProbeBudgetKey =
    RelayProbeBudgetKey(
        persistentNetworkHash = scope.persistentNetworkHash,
        profileToken = profileToken,
        relayKind = relayKind,
        capabilityProof = capabilityProof,
    )

private fun RelayHealthObservation.budgetKey(): RelayProbeBudgetKey =
    RelayProbeBudgetKey(
        persistentNetworkHash = scope.persistentNetworkHash,
        profileToken = profileToken,
        relayKind = relayKind,
        capabilityProof = capabilityProof,
    )
