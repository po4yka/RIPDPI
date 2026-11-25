package com.poyka.ripdpi.diagnostics.replay

import javax.inject.Inject
import javax.inject.Singleton

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
