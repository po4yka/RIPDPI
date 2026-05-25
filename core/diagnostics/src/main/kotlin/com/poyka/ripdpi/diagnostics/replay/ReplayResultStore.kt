package com.poyka.ripdpi.diagnostics.replay

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer of recent terminal [ReplayProbeResult] aggregates.
 *
 * Producer: [com.poyka.ripdpi.ui.screens.diagnostics.ReplayFailureViewModel]
 * records on terminal [ReplayStepEvent.Finished] / cancellation.
 *
 * Consumers:
 * - [com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceLoader]
 *   reads [recent] when the user exports a diagnostics bundle (G010 P4.8).
 * - Past-replays UI screen reads [recent] for in-app browsing.
 *
 * State is intentionally process-scoped — replay results are diagnostic
 * snapshots the user can re-run on demand, not policy state that must
 * survive [android.app.ActivityManager] LMK SIGKILL. Matches the
 * carve-out in `.claude/rules/android-vpn-lifecycle.md` for ephemeral
 * diagnostic aggregates.
 */
@Singleton
class ReplayResultStore
    @Inject
    constructor() {
        private val ring = ArrayDeque<ReplayProbeResult>(MaxEntries)

        @Synchronized
        fun record(result: ReplayProbeResult) {
            if (ring.size >= MaxEntries) {
                ring.removeFirst()
            }
            ring.addLast(result)
        }

        @Synchronized
        fun recent(): List<ReplayProbeResult> = ring.toList()

        @Synchronized
        fun clear() {
            ring.clear()
        }

        companion object {
            const val MaxEntries: Int = 10
        }
    }
