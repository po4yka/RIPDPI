package com.poyka.ripdpi.diagnostics.dpi

data class CompatibleSni(
    val sni: String,
    val lineNumber: Int,
)

enum class AllowlistSniFailure {
    NO_TLS_BASELINE,
    BASELINE_NOT_REPRODUCED,
    SEARCH_TIMED_OUT,
}

data class AllowlistSniResult(
    val asn: String,
    val provider: String,
    val ip: String,
    val compatibleSnis: List<CompatibleSni>,
    val triedCount: Int,
    val port: Int = Tcp16Transport.HTTPS.defaultPort,
    val failure: AllowlistSniFailure? = null,
) {
    val aborted: Boolean
        get() = failure != null
}
