package com.poyka.ripdpi.diagnostics.replay

import kotlinx.collections.immutable.ImmutableList

data class ReplayProbeResult(
    val request: ReplayProbeRequest,
    val events: ImmutableList<ReplayStepEvent>,
    val verdict: ReplayVerdict,
    val terminalStep: ReplayStepKind?,
    val recommendationKey: String,
)
