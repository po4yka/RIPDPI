package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState

enum class Tcp16Verdict {
    OK,
    PADDED_TRAIN_INTERRUPTED,
    DEAD,
    ERROR,
    INVALID_RECONNECTED,
    UNSUPPORTED,
    INVALID_TARGET,
    HTTP_REJECTED,
    SERVER_CLOSED,
    TIMED_OUT,
}

enum class Tcp16Stage {
    SETUP,
    CONNECT,
    TLS_HANDSHAKE,
    WRITE_REQUEST,
    READ_RESPONSE,
}

data class Tcp16ProbeResult(
    val targetId: String,
    val asn: String,
    val provider: String,
    val ip: String,
    val port: Int,
    val alive: Boolean,
    val verdict: Tcp16Verdict,
    val meanResponseMs: Long?,
    val errorDetail: String?,
    val connectionCount: Int,
    val tlsClientState: DiagnosticsTlsClientState? = null,
    val requestedHosts: List<String>? = null,
    val completedPaddingBytes: Int = 0,
    val httpStatus: Int? = null,
    val failedStage: Tcp16Stage? = null,
    val transport: Tcp16Transport? = Tcp16Transport.forDefaultPort(port),
    val tlsServerName: String? = null,
    val httpAuthority: String? = null,
) {
    val canCompareSni: Boolean
        get() =
            verdict == Tcp16Verdict.PADDED_TRAIN_INTERRUPTED &&
                transport == Tcp16Transport.HTTPS && connectionCount == 1 && httpAuthority != null
}

data class Tcp16AsnSummary(
    val asn: String,
    val providers: Set<String>,
    val checked: Int,
    val ok: Int,
    val detected: Int,
    val dead: Int,
    val errors: Int,
    val invalidReconnected: Int,
    val unsupported: Int,
)

fun Iterable<Tcp16ProbeResult>.byAsn(): Map<String, Tcp16AsnSummary> =
    groupBy { result -> result.asn }
        .mapValues { (asn, results) ->
            Tcp16AsnSummary(
                asn = asn,
                providers = results.map { result -> result.provider }.toSet(),
                checked = results.size,
                ok = results.count { result -> result.verdict == Tcp16Verdict.OK },
                detected = results.count { result -> result.verdict == Tcp16Verdict.PADDED_TRAIN_INTERRUPTED },
                dead = results.count { result -> result.verdict == Tcp16Verdict.DEAD },
                errors =
                    results.count { result ->
                        result.verdict in Tcp16ErrorVerdicts
                    },
                invalidReconnected = results.count { result -> result.verdict == Tcp16Verdict.INVALID_RECONNECTED },
                unsupported = results.count { result -> result.verdict == Tcp16Verdict.UNSUPPORTED },
            )
        }

private val Tcp16ErrorVerdicts =
    setOf(
        Tcp16Verdict.ERROR,
        Tcp16Verdict.INVALID_TARGET,
        Tcp16Verdict.HTTP_REJECTED,
        Tcp16Verdict.SERVER_CLOSED,
        Tcp16Verdict.TIMED_OUT,
    )
