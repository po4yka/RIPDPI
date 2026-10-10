package com.poyka.ripdpi.activities

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.LocalConstraintCode

internal fun DiagnosticsUiFactorySupport.localValue(value: Enum<*>): String =
    context.getString(localValueResource(value.name))

@StringRes
private fun localValueResource(name: String): Int = LocalValueResources[name] ?: R.string.diagnostics_local_unknown

private val LocalValueResources =
    mapOf(
        "ENABLED" to R.string.diagnostics_local_value_enabled,
        "DISABLED" to R.string.diagnostics_local_value_disabled,
        "UNKNOWN" to R.string.diagnostics_local_value_unknown,
        "PERMISSION_DENIED" to R.string.diagnostics_local_value_permission_denied,
        "UNSUPPORTED" to R.string.diagnostics_local_value_unsupported,
        "UNAVAILABLE" to R.string.diagnostics_local_value_unavailable,
        "WIFI" to R.string.diagnostics_local_value_wifi,
        "CELLULAR" to R.string.diagnostics_local_value_cellular,
        "VPN" to R.string.diagnostics_local_value_vpn,
        "NONE" to R.string.diagnostics_local_value_none,
        "OTHER" to R.string.diagnostics_local_value_other,
        "EXEMPT" to R.string.diagnostics_local_value_exempt,
        "DEFAULT_DATA" to R.string.diagnostics_local_value_default_data,
        "NOT_CONFIGURED" to R.string.diagnostics_local_value_not_configured,
        "CHANGED" to R.string.diagnostics_local_value_changed,
        "READY" to R.string.diagnostics_local_value_ready,
        "LOADED" to R.string.diagnostics_local_value_loaded,
        "ABSENT" to R.string.diagnostics_local_value_absent,
        "PIN_REQUIRED" to R.string.diagnostics_local_value_pin_required,
        "PUK_REQUIRED" to R.string.diagnostics_local_value_puk_required,
        "NETWORK_LOCKED" to R.string.diagnostics_local_value_network_locked,
        "NOT_READY" to R.string.diagnostics_local_value_not_ready,
        "PERMANENTLY_DISABLED" to R.string.diagnostics_local_value_permanently_disabled,
        "CARD_IO_ERROR" to R.string.diagnostics_local_value_card_io_error,
        "CARD_RESTRICTED" to R.string.diagnostics_local_value_card_restricted,
        "IN_SERVICE" to R.string.diagnostics_local_value_in_service,
        "OUT_OF_SERVICE" to R.string.diagnostics_local_value_out_of_service,
        "EMERGENCY_ONLY" to R.string.diagnostics_local_value_emergency_only,
        "POWER_OFF" to R.string.diagnostics_local_value_power_off,
        "CONNECTED" to R.string.diagnostics_local_value_connected,
        "CONNECTING" to R.string.diagnostics_local_value_connecting,
        "DISCONNECTED" to R.string.diagnostics_local_value_disconnected,
        "SUSPENDED" to R.string.diagnostics_local_value_suspended,
        "DISCONNECTING" to R.string.diagnostics_local_value_disconnecting,
        "HANDOVER" to R.string.diagnostics_local_value_handover,
    )

@StringRes
internal fun localConstraintText(code: LocalConstraintCode): Int =
    when (code) {
        LocalConstraintCode.AIRPLANE_MODE -> {
            R.string.diagnostics_local_hint_airplane_mode
        }

        LocalConstraintCode.BACKGROUND_DATA_RESTRICTED -> {
            R.string.diagnostics_local_hint_background_data_restricted
        }

        LocalConstraintCode.BACKGROUND_EXECUTION_RESTRICTED -> {
            R.string.diagnostics_local_hint_background_execution_restricted
        }

        LocalConstraintCode.POWER_SAVER -> {
            R.string.diagnostics_local_hint_power_saver
        }

        LocalConstraintCode.DEVICE_IDLE -> {
            R.string.diagnostics_local_hint_device_idle
        }

        LocalConstraintCode.BATTERY_OPTIMIZATION -> {
            R.string.diagnostics_local_hint_battery_optimization
        }

        LocalConstraintCode.NO_DEFAULT_DATA_SUBSCRIPTION -> {
            R.string.diagnostics_local_hint_no_default_data_subscription
        }

        LocalConstraintCode.SUBSCRIPTION_CHANGED -> {
            R.string.diagnostics_local_hint_subscription_changed
        }

        LocalConstraintCode.ACTIVE_DATA_DIFFERS -> {
            R.string.diagnostics_local_hint_active_data_differs
        }

        LocalConstraintCode.SIM_NOT_READY -> {
            R.string.diagnostics_local_hint_sim_not_ready
        }

        LocalConstraintCode.MOBILE_DATA_DISABLED -> {
            R.string.diagnostics_local_hint_mobile_data_disabled
        }

        LocalConstraintCode.DATA_CONNECTION_DISALLOWED -> {
            R.string.diagnostics_local_hint_data_connection_disallowed
        }

        LocalConstraintCode.ROAMING_DISABLED -> {
            R.string.diagnostics_local_hint_roaming_disabled
        }

        LocalConstraintCode.VOICE_SERVICE_UNAVAILABLE -> {
            R.string.diagnostics_local_hint_voice_service_unavailable
        }

        LocalConstraintCode.MOBILE_DATA_SUSPENDED -> {
            R.string.diagnostics_local_hint_mobile_data_suspended
        }

        LocalConstraintCode.INCOMPLETE_EVIDENCE -> {
            R.string.diagnostics_local_hint_incomplete_evidence
        }
    }
