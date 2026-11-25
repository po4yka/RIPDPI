package com.poyka.ripdpi.diagnostics.replay

import kotlinx.coroutines.flow.Flow

fun interface ProbeReplayService {
    fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent>
}
