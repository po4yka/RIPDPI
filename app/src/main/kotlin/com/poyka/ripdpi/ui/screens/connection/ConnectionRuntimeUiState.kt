package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.services.RunningReconnectState

enum class ConnectionRuntimeOperation {
    Starting,
    Stopping,
    Reconnecting,
}

internal fun RunningReconnectState.isConfigPending(): Boolean =
    this is RunningReconnectState.Starting || this is RunningReconnectState.Stopping
