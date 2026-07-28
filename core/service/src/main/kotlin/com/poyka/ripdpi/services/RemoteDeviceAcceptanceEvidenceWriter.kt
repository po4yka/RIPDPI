package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneDelta
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal interface RemoteDeviceAcceptanceEvidenceWriter {
    suspend fun beginRun(
        runGeneration: String,
        observedAtMillis: Long,
    )

    suspend fun record(
        runGeneration: String,
        event: DeviceRuntimeEvidence.BackgroundSurvival,
    )
}

@Singleton
internal class DefaultRemoteDeviceAcceptanceEvidenceWriter
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) : RemoteDeviceAcceptanceEvidenceWriter {
        private val lock = Mutex()
        private var pendingRunGeneration: String? = null

        override suspend fun beginRun(
            runGeneration: String,
            observedAtMillis: Long,
        ) {
            lock.withLock {
                val interrupted = pendingRunGeneration?.takeIf { it != runGeneration }
                if (interrupted != null) {
                    persist(
                        runGeneration = interrupted,
                        createdAt = observedAtMillis,
                        message =
                            buildInterruptedMessage(
                                runGeneration = interrupted,
                                reason = RemoteAcceptanceInterruptedBeforeNextRun,
                            ),
                    )
                }
                pendingRunGeneration = null
            }
        }

        override suspend fun record(
            runGeneration: String,
            event: DeviceRuntimeEvidence.BackgroundSurvival,
        ) {
            lock.withLock {
                persist(
                    runGeneration = runGeneration,
                    createdAt = event.observedAtMillis,
                    message = event.toDurableMessage(runGeneration),
                )
                when (event.outcome) {
                    DeviceRuntimeBackgroundSurvivalOutcome.Pending -> {
                        pendingRunGeneration = runGeneration
                    }

                    DeviceRuntimeBackgroundSurvivalOutcome.Passed,
                    DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    -> {
                        if (pendingRunGeneration == runGeneration) {
                            pendingRunGeneration = null
                        }
                    }
                }
            }
        }

        private suspend fun persist(
            runGeneration: String,
            createdAt: Long,
            message: String,
        ) {
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = null,
                    source = RemoteAcceptanceEventSource,
                    level = RemoteAcceptanceEventLevelInfo,
                    message = message,
                    createdAt = createdAt,
                    runtimeId = runGeneration,
                    mode = Mode.VPN.name,
                    subsystem = RemoteAcceptanceSubsystem,
                ),
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RemoteDeviceAcceptanceEvidenceWriterModule {
    @Binds
    @Singleton
    abstract fun bindRemoteDeviceAcceptanceEvidenceWriter(
        writer: DefaultRemoteDeviceAcceptanceEvidenceWriter,
    ): RemoteDeviceAcceptanceEvidenceWriter
}

private fun DeviceRuntimeEvidence.BackgroundSurvival.toDurableMessage(runGeneration: String): String =
    buildString {
        append("event=").append(RemoteAcceptanceBackgroundEvent)
        append(" run_generation=").append(runGeneration)
        append(" phase=").append(phase.toWireValue())
        append(" outcome=").append(outcome.toWireValue())
        append(" reason=").append(reason.toWireValue())
        append(" screen_off_ms=").append(screenOffDurationMs?.coerceAtLeast(0L) ?: "unchanged")
        appendDelta(counterDelta)
        append(" vendor_policy_visibility=unavailable")
    }

private fun buildInterruptedMessage(
    runGeneration: String,
    reason: String,
): String =
    "event=$RemoteAcceptanceBackgroundEvent " +
        "run_generation=$runGeneration " +
        "phase=run_interrupted " +
        "outcome=inconclusive " +
        "reason=$reason " +
        "screen_off_ms=unchanged " +
        "delta_tunnel_packets=unchanged " +
        "delta_tunnel_bytes=unchanged " +
        "delta_native_packets=unchanged " +
        "delta_native_bytes=unchanged " +
        "vendor_policy_visibility=unavailable"

private fun StringBuilder.appendDelta(delta: DeviceRuntimeDataPlaneDelta?) {
    append(" delta_tunnel_packets=").append(delta?.tunnelPackets?.coerceAtLeast(0L) ?: "unchanged")
    append(" delta_tunnel_bytes=").append(delta?.tunnelBytes?.coerceAtLeast(0L) ?: "unchanged")
    append(" delta_native_packets=").append(delta?.nativePackets?.coerceAtLeast(0L) ?: "unchanged")
    append(" delta_native_bytes=").append(delta?.nativeBytes?.coerceAtLeast(0L) ?: "unchanged")
}

private fun DeviceRuntimeBackgroundSurvivalPhase.toWireValue(): String =
    when (this) {
        DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted -> "screen_off_started"
        DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe -> "screen_off_probe"
        DeviceRuntimeBackgroundSurvivalPhase.AfterWake -> "after_wake"
    }

private fun DeviceRuntimeBackgroundSurvivalOutcome.toWireValue(): String =
    when (this) {
        DeviceRuntimeBackgroundSurvivalOutcome.Pending -> "pending"
        DeviceRuntimeBackgroundSurvivalOutcome.Passed -> "passed"
        DeviceRuntimeBackgroundSurvivalOutcome.Failed -> "failed"
        DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive -> "inconclusive"
    }

private fun DeviceRuntimeBackgroundSurvivalReason?.toWireValue(): String =
    when (this) {
        DeviceRuntimeBackgroundSurvivalReason.TooShort -> "too_short"
        DeviceRuntimeBackgroundSurvivalReason.ServiceStopped -> "service_stopped"
        DeviceRuntimeBackgroundSurvivalReason.ServiceRestarted -> "service_restarted"
        DeviceRuntimeBackgroundSurvivalReason.ScreenOffProbeMissing -> "screen_off_probe_missing"
        DeviceRuntimeBackgroundSurvivalReason.NoDataPlaneDelta -> "no_data_plane_delta"
        DeviceRuntimeBackgroundSurvivalReason.PostActionProbeFailed -> "post_action_probe_failed"
        DeviceRuntimeBackgroundSurvivalReason.TelemetryStale -> "telemetry_stale"
        DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged -> "screen_state_changed"
        DeviceRuntimeBackgroundSurvivalReason.Cancelled -> "cancelled"
        null -> "unchanged"
    }

private const val RemoteAcceptanceBackgroundEvent = "remote_acceptance_background"
private const val RemoteAcceptanceEventSource = "remote_device_acceptance"
private const val RemoteAcceptanceEventLevelInfo = "info"
private const val RemoteAcceptanceSubsystem = "remote_acceptance"
private const val RemoteAcceptanceInterruptedBeforeNextRun = "interrupted_before_next_run"
