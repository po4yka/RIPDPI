package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal data class VpnProtectFailureEvent(
    val fd: Int,
    val reason: FailureReason,
    val detail: String,
    val detectedAt: Long,
)

internal interface VpnProtectFailureMonitor {
    val events: SharedFlow<VpnProtectFailureEvent>

    fun report(event: VpnProtectFailureEvent)
}

internal class InMemoryVpnProtectFailureMonitor : VpnProtectFailureMonitor {
    private val eventFlow = MutableSharedFlow<VpnProtectFailureEvent>(extraBufferCapacity = 8)

    override val events: SharedFlow<VpnProtectFailureEvent> = eventFlow.asSharedFlow()

    override fun report(event: VpnProtectFailureEvent) {
        eventFlow.tryEmit(event)
    }
}
