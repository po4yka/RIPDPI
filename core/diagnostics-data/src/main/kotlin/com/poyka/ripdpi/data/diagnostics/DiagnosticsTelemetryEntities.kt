package com.poyka.ripdpi.data.diagnostics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.poyka.ripdpi.data.RuntimeTelemetryState
import kotlinx.serialization.Serializable

@Entity(
    tableName = "telemetry_samples",
    indices = [
        Index(
            name = "index_telemetry_samples_sessionId_createdAt",
            value = ["sessionId", "createdAt"],
        ),
        Index(
            name = "index_telemetry_samples_connectionSessionId_createdAt",
            value = ["connectionSessionId", "createdAt"],
        ),
        Index(
            name = "index_telemetry_samples_diagnosticsRunId_diagnosticsStageKey_createdAt",
            value = ["diagnosticsRunId", "diagnosticsStageKey", "createdAt"],
        ),
        Index(
            name = "index_telemetry_samples_createdAt",
            value = ["createdAt"],
        ),
        Index(
            name = "index_telemetry_samples_fingerprint_mode_createdAt",
            value = ["telemetryNetworkFingerprintHash", "activeMode", "createdAt"],
        ),
    ],
)
@Serializable
data class TelemetrySampleEntity(
    @PrimaryKey val id: String,
    val sessionId: String? = null,
    val connectionSessionId: String? = null,
    val diagnosticsRunId: String? = null,
    val diagnosticsStageKey: String? = null,
    val activeMode: String? = null,
    val connectionState: String,
    val networkType: String,
    val publicIp: String? = null,
    val failureClass: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val resolverId: String? = null,
    val resolverProtocol: String? = null,
    val resolverEndpoint: String? = null,
    val resolverLatencyMs: Long? = null,
    val dnsFailuresTotal: Long = 0,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String? = null,
    val networkHandoverClass: String? = null,
    val networkHandoverState: String? = null,
    val proxyTelemetryState: String = RuntimeTelemetryState.NoData.wireValue,
    val proxyTelemetryMessage: String? = null,
    val relayTelemetryState: String = RuntimeTelemetryState.NoData.wireValue,
    val relayTelemetryMessage: String? = null,
    val warpTelemetryState: String = RuntimeTelemetryState.NoData.wireValue,
    val warpTelemetryMessage: String? = null,
    val tunnelTelemetryState: String = RuntimeTelemetryState.NoData.wireValue,
    val tunnelTelemetryMessage: String? = null,
    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
    // Process memory footprint at sample time (Android 17 per-app cap signal).
    // Nullable so destructively-recreated/idle samples decode cleanly.
    val nativeHeapBytes: Long? = null,
    val processRssBytes: Long? = null,
    // Protocol kind emitted by the relay runtime (e.g. "vless_reality", "hysteria2").
    // Nullable so older rows (v5 schema) decode without a default value on the column.
    val relayProtocolKind: String? = null,
    val relayNativeEventsDropped: Long = 0,
    val createdAt: Long,
)

@Entity(
    tableName = "native_session_events",
    indices = [
        Index(
            name = "index_native_session_events_sessionId_createdAt",
            value = ["sessionId", "createdAt"],
        ),
        Index(
            name = "index_native_session_events_connectionSessionId_createdAt",
            value = ["connectionSessionId", "createdAt"],
        ),
        Index(
            name = "index_native_session_events_createdAt",
            value = ["createdAt"],
        ),
    ],
)
@Serializable
data class NativeSessionEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String? = null,
    val connectionSessionId: String? = null,
    val source: String,
    val level: String,
    val message: String,
    val createdAt: Long,
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val subsystem: String? = null,
    val attemptId: Long? = null,
    val attemptSequence: Long? = null,
    val stage: String? = null,
    val outcome: String? = null,
    val durationMs: Long? = null,
    val failureStage: String? = null,
    val failureClass: String? = null,
    val ioErrorKind: String? = null,
    val osErrorCode: Int? = null,
    val peerClosePhase: String? = null,
    val carrierDisposition: String? = null,
)
