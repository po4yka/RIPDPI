package com.poyka.ripdpi.diagnostics.dpi

data class CompatibleSni(
    val sni: String,
    val lineNumber: Int,
)

data class AllowlistSniResult(
    val asn: String,
    val provider: String,
    val ip: String,
    val compatibleSnis: List<CompatibleSni>,
    val triedCount: Int,
    val aborted: Boolean,
    val abortReason: String? = null,
)
