package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import java.net.URI

internal data class SubprocessRelayTelemetryInputs(
    val config: ResolvedRipDpiRelayConfig?,
    val launchSpec: SubprocessSocksRelayLaunchSpec?,
    val running: Boolean,
    val runtimeVersion: String?,
    val lastError: String?,
    val lastFailureClass: String?,
    val runtimeStateOverride: String?,
    val upstreamVerified: Boolean = false,
)

internal class SubprocessRelayTelemetryProjector {
    private companion object {
        const val DefaultHttpsPort = 443
        const val DefaultHttpPort = 80
    }

    fun project(inputs: SubprocessRelayTelemetryInputs): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "relay",
            state = if (inputs.running) "running" else "idle",
            health = health(inputs),
            listenerAddress =
                inputs.config?.let {
                    URI(null, null, it.localSocksHost, it.localSocksPort, null, null, null).rawAuthority
                },
            upstreamAddress = inputs.launchSpec?.upstreamAddress?.let(::redactUpstreamAddress),
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

    /**
     * A live NaiveProxy listener says nothing about the upstream: health is `healthy` only after
     * an upstream open succeeded and `degraded` while a stream failure is the latest report.
     */
    private fun health(inputs: SubprocessRelayTelemetryInputs): String =
        when {
            !inputs.running -> "idle"
            inputs.launchSpec?.runtimeKind != RelayKindNaiveProxy -> "running"
            inputs.lastFailureClass != null -> "degraded"
            inputs.upstreamVerified -> "healthy"
            else -> "unknown"
        }

    private fun redactUpstreamAddress(value: String): String =
        runCatching { URI(value) }.getOrNull()?.let { uri ->
            uri.host?.let { host -> uri.portOrDefault()?.let { "$host:$it" } ?: host }
                ?: value.takeUnless { it.contains('/') || it.contains('@') }
        } ?: "redacted"

    private fun URI.portOrDefault(): Int? =
        when {
            port > 0 -> port
            scheme.equals("https", ignoreCase = true) || scheme.equals("wss", ignoreCase = true) -> DefaultHttpsPort
            scheme.equals("http", ignoreCase = true) || scheme.equals("ws", ignoreCase = true) -> DefaultHttpPort
            else -> null
        }
}
