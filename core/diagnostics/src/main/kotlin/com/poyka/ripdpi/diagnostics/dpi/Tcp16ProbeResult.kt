package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState

enum class Tcp16Verdict {
    OK,
    DETECTED_AT_KB,
    DEAD,
    ERROR,
    INVALID_RECONNECTED,
}

data class Tcp16ProbeResult(
    val targetId: String,
    val asn: String,
    val provider: String,
    val ip: String,
    val port: Int,
    val alive: Boolean,
    val verdict: Tcp16Verdict,
    val detectedAtKb: Int?,
    val measuredRttMs: Long?,
    val errorDetail: String?,
    val connectionCount: Int,
    val tlsClientState: DiagnosticsTlsClientState? = null,
)

data class Tcp16AsnSummary(
    val asn: String,
    val providers: Set<String>,
    val checked: Int,
    val ok: Int,
    val detected: Int,
    val dead: Int,
    val errors: Int,
    val invalidReconnected: Int,
)

fun Iterable<Tcp16ProbeResult>.byAsn(): Map<String, Tcp16AsnSummary> =
    groupBy { result -> result.asn }
        .mapValues { (asn, results) ->
            Tcp16AsnSummary(
                asn = asn,
                providers = results.map { result -> result.provider }.toSet(),
                checked = results.size,
                ok = results.count { result -> result.verdict == Tcp16Verdict.OK },
                detected = results.count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB },
                dead = results.count { result -> result.verdict == Tcp16Verdict.DEAD },
                errors = results.count { result -> result.verdict == Tcp16Verdict.ERROR },
                invalidReconnected = results.count { result -> result.verdict == Tcp16Verdict.INVALID_RECONNECTED },
            )
        }
