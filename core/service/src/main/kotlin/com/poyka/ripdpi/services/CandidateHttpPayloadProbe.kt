package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProfileQualityMeasurement
import javax.inject.Inject

/** Uses the canonical cancellable full-body HTTP verifier with the candidate's independent HTTP budget. */
class CandidateHttpPayloadProbe internal constructor(
    private val tcp: RelayTcpProbe,
    private val nanoTime: () -> Long,
    private val qualityProbe: (Long) -> QualityHttpPayloadProbe = { QualityHttpPayloadProbe(it) },
    private val strictProbe: (PayloadProbeControl, Long) -> RelayTcpProbe = { control, timeout ->
        OkHttpRelayTcpProbe(timeout, control)
    },
) {
    @Inject
    constructor() : this(OkHttpRelayTcpProbe(CandidateHttpTimeoutMillis), System::nanoTime)

    suspend fun probe(
        endpoint: RelayProbeEndpoint,
        url: String,
    ): CandidateHttpPayloadEvidence {
        val started = nanoTime()
        val result = tcp.probe(endpoint, url)
        return CandidateHttpPayloadEvidence(result.succeeded, (nanoTime() - started) / NanosecondsPerMillisecond)
    }

    suspend fun probePayload(
        endpoint: RelayProbeEndpoint,
        policy: PayloadFailoverPolicy,
    ): CandidateHttpPayloadEvidence {
        val started = nanoTime()
        for ((index, control) in policy.probes.withIndex()) {
            val result =
                if (policy.quality != null) {
                    val quality =
                        qualityProbe(policy.timeoutSeconds * MillisecondsPerSecond)
                            .measure(endpoint, control, index)
                    CandidateHttpPayloadEvidence(
                        quality != null,
                        (nanoTime() - started) / NanosecondsPerMillisecond,
                        quality,
                    )
                } else {
                    val health =
                        strictProbe(control, policy.timeoutSeconds * MillisecondsPerSecond)
                            .probe(endpoint, control.url)
                    CandidateHttpPayloadEvidence(health.succeeded, (nanoTime() - started) / NanosecondsPerMillisecond)
                }
            if (result.succeeded) return result
        }
        return CandidateHttpPayloadEvidence(false, (nanoTime() - started) / NanosecondsPerMillisecond)
    }
}

data class CandidateHttpPayloadEvidence(
    val succeeded: Boolean,
    val latencyMillis: Long,
    val quality: ProfileQualityMeasurement? = null,
)

private const val CandidateHttpTimeoutMillis = 15_000L
private const val NanosecondsPerMillisecond = 1_000_000

private const val MillisecondsPerSecond = 1_000L
