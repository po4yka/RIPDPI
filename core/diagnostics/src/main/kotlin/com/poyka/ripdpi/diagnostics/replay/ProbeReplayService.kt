package com.poyka.ripdpi.diagnostics.replay

import kotlinx.coroutines.flow.Flow

/**
 * Replays a single probe step-by-step through OkHttp's EventListener,
 * emitting [ReplayStepEvent] as each protocol boundary completes or
 * fails. Cancel-safe per the underlying OkHttp Call.cancel() contract.
 *
 * Implementations:
 * - [DefaultProbeReplayService] - the OkHttp-backed production impl
 *   (lands in P4.2)
 *
 * Mutates the live strategy via SettingsStrategyProbeActivator -
 * callers must show the "Replay disrupts live session" confirmation
 * dialog when the VPN is in a non-Halted state per the design's
 * P4 risk-mitigation. See StrategyProbeService.kt:201-234 for the
 * activate/finally-restore precedent.
 */
fun interface ProbeReplayService {
    /**
     * Run a replay; terminates with [ReplayStepEvent.Finished] regardless
     * of outcome. Cancelling the collecting coroutine cancels the
     * underlying OkHttp Call.
     */
    fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent>
}
