package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneDelta
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
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

    suspend fun cancelRun(
        runGeneration: String,
        observedAtMillis: Long,
    )

    suspend fun recordRecoveryReceipt(
        runGeneration: String,
        receipt: RemoteDeviceRecoveryReceipt,
        observedAtMillis: Long,
    ) {
    }
}

interface RemoteDeviceAcceptanceStartupReconciler {
    suspend fun reconcilePendingRun()
}

@Singleton
internal class DefaultRemoteDeviceAcceptanceEvidenceWriter internal constructor(
    private val durableStateStore: DiagnosticsDurableStateStore,
    private val wallClock: () -> Long,
) : RemoteDeviceAcceptanceEvidenceWriter,
    RemoteDeviceAcceptanceStartupReconciler {
    @Inject
    constructor(
        durableStateStore: DiagnosticsDurableStateStore,
    ) : this(durableStateStore, System::currentTimeMillis)

    private val lock = Mutex()

    override suspend fun beginRun(
        runGeneration: String,
        observedAtMillis: Long,
    ) {
        lock.withLock {
            val nextState = pendingGenerationState(runGeneration, observedAtMillis)
            val persisted =
                durableStateStore
                    .getDurableState(RemoteAcceptancePendingGenerationKey)
            when {
                persisted == null || persisted.value == runGeneration -> {
                    durableStateStore.upsertDurableState(nextState)
                }

                persisted.value.canonicalRunGenerationOrNull() == null -> {
                    check(
                        durableStateStore.replaceDurableStateIfCurrent(
                            state = nextState,
                            expectedValue = persisted.value,
                        ),
                    ) { "Remote acceptance pending generation changed during replacement" }
                }

                else -> {
                    reconcilePendingGeneration(
                        interrupted = persisted.value,
                        replacementState = nextState,
                        observedAtMillis = observedAtMillis,
                    )
                }
            }
        }
    }

    override suspend fun record(
        runGeneration: String,
        event: DeviceRuntimeEvidence.BackgroundSurvival,
    ) {
        lock.withLock {
            val durableEvent = event.toDurableEvent(runGeneration)
            val nativeEvent = durableEvent.toNativeSessionEvent(event.observedAtMillis)
            if (event.isTerminalBackgroundEvent()) {
                durableStateStore.insertNativeSessionEventAndClearDurableStateIfCurrent(
                    event = nativeEvent,
                    key = RemoteAcceptancePendingGenerationKey,
                    expectedValue = runGeneration,
                )
            } else {
                durableStateStore.insertNativeSessionEventAndUpsertDurableStateIfCurrent(
                    event = nativeEvent,
                    state = pendingGenerationState(runGeneration, event.observedAtMillis),
                    expectedValue = runGeneration,
                )
            }
        }
    }

    override suspend fun cancelRun(
        runGeneration: String,
        observedAtMillis: Long,
    ) {
        lock.withLock {
            durableStateStore.insertNativeSessionEventAndClearDurableStateIfCurrent(
                event =
                    durableLifecycleEvent(
                        runGeneration = runGeneration,
                        phase = RemoteAcceptanceCancelledPhase,
                        reason = RemoteAcceptanceCancelledReason,
                    ).toNativeSessionEvent(observedAtMillis),
                key = RemoteAcceptancePendingGenerationKey,
                expectedValue = runGeneration,
            )
        }
    }

    override suspend fun recordRecoveryReceipt(
        runGeneration: String,
        receipt: RemoteDeviceRecoveryReceipt,
        observedAtMillis: Long,
    ) {
        lock.withLock {
            val persisted = durableStateStore.getDurableState(RemoteAcceptancePendingGenerationKey) ?: return@withLock
            if (persisted.value != runGeneration) return@withLock
            durableStateStore.insertNativeSessionEventAndUpsertDurableStateIfCurrent(
                event = receipt.privacySafe().toNativeSessionEvent(runGeneration, observedAtMillis),
                state = persisted.copy(updatedAt = observedAtMillis),
                expectedValue = runGeneration,
            )
        }
    }

    override suspend fun reconcilePendingRun() {
        lock.withLock {
            val persisted =
                durableStateStore
                    .getDurableState(RemoteAcceptancePendingGenerationKey)
                    ?: return@withLock
            val interrupted = persisted.value.canonicalRunGenerationOrNull()
            if (interrupted == null) {
                durableStateStore.clearDurableStateIfCurrent(
                    RemoteAcceptancePendingGenerationKey,
                    persisted.value,
                )
                return@withLock
            }
            durableStateStore.insertNativeSessionEventAndClearDurableStateIfCurrent(
                event =
                    durableLifecycleEvent(
                        runGeneration = interrupted,
                        phase = RemoteAcceptanceInterruptedPhase,
                        reason = RemoteAcceptanceInterruptedBeforeStartup,
                    ).toNativeSessionEvent(wallClock()),
                key = RemoteAcceptancePendingGenerationKey,
                expectedValue = interrupted,
            )
        }
    }

    private suspend fun reconcilePendingGeneration(
        interrupted: String,
        replacementState: DiagnosticsDurableStateEntity,
        observedAtMillis: Long,
    ) {
        durableStateStore.reconcileDurableStateWithTerminalEvent(
            key = RemoteAcceptancePendingGenerationKey,
            expectedValue = interrupted,
            replacementState = replacementState,
            terminalEventId = runTerminalEventId(interrupted),
            missingTerminalEvent =
                durableLifecycleEvent(
                    runGeneration = interrupted,
                    phase = RemoteAcceptanceInterruptedPhase,
                    reason = RemoteAcceptanceInterruptedBeforeNextRun,
                ).toNativeSessionEvent(observedAtMillis),
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

    @Binds
    @Singleton
    abstract fun bindRemoteDeviceAcceptanceStartupReconciler(
        writer: DefaultRemoteDeviceAcceptanceEvidenceWriter,
    ): RemoteDeviceAcceptanceStartupReconciler
}

private data class RemoteAcceptanceDurableEvent(
    val runGeneration: String,
    val phase: String,
    val outcome: String,
    val reason: String,
    val message: String,
) {
    val id: String =
        durableEventId(
            runGeneration = runGeneration,
            phase = phase,
            outcome = outcome,
            reason = reason,
        )
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

private fun RemoteAcceptanceDurableEvent.toNativeSessionEvent(createdAt: Long): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = id,
        sessionId = null,
        source = RemoteAcceptanceEventSource,
        level = RemoteAcceptanceEventLevelInfo,
        message = message,
        createdAt = createdAt,
        runtimeId = runGeneration,
        mode = Mode.VPN.name,
        subsystem = RemoteAcceptanceSubsystem,
    )

private fun pendingGenerationState(
    runGeneration: String,
    observedAtMillis: Long,
): DiagnosticsDurableStateEntity =
    DiagnosticsDurableStateEntity(
        key = RemoteAcceptancePendingGenerationKey,
        value = runGeneration,
        updatedAt = observedAtMillis,
    )

private fun RemoteDeviceRecoveryReceipt.toNativeSessionEvent(
    runGeneration: String,
    observedAtMillis: Long,
): NativeSessionEventEntity {
    val eventId =
        listOf(RemoteAcceptanceRecoveryEvent, runGeneration, generation)
            .joinToString(separator = "|")
            .sha256Hex()
            .let { hash -> "${RemoteAcceptanceRecoveryEvent}_${hash.take(RemoteAcceptanceEventIdHashChars)}" }
    return NativeSessionEventEntity(
        id = eventId,
        sessionId = null,
        source = RemoteAcceptanceEventSource,
        level = RemoteAcceptanceEventLevelInfo,
        message =
            buildString {
                append("event=").append(RemoteAcceptanceRecoveryEvent)
                append(" run_generation=").append(runGeneration)
                append(" recovery_generation=").append(generation)
                append(" start_origin=").append(startOrigin)
                append(" user_unlocked=").append(userUnlocked)
                append(" always_on=").append(alwaysOn)
                append(" lockdown=").append(lockdown)
                append(" service_instance_changed=").append(serviceInstanceChanged)
                append(" time_to_fgs=").append(timeToForegroundService)
                append(" time_to_tun=").append(timeToTun)
                append(" time_to_first_flow=").append(timeToFirstFlow)
                append(" post_start_data_plane=").append(postStartDataPlaneOutcome)
            },
        createdAt = observedAtMillis,
        runtimeId = runGeneration,
        mode = Mode.VPN.name,
        subsystem = RemoteAcceptanceSubsystem,
    )
}

private fun durableEventId(
    runGeneration: String,
    phase: String,
    outcome: String,
    reason: String,
): String {
    val idParts =
        if (phase.isTerminalDurablePhase(outcome)) {
            listOf(RemoteAcceptanceBackgroundEvent, runGeneration, RemoteAcceptanceTerminalEventIdPart)
        } else {
            listOf(RemoteAcceptanceBackgroundEvent, runGeneration, phase, outcome, reason)
        }
    return idParts
        .joinToString(separator = "|")
        .sha256Hex()
        .let { hash -> "${RemoteAcceptanceBackgroundEvent}_${hash.take(RemoteAcceptanceEventIdHashChars)}" }
}

private fun runTerminalEventId(runGeneration: String): String =
    durableEventId(
        runGeneration = runGeneration,
        phase = RemoteAcceptanceTerminalEventIdPart,
        outcome = "terminal",
        reason = "terminal",
    )

private fun String.isTerminalDurablePhase(outcome: String): Boolean =
    this == RemoteAcceptanceTerminalEventIdPart ||
        this == RemoteAcceptanceInterruptedPhase ||
        this == RemoteAcceptanceCancelledPhase ||
        this == "after_wake" ||
        (this == "screen_off_probe" && outcome != "pending" && outcome != "passed")

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

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String.canonicalRunGenerationOrNull(): String? =
    takeIf { value -> value.length == CanonicalUuidLength }
        ?.let { value -> runCatching { UUID.fromString(value).toString() }.getOrNull() }
        ?.takeIf { canonical -> canonical == this }

private const val RemoteAcceptanceBackgroundEvent = "remote_acceptance_background"
private const val RemoteAcceptanceRecoveryEvent = "remote_acceptance_recovery"
private const val RemoteAcceptanceEventSource = "remote_device_acceptance"
private const val RemoteAcceptanceEventLevelInfo = "info"
private const val RemoteAcceptanceSubsystem = "remote_acceptance"
private const val RemoteAcceptanceEventIdHashChars = 32
private const val CanonicalUuidLength = 36
internal const val RemoteAcceptancePendingGenerationKey = "remote_acceptance_pending_generation"
private const val RemoteAcceptanceInterruptedBeforeNextRun = "interrupted_before_next_run"
private const val RemoteAcceptanceInterruptedBeforeStartup = "interrupted_before_startup"
private const val RemoteAcceptanceCancelledReason = "cancelled"
private const val RemoteAcceptanceInterruptedPhase = "run_interrupted"
private const val RemoteAcceptanceCancelledPhase = "run_cancelled"
private const val RemoteAcceptanceTerminalEventIdPart = "run_terminal"
internal const val RemoteAcceptanceCancellationPersistenceTimeoutMs = 2_000L

internal enum class RemoteAcceptanceRecoveryWarning {
    PreviousRunTimeout,
    CancellationPersistenceTimeout,
    CancellationPersistenceCancelled,
    CancellationPersistenceDatabase,
    CancellationPersistenceState,
}

internal fun remoteAcceptanceRecoveryWarning(warning: RemoteAcceptanceRecoveryWarning): String =
    when (warning) {
        RemoteAcceptanceRecoveryWarning.PreviousRunTimeout -> {
            "Remote acceptance previous run cleanup timed out"
        }

        RemoteAcceptanceRecoveryWarning.CancellationPersistenceTimeout -> {
            "Remote acceptance cancellation evidence timed out"
        }

        RemoteAcceptanceRecoveryWarning.CancellationPersistenceCancelled -> {
            "Remote acceptance cancellation evidence was cancelled"
        }

        RemoteAcceptanceRecoveryWarning.CancellationPersistenceDatabase -> {
            "Remote acceptance cancellation evidence failed: database"
        }

        RemoteAcceptanceRecoveryWarning.CancellationPersistenceState -> {
            "Remote acceptance cancellation evidence failed: state"
        }
    }

internal fun logRecoveryWarning(warning: RemoteAcceptanceRecoveryWarning) {
    Logger.w { remoteAcceptanceRecoveryWarning(warning) }
}
