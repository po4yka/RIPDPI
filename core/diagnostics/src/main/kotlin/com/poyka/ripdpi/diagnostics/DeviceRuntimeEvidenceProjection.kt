package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneDelta
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.DeviceRuntimeForegroundCallKind
import com.poyka.ripdpi.data.DeviceRuntimeForegroundOutcome
import com.poyka.ripdpi.data.DeviceRuntimeKillSwitchStatus
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.DeviceRuntimeMemoryPressure
import com.poyka.ripdpi.data.DeviceRuntimeValue
import com.poyka.ripdpi.data.Mode

internal enum class DeviceStateTrigger(
    val wireValue: String,
    val singleton: Boolean,
) {
    ServiceStart("service_start", true),
    RunningReady("running_ready", true),
    SystemStateChanged("system_state_changed", false),
    ReconnectStart("reconnect_start", false),
    Failure("failure", true),
    Recovery("recovery", false),
    Handover("handover", false),
    Stop("stop", true),
    ServiceCreated("service_created", true),
    ServiceStartCommand("service_start_command", false),
    ForegroundCall("foreground_call", false),
    VpnPolicy("vpn_policy", false),
    MemoryTrim("memory_trim", false),
    RemoteAcceptanceBackground("remote_acceptance_background", false),
    ServiceDestroyed("service_destroyed", true),
    ;

    val isTerminalRuntime: Boolean
        get() = this == ServiceDestroyed
}

internal data class PendingDeviceStateEvent(
    val trigger: DeviceStateTrigger,
    val snapshot: DeviceStateSnapshot,
    val mode: Mode?,
    val createdAt: Long,
    val runtimeEvidence: DeviceRuntimeEvidence? = null,
) {
    fun toCanonicalMessage(): String =
        buildString {
            append("event=device_state")
            append(" trigger=").append(trigger.wireValue)
            append(" screen_interactive=").append(snapshot.screenInteractive.wireValue)
            append(" device_idle=").append(snapshot.deviceIdle.wireValue)
            append(" power_saver=").append(snapshot.powerSaver.wireValue)
            append(" background_restricted=").append(snapshot.backgroundRestricted.wireValue)
            append(" battery_optimization_exempt=").append(snapshot.batteryOptimizationExempt.wireValue)
            append(" low_power_standby=").append(snapshot.lowPowerStandby.wireValue)
            append(" low_power_standby_exempt=").append(snapshot.lowPowerStandbyExempt.wireValue)
            append(" battery_level=").append(snapshot.batteryLevel.wireValue)
            append(" charging=").append(snapshot.charging.wireValue)
            append(" standby_bucket=").append(snapshot.standbyBucket.wireValue)
            append(" notification_permission=").append(snapshot.notificationPermission.wireValue)
            append(" notifications_allowed=").append(snapshot.notificationsAllowed.wireValue)
            append(" foreground_notification_channels=").append(snapshot.foregroundNotificationChannels.wireValue)
            append(" memory_pressure=").append(snapshot.memoryPressure.wireValue)
            append(" thermal_status=").append(snapshot.thermalStatus.wireValue)
            append(" manufacturer_family=").append(snapshot.manufacturerFamily.wireValue)
            append(" vendor_policy_visibility=unavailable")
            appendRuntimeEvidence(runtimeEvidence)
        }
}

private fun StringBuilder.appendRuntimeEvidence(event: DeviceRuntimeEvidence?) {
    append(" service_lifecycle=").append(event.serviceLifecycleValue())
    append(" foreground_call_kind=").append(event.foregroundCallKindValue())
    append(" foreground_outcome=").append(event.foregroundOutcomeValue())
    append(" vpn_always_on=").append(event.vpnAlwaysOnValue())
    append(" vpn_lockdown=").append(event.vpnLockdownValue())
    append(" vpn_kill_switch=").append(event.vpnKillSwitchValue())
    append(" memory_trim_callback=").append(event.memoryTrimValue())
    append(" remote_acceptance_background_phase=").append(event.backgroundSurvivalPhaseValue())
    append(" remote_acceptance_background_outcome=").append(event.backgroundSurvivalOutcomeValue())
    append(" remote_acceptance_background_reason=").append(event.backgroundSurvivalReasonValue())
    append(" remote_acceptance_screen_off_ms=").append(event.backgroundSurvivalDurationValue())
    append(" remote_acceptance_delta_tunnel_packets=").append(event.backgroundSurvivalTunnelPacketDeltaValue())
    append(" remote_acceptance_delta_tunnel_bytes=").append(event.backgroundSurvivalTunnelByteDeltaValue())
    append(" remote_acceptance_delta_native_packets=").append(event.backgroundSurvivalNativePacketDeltaValue())
    append(" remote_acceptance_delta_native_bytes=").append(event.backgroundSurvivalNativeByteDeltaValue())
}

internal fun DeviceRuntimeEvidence.toTrigger(): DeviceStateTrigger =
    when (this) {
        is DeviceRuntimeEvidence.ServiceLifecycle -> {
            when (phase) {
                DeviceRuntimeLifecyclePhase.Created -> DeviceStateTrigger.ServiceCreated
                DeviceRuntimeLifecyclePhase.StartCommand -> DeviceStateTrigger.ServiceStartCommand
                DeviceRuntimeLifecyclePhase.Destroyed -> DeviceStateTrigger.ServiceDestroyed
            }
        }

        is DeviceRuntimeEvidence.ForegroundCall -> {
            DeviceStateTrigger.ForegroundCall
        }

        is DeviceRuntimeEvidence.VpnPolicy -> {
            DeviceStateTrigger.VpnPolicy
        }

        is DeviceRuntimeEvidence.MemoryTrim -> {
            DeviceStateTrigger.MemoryTrim
        }

        is DeviceRuntimeEvidence.BackgroundSurvival -> {
            DeviceStateTrigger.RemoteAcceptanceBackground
        }
    }

internal fun DeviceRuntimeEvidence.modeOrNull(): Mode? =
    when (this) {
        is DeviceRuntimeEvidence.ServiceLifecycle -> mode
        is DeviceRuntimeEvidence.ForegroundCall -> mode
        is DeviceRuntimeEvidence.VpnPolicy -> Mode.VPN
        is DeviceRuntimeEvidence.MemoryTrim -> null
        is DeviceRuntimeEvidence.BackgroundSurvival -> mode
    }

internal fun DeviceRuntimeEvidence.startsServiceContext(): Boolean =
    (this is DeviceRuntimeEvidence.ServiceLifecycle && phase != DeviceRuntimeLifecyclePhase.Destroyed) ||
        this is DeviceRuntimeEvidence.ForegroundCall ||
        this is DeviceRuntimeEvidence.BackgroundSurvival

internal fun DeviceRuntimeEvidence.flushesWithoutSession(): Boolean =
    (this is DeviceRuntimeEvidence.ServiceLifecycle && phase == DeviceRuntimeLifecyclePhase.Destroyed) ||
        (this is DeviceRuntimeEvidence.ForegroundCall && outcome != DeviceRuntimeForegroundOutcome.Returned)

private fun DeviceRuntimeEvidence?.serviceLifecycleValue(): String =
    when ((this as? DeviceRuntimeEvidence.ServiceLifecycle)?.phase) {
        DeviceRuntimeLifecyclePhase.Created -> "created"
        DeviceRuntimeLifecyclePhase.StartCommand -> "start_command"
        DeviceRuntimeLifecyclePhase.Destroyed -> "destroyed"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.foregroundCallKindValue(): String =
    when ((this as? DeviceRuntimeEvidence.ForegroundCall)?.kind) {
        DeviceRuntimeForegroundCallKind.Initial -> "initial"
        DeviceRuntimeForegroundCallKind.Refresh -> "refresh"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.foregroundOutcomeValue(): String =
    when ((this as? DeviceRuntimeEvidence.ForegroundCall)?.outcome) {
        DeviceRuntimeForegroundOutcome.Returned -> "returned"
        DeviceRuntimeForegroundOutcome.StartNotAllowed -> "start_not_allowed"
        DeviceRuntimeForegroundOutcome.SecurityRejected -> "security_rejected"
        DeviceRuntimeForegroundOutcome.InvalidType -> "invalid_type"
        DeviceRuntimeForegroundOutcome.OtherFailure -> "other_failure"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.vpnAlwaysOnValue(): String =
    (this as? DeviceRuntimeEvidence.VpnPolicy)?.alwaysOn.toWireValue()

private fun DeviceRuntimeEvidence?.vpnLockdownValue(): String =
    (this as? DeviceRuntimeEvidence.VpnPolicy)?.lockdown.toWireValue()

private fun DeviceRuntimeValue?.toWireValue(): String =
    when (this) {
        DeviceRuntimeValue.Enabled -> "enabled"
        DeviceRuntimeValue.Disabled -> "disabled"
        DeviceRuntimeValue.Unknown -> "unknown"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.vpnKillSwitchValue(): String =
    when ((this as? DeviceRuntimeEvidence.VpnPolicy)?.killSwitch) {
        DeviceRuntimeKillSwitchStatus.Enabled -> "enabled"
        DeviceRuntimeKillSwitchStatus.NotEnabled -> "not_enabled"
        DeviceRuntimeKillSwitchStatus.Unknown -> "unknown"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.memoryTrimValue(): String =
    when ((this as? DeviceRuntimeEvidence.MemoryTrim)?.pressure) {
        DeviceRuntimeMemoryPressure.UiHidden -> "ui_hidden"
        DeviceRuntimeMemoryPressure.Background -> "background"
        DeviceRuntimeMemoryPressure.Moderate -> "moderate"
        DeviceRuntimeMemoryPressure.Critical -> "critical"
        DeviceRuntimeMemoryPressure.Unknown -> "unknown"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.backgroundSurvivalPhaseValue(): String =
    when ((this as? DeviceRuntimeEvidence.BackgroundSurvival)?.phase) {
        DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted -> "screen_off_started"
        DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe -> "screen_off_probe"
        DeviceRuntimeBackgroundSurvivalPhase.AfterWake -> "after_wake"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.backgroundSurvivalOutcomeValue(): String =
    when ((this as? DeviceRuntimeEvidence.BackgroundSurvival)?.outcome) {
        DeviceRuntimeBackgroundSurvivalOutcome.Pending -> "pending"
        DeviceRuntimeBackgroundSurvivalOutcome.Passed -> "passed"
        DeviceRuntimeBackgroundSurvivalOutcome.Failed -> "failed"
        DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive -> "inconclusive"
        null -> "unchanged"
    }

private fun DeviceRuntimeEvidence?.backgroundSurvivalReasonValue(): String =
    when ((this as? DeviceRuntimeEvidence.BackgroundSurvival)?.reason) {
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

private fun DeviceRuntimeEvidence?.backgroundSurvivalDurationValue(): String =
    (this as? DeviceRuntimeEvidence.BackgroundSurvival)
        ?.screenOffDurationMs
        ?.coerceAtLeast(0L)
        ?.toString()
        ?: "unchanged"

private fun DeviceRuntimeEvidence?.backgroundSurvivalTunnelPacketDeltaValue(): String =
    backgroundSurvivalDeltaValue { tunnelPackets }

private fun DeviceRuntimeEvidence?.backgroundSurvivalTunnelByteDeltaValue(): String =
    backgroundSurvivalDeltaValue { tunnelBytes }

private fun DeviceRuntimeEvidence?.backgroundSurvivalNativePacketDeltaValue(): String =
    backgroundSurvivalDeltaValue { nativePackets }

private fun DeviceRuntimeEvidence?.backgroundSurvivalNativeByteDeltaValue(): String =
    backgroundSurvivalDeltaValue { nativeBytes }

private fun DeviceRuntimeEvidence?.backgroundSurvivalDeltaValue(
    selector: DeviceRuntimeDataPlaneDelta.() -> Long,
): String =
    (this as? DeviceRuntimeEvidence.BackgroundSurvival)
        ?.counterDelta
        ?.selector()
        ?.coerceAtLeast(0L)
        ?.toString()
        ?: "unchanged"
