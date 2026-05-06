package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.toRuntimeException

internal data class VpnTelemetrySnapshot(
    val proxyTelemetry: NativeRuntimeSnapshot,
    val proxyTelemetryStatus: RuntimeTelemetryStatus,
    val relayTelemetry: NativeRuntimeSnapshot,
    val relayTelemetryStatus: RuntimeTelemetryStatus,
    val warpTelemetry: NativeRuntimeSnapshot,
    val warpTelemetryStatus: RuntimeTelemetryStatus,
    val tunnelTelemetry: NativeRuntimeSnapshot,
    val tunnelTelemetryStatus: RuntimeTelemetryStatus,
) {
    fun failureReason(): FailureReason? =
        if (tunnelTelemetryStatus.state == RuntimeTelemetryState.EngineError) {
            classifyFailureReason(tunnelTelemetryStatus.toRuntimeException(), isTunnelContext = true)
        } else {
            null
        }
}

internal fun RuntimeTelemetryOutcome.snapshotOrIdle(source: String): NativeRuntimeSnapshot =
    when (this) {
        is RuntimeTelemetryOutcome.Snapshot -> snapshot

        RuntimeTelemetryOutcome.NoData,
        is RuntimeTelemetryOutcome.EngineError,
        -> NativeRuntimeSnapshot.idle(source = source)
    }
