package com.poyka.ripdpi.config.relay

import com.poyka.ripdpi.data.NativeRuntimeSnapshot

internal fun NativeRuntimeSnapshot.isDegradedControlPlane(): Boolean {
    val healthState = health.trim().lowercase()
    return healthState == "degraded" ||
        healthState == "failed" ||
        lastFailureClass?.isNotBlank() == true ||
        lastError?.isNotBlank() == true
}
