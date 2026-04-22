package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class ControllableNetworkHandoverMonitor : NetworkHandoverMonitor {
    private val eventFlow = MutableSharedFlow<NetworkHandoverEvent>(extraBufferCapacity = 8)

    override val events: SharedFlow<NetworkHandoverEvent> = eventFlow.asSharedFlow()

    fun emit(event: NetworkHandoverEvent) {
        check(eventFlow.tryEmit(event)) { "Failed to enqueue network handover event" }
    }
}
