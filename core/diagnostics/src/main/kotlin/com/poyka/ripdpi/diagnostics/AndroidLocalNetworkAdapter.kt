package com.poyka.ripdpi.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

internal class AndroidLocalNetworkAdapter(
    private val context: Context,
) : AndroidLocalNetworkPlatform {
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)

    @SuppressLint("MissingPermission")
    override fun transport(): LocalTransport =
        try {
            val manager = connectivity
            val active = manager?.activeNetwork
            val capabilities = active?.let { manager.getNetworkCapabilities(it) }
            when {
                manager == null -> LocalTransport.UNKNOWN
                active == null -> LocalTransport.NONE
                capabilities == null -> LocalTransport.UNKNOWN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> LocalTransport.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LocalTransport.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LocalTransport.CELLULAR
                else -> LocalTransport.OTHER
            }
        } catch (_: RuntimeException) {
            LocalTransport.UNKNOWN
        }

    @SuppressLint("MissingPermission")
    override fun device(): LocalDeviceConstraints =
        LocalDeviceConstraints(
            airplaneMode =
                localObservation {
                    when (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1)) {
                        0 -> LocalObservationState.DISABLED
                        1 -> LocalObservationState.ENABLED
                        else -> LocalObservationState.UNKNOWN
                    }
                },
            dataSaver =
                localObservation {
                    when (connectivity?.restrictBackgroundStatus) {
                        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> LocalDataSaverState.DISABLED
                        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> LocalDataSaverState.ENABLED
                        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> LocalDataSaverState.EXEMPT
                        null -> LocalDataSaverState.UNAVAILABLE
                        else -> LocalDataSaverState.UNKNOWN
                    }
                },
            backgroundRestricted =
                localObservation {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        LocalObservationState.UNSUPPORTED
                    } else {
                        context
                            .getSystemService(ActivityManager::class.java)
                            ?.isBackgroundRestricted
                            ?.let(::localBoolean) ?: LocalObservationState.UNAVAILABLE
                    }
                },
            powerSaveMode =
                localObservation {
                    power?.isPowerSaveMode?.let(::localBoolean) ?: LocalObservationState.UNAVAILABLE
                },
            batteryOptimizationExempt =
                localObservation {
                    power?.isIgnoringBatteryOptimizations(context.packageName)?.let(::localBoolean)
                        ?: LocalObservationState.UNAVAILABLE
                },
            deviceIdle =
                localObservation {
                    power?.isDeviceIdleMode?.let(::localBoolean) ?: LocalObservationState.UNAVAILABLE
                },
            metered =
                localObservation {
                    val manager = connectivity
                    when {
                        manager == null -> LocalObservationState.UNAVAILABLE
                        manager.activeNetwork == null -> LocalObservationState.UNKNOWN
                        else -> localBoolean(manager.isActiveNetworkMetered)
                    }
                },
        )

    override fun defaultDataSubscriptionId(): Int {
        checkTelephony()
        return SubscriptionManager.getDefaultDataSubscriptionId()
    }

    override fun sim(subscriptionId: Int): LocalSimConstraints =
        try {
            checkTelephony()
            val manager =
                context
                    .getSystemService(
                        TelephonyManager::class.java,
                    )?.createForSubscriptionId(subscriptionId)
            if (manager ==
                null
            ) {
                emptyLocalSim(LocalSimScope.UNAVAILABLE)
            } else {
                AndroidLocalSimAdapter(context, manager).capture(subscriptionId)
            }
        } catch (_: SecurityException) {
            emptyLocalSim(LocalSimScope.PERMISSION_DENIED)
        } catch (_: UnsupportedOperationException) {
            emptyLocalSim(LocalSimScope.UNSUPPORTED)
        } catch (_: RuntimeException) {
            emptyLocalSim(LocalSimScope.UNAVAILABLE)
        }

    private fun checkTelephony() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            throw UnsupportedOperationException()
        }
    }
}
