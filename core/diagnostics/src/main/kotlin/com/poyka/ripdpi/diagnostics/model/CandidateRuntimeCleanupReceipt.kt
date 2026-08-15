package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class CandidateRuntimeCleanupReceipt(
    val started: Int,
    val stopped: Int,
    val joined: Int,
    val forcedAbort: Int,
)
