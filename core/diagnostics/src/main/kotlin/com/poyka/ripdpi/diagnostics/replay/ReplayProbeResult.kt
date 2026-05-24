package com.poyka.ripdpi.diagnostics.replay

import kotlinx.collections.immutable.ImmutableList

/**
 * Terminal aggregate of a replay session. Built by collecting all
 * [ReplayStepEvent]s from [ProbeReplayService.run] up to and including
 * the [ReplayStepEvent.Finished] event.
 *
 * Suitable for archive persistence (P4.6) and for unit-testing the
 * orchestrator's terminal contract.
 *
 * Design ref: docs/architecture/G008_SUBSYSTEMS_DESIGN.md P4
 * implementation-plan item 6 (archive integration).
 */
data class ReplayProbeResult(
    val request: ReplayProbeRequest,
    val events: ImmutableList<ReplayStepEvent>,
    val verdict: ReplayVerdict,
    val terminalStep: ReplayStepKind?,
    val recommendationKey: String,
)
