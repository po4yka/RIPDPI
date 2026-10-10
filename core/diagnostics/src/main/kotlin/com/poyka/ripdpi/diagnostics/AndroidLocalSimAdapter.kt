package com.poyka.ripdpi.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

internal class AndroidLocalSimAdapter(
    private val context: Context,
    private val manager: TelephonyManager,
) {
    @SuppressLint("MissingPermission")
    fun capture(subscriptionId: Int): LocalSimConstraints =
        LocalSimConstraints(
            scope = LocalSimScope.DEFAULT_DATA,
            activeDataMatchesDefault =
                localObservation {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        LocalObservationState.UNSUPPORTED
                    } else {
                        val active = SubscriptionManager.getActiveDataSubscriptionId()
                        if (SubscriptionManager.isValidSubscriptionId(active)) {
                            localBoolean(active == subscriptionId)
                        } else {
                            LocalObservationState.UNKNOWN
                        }
                    }
                },
            simState = localObservation { simState(manager.simState) },
            mobileDataEnabled = localObservation { localBoolean(manager.isDataEnabled) },
            dataConnectionAllowed =
                localObservation {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        localBoolean(manager.isDataConnectionAllowed)
                    } else {
                        LocalObservationState.UNSUPPORTED
                    }
                },
            roaming = localObservation { localBoolean(manager.isNetworkRoaming) },
            roamingEnabled =
                localObservation {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        localBoolean(manager.isDataRoamingEnabled)
                    } else {
                        LocalObservationState.UNSUPPORTED
                    }
                },
            voiceServiceState = localObservation { serviceState() },
            dataState = localObservation { dataState(manager.dataState) },
        )

    @SuppressLint("MissingPermission")
    private fun serviceState(): LocalServiceState {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE) ||
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return LocalServiceState.PERMISSION_DENIED
        }
        val state =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_NONE)
            } else {
                manager.serviceState
            }
        return when (state?.state) {
            ServiceState.STATE_IN_SERVICE -> LocalServiceState.IN_SERVICE
            ServiceState.STATE_OUT_OF_SERVICE -> LocalServiceState.OUT_OF_SERVICE
            ServiceState.STATE_EMERGENCY_ONLY -> LocalServiceState.EMERGENCY_ONLY
            ServiceState.STATE_POWER_OFF -> LocalServiceState.POWER_OFF
            null -> LocalServiceState.UNAVAILABLE
            else -> LocalServiceState.UNKNOWN
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun simState(state: Int): LocalSimState =
    when (state) {
        TelephonyManager.SIM_STATE_READY -> LocalSimState.READY
        TelephonyManager.SIM_STATE_ABSENT -> LocalSimState.ABSENT
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> LocalSimState.PIN_REQUIRED
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> LocalSimState.PUK_REQUIRED
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> LocalSimState.NETWORK_LOCKED
        TelephonyManager.SIM_STATE_NOT_READY -> LocalSimState.NOT_READY
        TelephonyManager.SIM_STATE_PERM_DISABLED -> LocalSimState.PERMANENTLY_DISABLED
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> LocalSimState.CARD_IO_ERROR
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> LocalSimState.CARD_RESTRICTED
        else -> LocalSimState.UNKNOWN
    }

private fun dataState(state: Int): LocalDataConnectionState =
    when (state) {
        TelephonyManager.DATA_CONNECTED -> LocalDataConnectionState.CONNECTED
        TelephonyManager.DATA_CONNECTING -> LocalDataConnectionState.CONNECTING
        TelephonyManager.DATA_DISCONNECTED -> LocalDataConnectionState.DISCONNECTED
        TelephonyManager.DATA_SUSPENDED -> LocalDataConnectionState.SUSPENDED
        TelephonyManager.DATA_DISCONNECTING -> LocalDataConnectionState.DISCONNECTING
        TelephonyManager.DATA_HANDOVER_IN_PROGRESS -> LocalDataConnectionState.HANDOVER
        else -> LocalDataConnectionState.UNKNOWN
    }
