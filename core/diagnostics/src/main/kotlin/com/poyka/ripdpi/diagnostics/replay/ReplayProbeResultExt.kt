package com.poyka.ripdpi.diagnostics.replay

import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.toList

/**
 * Collects the entire replay stream into a single [ReplayProbeResult].
 * Convenience for callers (archive persistence, tests) that don't
 * need the streaming UX of [ProbeReplayService.run].
 *
 * When the underlying flow terminates without emitting a
 * [ReplayStepEvent.Finished] (e.g. cancellation before the orchestrator
 * could emit the terminal event), the returned result carries
 * [ReplayVerdict.Cancelled], a null [ReplayProbeResult.terminalStep],
 * and an empty [ReplayProbeResult.recommendationKey] — consistent with
 * the recommendation engine's "no rule matches" fallback contract.
 *
 * Design ref: docs/architecture/G008_SUBSYSTEMS_DESIGN.md P4
 * implementation-plan item 6 (archive integration).
 */
suspend fun ProbeReplayService.runToCompletion(request: ReplayProbeRequest): ReplayProbeResult {
    val events = run(request).toList()
    val finished =
        events.filterIsInstance<ReplayStepEvent.Finished>().lastOrNull()
            ?: return ReplayProbeResult(
                request = request,
                events = events.toPersistentList(),
                verdict = ReplayVerdict.Cancelled,
                terminalStep = null,
                recommendationKey = "",
            )
    return ReplayProbeResult(
        request = request,
        events = events.toPersistentList(),
        verdict = finished.verdict,
        terminalStep = finished.terminalStep,
        recommendationKey = finished.recommendationKey,
    )
}
