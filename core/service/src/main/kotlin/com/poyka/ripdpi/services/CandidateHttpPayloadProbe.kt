package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import javax.inject.Inject

/** Uses the canonical cancellable full-body HTTP verifier with the candidate's independent HTTP budget. */
class CandidateHttpPayloadProbe internal constructor(
    private val tcp: RelayTcpProbe,
    private val nanoTime: () -> Long,
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
        for (control in policy.probes) {
            val result =
                strictProbe(
                    control,
                    policy.timeoutSeconds * MillisecondsPerSecond,
                ).probe(endpoint, control.url)
            if (result.succeeded) {
                return CandidateHttpPayloadEvidence(true, (nanoTime() - started) / NanosecondsPerMillisecond)
            }
        }
        return CandidateHttpPayloadEvidence(false, (nanoTime() - started) / NanosecondsPerMillisecond)
    }
}

data class CandidateHttpPayloadEvidence(
    val succeeded: Boolean,
    val latencyMillis: Long,
)

private const val CandidateHttpTimeoutMillis = 15_000L
private const val NanosecondsPerMillisecond = 1_000_000

private const val MillisecondsPerSecond = 1_000L
