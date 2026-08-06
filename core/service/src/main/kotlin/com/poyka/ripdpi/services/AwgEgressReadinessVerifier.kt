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
                activeProbe.probe(
                    endpoint = LocalProxyEndpoint("127.0.0.1", AmneziaWgLocalSocksPort),
                    url = plan.probeUrl,
                    requirements = plan.readinessProbeRequirements,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                stopAfterFailure(failure)
            }
        if (!probeResult.succeeded) {
            onState(
                raceState(
                    RaceStateExhausted,
                    candidate,
                    probeResult.failure ?: RaceOutcomeFailed,
                    probeResult.latencyMs,
                ),
            )
            failReadiness("AmneziaWG handshake completed but active internet egress failed")
        }

        val result =
            InitialRelayRaceResult(
                selectedCandidate = candidate,
                usedCachedFallback = false,
                latencyMs = probeResult.latencyMs,
            )
        onState(raceState(RaceStateSelected, candidate, RaceOutcomeSucceeded, probeResult.latencyMs))
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

    private companion object {
        const val RaceStateRacing = "racing"
        const val RaceStateSelected = "selected"
        const val RaceStateExhausted = "exhausted"
        const val RaceOutcomePending = "pending"
        const val RaceOutcomeSucceeded = "succeeded"
        const val RaceOutcomeFailed = "failed"
    }
}
