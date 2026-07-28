package com.poyka.ripdpi.services

import android.content.Context
import android.content.SharedPreferences
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
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

    suspend fun cancelRun(
        runGeneration: String,
        observedAtMillis: Long,
    )
}

@Singleton
internal class DefaultRemoteDeviceAcceptanceEvidenceWriter internal constructor(
    private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    private val ledger: SharedPreferences,
) : RemoteDeviceAcceptanceEvidenceWriter {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) : this(
        artifactWriteStore = artifactWriteStore,
        ledger = context.getSharedPreferences(RemoteAcceptanceEvidencePrefsName, Context.MODE_PRIVATE),
    )

    private val lock = Mutex()

    override suspend fun beginRun(
        runGeneration: String,
        observedAtMillis: Long,
    ) {
        lock.withLock {
            val interrupted = ledger.pendingGeneration()?.takeIf { it != runGeneration }
            if (interrupted != null) {
                persistDurableEvent(
                    event =
                        durableLifecycleEvent(
                            runGeneration = interrupted,
                            phase = RemoteAcceptanceInterruptedPhase,
                            reason = RemoteAcceptanceInterruptedBeforeNextRun,
                        ),
                    createdAt = observedAtMillis,
                )
                ledger.clearPendingGeneration(interrupted)
            }
            ledger.persistPendingGeneration(runGeneration)
        }
    }

    override suspend fun record(
        runGeneration: String,
        event: DeviceRuntimeEvidence.BackgroundSurvival,
    ) {
        lock.withLock {
            persistDurableEvent(
                event = event.toDurableEvent(runGeneration),
                createdAt = event.observedAtMillis,
            )
            if (event.isTerminalBackgroundEvent()) {
                ledger.clearPendingGeneration(runGeneration)
            } else {
                ledger.persistPendingGeneration(runGeneration)
            }
        }
    }

    override suspend fun cancelRun(
        runGeneration: String,
        observedAtMillis: Long,
    ) {
        lock.withLock {
            if (ledger.pendingGeneration() == runGeneration) {
                persistDurableEvent(
                    event =
                        durableLifecycleEvent(
                            runGeneration = runGeneration,
                            phase = RemoteAcceptanceCancelledPhase,
                            reason = RemoteAcceptanceCancelledReason,
                        ),
                    createdAt = observedAtMillis,
                )
                ledger.clearPendingGeneration(runGeneration)
            }
        }
    }

    private suspend fun persistDurableEvent(
        event: RemoteAcceptanceDurableEvent,
        createdAt: Long,
    ) {
        artifactWriteStore.insertNativeSessionEvent(
            NativeSessionEventEntity(
                id = event.id,
                sessionId = null,
                source = RemoteAcceptanceEventSource,
                level = RemoteAcceptanceEventLevelInfo,
                message = event.message,
                createdAt = createdAt,
                runtimeId = event.runGeneration,
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

private data class RemoteAcceptanceDurableEvent(
    val runGeneration: String,
    val phase: String,
    val outcome: String,
    val reason: String,
    val message: String,
) {
    val id: String =
        listOf(RemoteAcceptanceBackgroundEvent, runGeneration, phase, outcome, reason)
            .joinToString(separator = "|")
            .sha256Hex()
            .let { hash -> "${RemoteAcceptanceBackgroundEvent}_${hash.take(RemoteAcceptanceEventIdHashChars)}" }
}

private fun DeviceRuntimeEvidence.BackgroundSurvival.toDurableEvent(
    runGeneration: String,
): RemoteAcceptanceDurableEvent {
    val phase = phase.toWireValue()
    val outcome = outcome.toWireValue()
    val reason = reason.toWireValue()
    return RemoteAcceptanceDurableEvent(
        runGeneration = runGeneration,
        phase = phase,
        outcome = outcome,
        reason = reason,
        message =
            buildString {
                append("event=").append(RemoteAcceptanceBackgroundEvent)
                append(" run_generation=").append(runGeneration)
                append(" phase=").append(phase)
                append(" outcome=").append(outcome)
                append(" reason=").append(reason)
                append(" screen_off_ms=").append(screenOffDurationMs?.coerceAtLeast(0L) ?: "unchanged")
                appendDelta(counterDelta)
                append(" vendor_policy_visibility=unavailable")
            },
    )
}

private fun durableLifecycleEvent(
    runGeneration: String,
    phase: String,
    reason: String,
): RemoteAcceptanceDurableEvent =
    RemoteAcceptanceDurableEvent(
        runGeneration = runGeneration,
        phase = phase,
        outcome = "inconclusive",
        reason = reason,
        message =
            "event=$RemoteAcceptanceBackgroundEvent " +
                "run_generation=$runGeneration " +
                "phase=$phase " +
                "outcome=inconclusive " +
                "reason=$reason " +
                "screen_off_ms=unchanged " +
                "delta_tunnel_packets=unchanged " +
                "delta_tunnel_bytes=unchanged " +
                "delta_native_packets=unchanged " +
                "delta_native_bytes=unchanged " +
                "vendor_policy_visibility=unavailable",
    )

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

private fun DeviceRuntimeEvidence.BackgroundSurvival.isTerminalBackgroundEvent(): Boolean =
    phase == DeviceRuntimeBackgroundSurvivalPhase.AfterWake ||
        (
            phase == DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe &&
                outcome != DeviceRuntimeBackgroundSurvivalOutcome.Pending &&
                outcome != DeviceRuntimeBackgroundSurvivalOutcome.Passed
        )

private fun SharedPreferences.pendingGeneration(): String? =
    getString(RemoteAcceptanceEvidencePendingGenerationKey, null)

private fun SharedPreferences.persistPendingGeneration(runGeneration: String) {
    check(edit().putString(RemoteAcceptanceEvidencePendingGenerationKey, runGeneration).commit()) {
        "Failed to persist remote acceptance background evidence generation"
    }
}

private fun SharedPreferences.clearPendingGeneration(runGeneration: String) {
    if (pendingGeneration() == runGeneration) {
        check(edit().remove(RemoteAcceptanceEvidencePendingGenerationKey).commit()) {
            "Failed to clear remote acceptance background evidence generation"
        }
    }
}

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal const val RemoteAcceptanceEvidencePrefsName = "ripdpi_remote_acceptance_background_evidence"
private const val RemoteAcceptanceBackgroundEvent = "remote_acceptance_background"
private const val RemoteAcceptanceEventSource = "remote_device_acceptance"
private const val RemoteAcceptanceEventLevelInfo = "info"
private const val RemoteAcceptanceSubsystem = "remote_acceptance"
private const val RemoteAcceptanceEventIdHashChars = 32
private const val RemoteAcceptanceEvidencePendingGenerationKey = "pending_generation"
private const val RemoteAcceptanceInterruptedBeforeNextRun = "interrupted_before_next_run"
private const val RemoteAcceptanceCancelledReason = "cancelled"
private const val RemoteAcceptanceInterruptedPhase = "run_interrupted"
private const val RemoteAcceptanceCancelledPhase = "run_cancelled"
