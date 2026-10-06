package com.poyka.ripdpi.services

import javax.inject.Inject

/** Uses the canonical cancellable full-body HTTP verifier with the candidate's independent HTTP budget. */
class CandidateHttpPayloadProbe internal constructor(
    private val tcp: RelayTcpProbe,
    private val nanoTime: () -> Long,
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
}

data class CandidateHttpPayloadEvidence(
    val succeeded: Boolean,
    val latencyMillis: Long,
)

private const val CandidateHttpTimeoutMillis = 15_000L
private const val NanosecondsPerMillisecond = 1_000_000
