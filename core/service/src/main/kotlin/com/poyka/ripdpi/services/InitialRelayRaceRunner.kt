package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.InitialTransportRaceCandidateSnapshot
import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import com.poyka.ripdpi.data.InitialTransportSelectionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class InitialRelayRaceRunner(
    private val relayActiveProbe: RelayActiveProbe,
    private val startCandidate: suspend (InitialRelayCandidate) -> RelayRuntimeSlot,
    private val promoteCandidate: suspend (RelayRuntimeSlot) -> Unit,
    private val stopDiscardedCandidates: suspend (Collection<RelayRuntimeSlot>, RelayRuntimeSlot?) -> Unit,
) {
    suspend fun run(
        plan: InitialRelayRacePlan,
        onState: (InitialTransportRaceSnapshot) -> Unit,
    ): PromotedRelayRuntime =
        coroutineScope {
            validatePlan(plan)
            val slots = ConcurrentHashMap<String, RelayRuntimeSlot>()
            val attempts = Channel<RelayRaceAttempt>(capacity = plan.candidates.size)
            val outcomes = ConcurrentHashMap<String, InitialTransportRaceCandidateSnapshot>()
            plan.candidates.forEach { candidate ->
                outcomes[candidate.profileId] = candidate.toSnapshot(outcome = RaceOutcomePending)
            }
            onState(raceState(state = RaceStateRacing, plan = plan, outcomes = outcomes))

            val jobs =
                plan.candidates.map { candidate ->
                    launch {
                        runCandidate(candidate, plan, slots, outcomes, attempts)
                    }
                }
            var retainedSlot: RelayRuntimeSlot? = null
            try {
                val raceCompletion = awaitRaceCompletion(attempts, plan.candidates.size)
                jobs.forEach { job ->
                    if (job.isActive) job.cancelAndJoin()
                }

                raceCompletion.winner?.let { winningAttempt ->
                    plan.candidates.forEach { candidate ->
                        outcomes.replacePendingOutcome(candidate, RaceOutcomeCancelled)
                    }
                    val winningSlot = requireNotNull(winningAttempt.slot)
                    val promoted = promoteWinner(winningAttempt, winningSlot, plan, outcomes, onState)
                    retainedSlot = winningSlot
                    return@coroutineScope promoted
                }

                if (!raceCompletion.completedBeforeDeadline) {
                    plan.candidates.forEach { candidate ->
                        outcomes.replacePendingOutcome(candidate, RaceOutcomeTimeout)
                    }
                }

                cachedFallback(plan, slots)?.let { (cachedCandidate, cachedSlot) ->
                    val result = InitialRelayRaceResult(cachedCandidate, usedCachedFallback = true, latencyMs = null)
                    onState(
                        raceState(
                            state = RaceStateCachedFallback,
                            plan = plan,
                            outcomes = outcomes,
                            selectedCandidate = cachedCandidate,
                            usedCachedFallback = true,
                        ),
                    )
                    promoteCandidate(cachedSlot)
                    retainedSlot = cachedSlot
                    return@coroutineScope PromotedRelayRuntime(
                        endpoint = requireNotNull(cachedSlot.endpoint),
                        result = result,
                        udpEnabled = cachedSlot.udpEnabled,
                    )
                }

                onState(raceState(state = RaceStateExhausted, plan = plan, outcomes = outcomes))
                throw InitialTransportSelectionException(
                    "No compatible initial relay transport passed its active probe",
                )
            } finally {
                withContext(NonCancellable) {
                    jobs.forEach { job ->
                        if (job.isActive) job.cancelAndJoin()
                    }
                    stopDiscardedCandidates(slots.values, retainedSlot)
                }
            }
        }

    private suspend fun promoteWinner(
        winningAttempt: RelayRaceAttempt,
        winningSlot: RelayRuntimeSlot,
        plan: InitialRelayRacePlan,
        outcomes: Map<String, InitialTransportRaceCandidateSnapshot>,
        onState: (InitialTransportRaceSnapshot) -> Unit,
    ): PromotedRelayRuntime {
        val result = winningAttempt.toResult()
        onState(
            raceState(
                state = RaceStateSelected,
                plan = plan,
                outcomes = outcomes,
                selectedCandidate = winningAttempt.candidate,
            ),
        )
        promoteCandidate(winningSlot)
        return PromotedRelayRuntime(
            endpoint = requireNotNull(winningSlot.endpoint),
            result = result,
            udpEnabled = winningSlot.udpEnabled,
        )
    }

    private suspend fun runCandidate(
        candidate: InitialRelayCandidate,
        plan: InitialRelayRacePlan,
        slots: ConcurrentHashMap<String, RelayRuntimeSlot>,
        outcomes: ConcurrentHashMap<String, InitialTransportRaceCandidateSnapshot>,
        attempts: Channel<RelayRaceAttempt>,
    ) {
        try {
            val slot = startCandidate(candidate)
            slots[candidate.profileId] = slot
            val endpoint = requireNotNull(slot.endpoint)
            val result = relayActiveProbe.probe(endpoint, plan.probeUrl, plan.readinessProbeRequirements)
            val outcome = result.failure ?: if (result.succeeded) RaceOutcomeSucceeded else RaceOutcomeFailed
            outcomes[candidate.profileId] = candidate.toSnapshot(outcome, result.latencyMs)
            attempts.send(RelayRaceAttempt(candidate, slot, result))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            Logger.w { "Initial relay race candidate kind=${candidate.relayKind} failed" }
            outcomes[candidate.profileId] = candidate.toSnapshot(RaceOutcomeRuntimeFailed)
            attempts.send(RelayRaceAttempt(candidate, slots[candidate.profileId], probeResult = null))
        }
    }

    private suspend fun awaitRaceCompletion(
        attempts: Channel<RelayRaceAttempt>,
        candidateCount: Int,
    ): RaceCompletion {
        var winner: RelayRaceAttempt? = null
        val completed =
            withTimeoutOrNull(RaceDeadlineMillis) {
                repeat(candidateCount) {
                    val attempt = attempts.receive()
                    if (attempt.probeResult?.succeeded == true && attempt.slot?.job?.isActive == true) {
                        winner = attempt
                        return@withTimeoutOrNull true
                    }
                }
                true
            } ?: false
        return RaceCompletion(winner = winner, completedBeforeDeadline = completed)
    }

    private fun cachedFallback(
        plan: InitialRelayRacePlan,
        slots: Map<String, RelayRuntimeSlot>,
    ): Pair<InitialRelayCandidate, RelayRuntimeSlot>? =
        plan.candidates
            .takeIf { it.size > 1 }
            .orEmpty()
            .firstOrNull { it.profileId == plan.cachedFallbackProfileId }
            ?.let { candidate -> slots[candidate.profileId]?.takeIf { it.job.isActive }?.let { candidate to it } }

    private fun validatePlan(plan: InitialRelayRacePlan) {
        require(plan.candidates.size in MinRaceCandidateCount..MaxRaceCandidateCount) {
            "Initial relay preflight requires one or two candidates"
        }
        require(
            plan.candidates
                .map(InitialRelayCandidate::transportClass)
                .toSet()
                .size == plan.candidates.size,
        ) {
            "Initial relay race candidates must use distinct transport classes"
        }
        require(!plan.readinessProbeRequirements.tcpConnect || plan.requirements.tcpConnect) {
            "Initial relay readiness probe cannot require unsupported TCP egress"
        }
        require(!plan.readinessProbeRequirements.udpAssociate || plan.requirements.udpAssociate) {
            "Initial relay readiness probe cannot require unsupported UDP egress"
        }
    }

    private fun RelayRaceAttempt.toResult(): InitialRelayRaceResult =
        InitialRelayRaceResult(
            selectedCandidate = candidate,
            usedCachedFallback = false,
            latencyMs = probeResult?.latencyMs,
        )

    private fun raceState(
        state: String,
        plan: InitialRelayRacePlan,
        outcomes: Map<String, InitialTransportRaceCandidateSnapshot>,
        selectedCandidate: InitialRelayCandidate? = null,
        usedCachedFallback: Boolean = false,
    ): InitialTransportRaceSnapshot =
        InitialTransportRaceSnapshot(
            state = state,
            candidates =
                plan.candidates.map { candidate ->
                    outcomes[candidate.profileId] ?: candidate.toSnapshot(RaceOutcomePending)
                },
            selectedTransportClass = selectedCandidate?.transportClass?.wireValue,
            usedCachedFallback = usedCachedFallback,
        )

    private fun InitialRelayCandidate.toSnapshot(
        outcome: String,
        latencyMs: Long? = null,
    ): InitialTransportRaceCandidateSnapshot =
        InitialTransportRaceCandidateSnapshot(
            transportClass = transportClass.wireValue,
            outcome = outcome,
            latencyMs = latencyMs?.coerceIn(0L, MaxReportedLatencyMillis),
        )

    private fun MutableMap<String, InitialTransportRaceCandidateSnapshot>.replacePendingOutcome(
        candidate: InitialRelayCandidate,
        outcome: String,
    ) {
        computeIfPresent(candidate.profileId) { _, snapshot ->
            if (snapshot.outcome == RaceOutcomePending) candidate.toSnapshot(outcome) else snapshot
        }
    }

    private data class RelayRaceAttempt(
        val candidate: InitialRelayCandidate,
        val slot: RelayRuntimeSlot?,
        val probeResult: RelayActiveProbeResult?,
    )

    private data class RaceCompletion(
        val winner: RelayRaceAttempt?,
        val completedBeforeDeadline: Boolean,
    )

    private companion object {
        const val MinRaceCandidateCount = 1
        const val MaxRaceCandidateCount = 2
        const val RaceDeadlineMillis = 15_000L
        const val MaxReportedLatencyMillis = RaceDeadlineMillis
        const val RaceStateRacing = "racing"
        const val RaceStateSelected = "selected"
        const val RaceStateCachedFallback = "cached_fallback"
        const val RaceStateExhausted = "exhausted"
        const val RaceOutcomePending = "pending"
        const val RaceOutcomeSucceeded = "succeeded"
        const val RaceOutcomeFailed = "failed"
        const val RaceOutcomeRuntimeFailed = "runtime_failed"
        const val RaceOutcomeTimeout = "timeout"
        const val RaceOutcomeCancelled = "cancelled"
    }
}

internal class InitialRelayRaceRunnerFactory(
    private val relayActiveProbe: RelayActiveProbe = OkHttpRelayActiveProbe(),
) {
    fun create(
        startCandidate: suspend (InitialRelayCandidate) -> RelayRuntimeSlot,
        promoteCandidate: suspend (RelayRuntimeSlot) -> Unit,
        stopDiscardedCandidates: suspend (Collection<RelayRuntimeSlot>, RelayRuntimeSlot?) -> Unit,
    ): InitialRelayRaceRunner =
        InitialRelayRaceRunner(
            relayActiveProbe = relayActiveProbe,
            startCandidate = startCandidate,
            promoteCandidate = promoteCandidate,
            stopDiscardedCandidates = stopDiscardedCandidates,
        )
}

internal data class RelayRuntimeSlot(
    val runtime: RipDpiRelayRuntime,
    val job: Job,
    val exitCause: CompletableDeferred<SupervisorExitCause>,
    val stopRequested: AtomicBoolean,
    val shouldReportExit: AtomicBoolean,
    val exitReported: AtomicBoolean,
    val onUnexpectedExit: suspend (SupervisorExitCause) -> Unit,
    val udpEnabled: Boolean,
    var endpoint: LocalProxyEndpoint? = null,
)
