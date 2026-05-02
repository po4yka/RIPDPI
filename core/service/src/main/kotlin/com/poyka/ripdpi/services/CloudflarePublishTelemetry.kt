package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import javax.inject.Inject

private const val CloudflarePublishRuntimeKind = "cloudflared"

internal data class CloudflarePublishTelemetryState(
    val helpersRunning: Boolean,
    val originReady: Boolean,
    val cloudflaredReady: Boolean,
    val cloudflaredVersion: String? = null,
    val originVersion: String? = null,
    val originListenerAddress: String? = null,
    val lastError: String? = null,
    val lastFailureClass: String? = null,
)

internal class CloudflarePublishTelemetryProjector
    @Inject
    constructor() {
        fun project(
            relayTelemetry: NativeRuntimeSnapshot,
            active: RunningCloudflarePublish,
        ): NativeRuntimeSnapshot =
            mergeCloudflarePublishTelemetry(
                relayTelemetry = relayTelemetry,
                state =
                    CloudflarePublishTelemetryState(
                        helpersRunning =
                            isCloudflareProcessAlive(active.originProcess.process) &&
                                isCloudflareProcessAlive(active.cloudflaredProcess.process),
                        originReady = active.originReady,
                        cloudflaredReady = active.cloudflaredReady,
                        cloudflaredVersion = active.cloudflaredProcess.version,
                        originVersion = active.originProcess.version,
                        originListenerAddress = active.originListenerAddress,
                        lastError = active.lastError,
                        lastFailureClass = active.lastFailureClass,
                    ),
            )
    }

internal fun mergeCloudflarePublishTelemetry(
    relayTelemetry: NativeRuntimeSnapshot,
    state: CloudflarePublishTelemetryState,
): NativeRuntimeSnapshot =
    relayTelemetry.copy(
        ptRuntimeKind = CloudflarePublishRuntimeKind,
        ptRuntimeState =
            when {
                state.helpersRunning && state.originReady && state.cloudflaredReady -> "running"
                state.lastError != null -> "failed"
                else -> "starting"
            },
        ptRuntimeVersion =
            buildList {
                state.cloudflaredVersion?.let { add(it) }
                state.originVersion?.let { add("origin=$it") }
            }.joinToString(" | ").ifBlank { null },
        listenerAddress = relayTelemetry.listenerAddress ?: state.originListenerAddress,
        lastError = relayTelemetry.lastError ?: state.lastError,
        lastFailureClass = relayTelemetry.lastFailureClass ?: state.lastFailureClass,
    )
