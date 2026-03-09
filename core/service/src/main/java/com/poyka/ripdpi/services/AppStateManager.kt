package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ServiceEvent {
    data class Failed(val sender: Sender) : ServiceEvent()
}

object AppStateManager {
    private val _status = MutableStateFlow(AppStatus.Halted to Mode.VPN)
    val status: StateFlow<Pair<AppStatus, Mode>> = _status.asStateFlow()

    private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    /** Broadcast to all active collectors (MainActivity, QuickTileService). SharedFlow, not Channel. */
    val events: SharedFlow<ServiceEvent> = _events.asSharedFlow()

    fun setStatus(status: AppStatus, mode: Mode) {
        _status.value = status to mode
    }

    fun emitFailed(sender: Sender) {
        _events.tryEmit(ServiceEvent.Failed(sender))
    }
}
