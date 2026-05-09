package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindNaiveProxy

internal data class SubprocessRelayTelemetryInputs(
    val config: ResolvedRipDpiRelayConfig?,
    val launchSpec: SubprocessSocksRelayLaunchSpec?,
    val running: Boolean,
    val runtimeVersion: String?,
    val lastError: String?,
    val lastFailureClass: String?,
    val runtimeStateOverride: String?,
)

internal class SubprocessRelayTelemetryProjector {
    fun project(inputs: SubprocessRelayTelemetryInputs): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "relay",
            state = if (inputs.running) "running" else "idle",
            health = if (inputs.running) "running" else "idle",
            listenerAddress = inputs.config?.let { "${it.localSocksHost}:${it.localSocksPort}" },
            upstreamAddress = inputs.launchSpec?.upstreamAddress,
            profileId = inputs.config?.profileId,
            protocolKind = inputs.config?.kind,
            tcpCapable = true,
            udpCapable = false,
            lastError = inputs.lastError,
            ptRuntimeKind =
                inputs.launchSpec
                    ?.runtimeKind
                    ?.takeUnless { it == RelayKindNaiveProxy },
            ptRuntimeState =
                inputs.launchSpec
                    ?.runtimeKind
                    ?.takeUnless { it == RelayKindNaiveProxy }
                    ?.let {
                        inputs.runtimeStateOverride ?: when {
                            inputs.running -> "running"
                            inputs.lastError != null -> "failed"
                            else -> "idle"
                        }
                    },
            ptRuntimeVersion = inputs.runtimeVersion,
            lastFailureClass = inputs.lastFailureClass,
        )
}
