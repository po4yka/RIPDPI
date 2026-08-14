package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.InitialTransportRaceCandidateSnapshot
import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.service.awg.AmneziaWgLocalSocksPort
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AwgEgressReadinessVerifier(
    private val runtimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val activeProbe: RelayActiveProbe = OkHttpRelayActiveProbe(),
    private val relayHealthDecisionEngine: RelayHealthDecisionEngine = RelayHealthDecisionEngine(),
) {
    suspend fun verify(
        requestProfileId: String,
        plan: InitialRelayRacePlan,
        onState: (InitialTransportRaceSnapshot) -> Unit,
        onSelected: (InitialRelayRaceResult) -> Unit,
    ) {
        val candidate =
            plan.candidates.singleOrNull()?.takeIf { candidate ->
                candidate.profileId == requestProfileId && candidate.relayKind == AmneziaWgEgressKind
            } ?: failReadiness("AWG egress readiness plan does not match the active profile")
        onState(raceState(RaceStateRacing, candidate, RaceOutcomePending))

        val probeResult =
            try {
                plan.probePlan.targetUrl?.let { targetUrl ->
                    activeProbe.probe(
                        endpoint = LocalProxyEndpoint("127.0.0.1", AmneziaWgLocalSocksPort),
                        url = targetUrl,
                        requirements = plan.probePlan.requirements,
                    )
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                stopAfterFailure(failure)
            }
        val decision =
            relayHealthDecisionEngine.evaluateInitialProbe(
                candidate = candidate,
                plan = plan,
                result = probeResult,
                observedAtMs = System.currentTimeMillis(),
            )
        if (decision !is RelayHealthDecision.Healthy) {
            onState(
                raceState(
                    RaceStateExhausted,
                    candidate,
                    decision.toRaceOutcome(),
                    probeResult?.latencyMs,
                ),
            )
            failReadiness("AmneziaWG handshake completed but relay health verification was inconclusive")
        }

        val result =
            InitialRelayRaceResult(
                selectedCandidate = candidate,
                usedCachedFallback = false,
                latencyMs = probeResult?.latencyMs,
            )
        onState(raceState(RaceStateSelected, candidate, RaceOutcomeSucceeded, probeResult?.latencyMs))
        onSelected(result)
    }

    private suspend fun failReadiness(message: String): Nothing =
        stopAfterFailure(InitialTransportSelectionException(message))

    private suspend fun stopAfterFailure(failure: Throwable): Nothing {
        withContext(NonCancellable) {
            runCatching { runtimeSupervisor.stop() }
                .onFailure(failure::addSuppressed)
        }
        throw failure
    }

    private fun raceState(
        state: String,
        candidate: InitialRelayCandidate,
        outcome: String,
        latencyMs: Long? = null,
    ): InitialTransportRaceSnapshot =
        InitialTransportRaceSnapshot(
            state = state,
            candidates =
                listOf(
                    InitialTransportRaceCandidateSnapshot(
                        transportClass = candidate.transportClass.wireValue,
                        outcome = outcome,
                        latencyMs = latencyMs,
                    ),
                ),
            selectedTransportClass = candidate.transportClass.wireValue.takeIf { state == RaceStateSelected },
        )

    private fun RelayHealthDecision.toRaceOutcome(): String =
        when (this) {
            is RelayHealthDecision.Healthy -> RaceOutcomeSucceeded
            is RelayHealthDecision.Inconclusive -> RaceOutcomeVerificationInconclusive
            is RelayHealthDecision.ConfirmedFailed -> RaceOutcomeConfirmedFailed
        }

    private companion object {
        const val RaceStateRacing = "racing"
        const val RaceStateSelected = "selected"
        const val RaceStateExhausted = "exhausted"
        const val RaceOutcomePending = "pending"
        const val RaceOutcomeSucceeded = "succeeded"
        const val RaceOutcomeVerificationInconclusive = "verification_inconclusive"
        const val RaceOutcomeConfirmedFailed = "confirmed_failed"
    }
}
