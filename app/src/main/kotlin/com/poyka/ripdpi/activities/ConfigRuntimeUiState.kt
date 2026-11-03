package com.poyka.ripdpi.activities

import com.poyka.ripdpi.services.RunningReconnectState

enum class ConfigRuntimeOperation {
    Starting,
    Stopping,
    Reconnecting,
}

internal fun RunningReconnectState.isConfigPending(): Boolean =
    this is RunningReconnectState.Starting || this is RunningReconnectState.Stopping
