package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

data class NetworkHandoverEvent(
    val previousFingerprint: NetworkFingerprint?,
    val currentFingerprint: NetworkFingerprint?,
    val classification: String,
    val occurredAt: Long,
) {
    val isActionable: Boolean
        get() = currentFingerprint != null && classification != "connectivity_loss"
}

interface NetworkHandoverMonitor {
    val events: SharedFlow<NetworkHandoverEvent>

    /** Starts a subscriber-owned observation when supported; existing hot monitors keep their event stream. */
    fun observe(): Flow<NetworkHandoverEvent> = events
}
