package com.poyka.ripdpi.diagnostics

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.DeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.DeviceRuntimeForegroundServiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AndroidDeviceStateProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val runtimeEvidenceStore: DeviceRuntimeEvidenceStore,
    ) : DeviceStateProvider {
        private val powerManager = context.getSystemService(PowerManager::class.java)
        private val activityManager = context.getSystemService(ActivityManager::class.java)
        private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        private val notificationManager = context.getSystemService(NotificationManager::class.java)
        private val userManager = context.getSystemService(UserManager::class.java)

        override fun capture(): DeviceStateSnapshot {
            val battery =
                runCatching {
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }.getOrNull()
            val processMemoryState = captureProcessMemoryState()
            val foregroundNotificationActive = foregroundNotificationActive()
            val foregroundServiceState = runtimeEvidenceStore.foregroundServiceState.value
            return buildDeviceStateSnapshot(
                apiLevel = Build.VERSION.SDK_INT,
                screenInteractive = runCatching { powerManager?.isInteractive }.getOrNull(),
                deviceIdle = runCatching { powerManager?.isDeviceIdleMode }.getOrNull(),
                powerSaver = runCatching { powerManager?.isPowerSaveMode }.getOrNull(),
                backgroundRestricted =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        runCatching { activityManager?.isBackgroundRestricted }.getOrNull()
                    } else {
                        null
                    },
                batteryOptimizationExempt =
                    runCatching {
                        powerManager?.isIgnoringBatteryOptimizations(context.packageName)
                    }.getOrNull(),
                lowPowerStandby =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching { powerManager?.isLowPowerStandbyEnabled }.getOrNull()
                    } else {
                        null
                    },
                lowPowerStandbyExempt =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        runCatching { powerManager?.isExemptFromLowPowerStandby }.getOrNull()
                    } else {
                        null
                    },
                thermalStatus =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { powerManager?.currentThermalStatus }.getOrNull()
                    } else {
                        null
                    },
                batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 },
                batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 },
                charging = battery?.batteryChargingState(),
                standbyBucket =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        runCatching { usageStatsManager?.appStandbyBucket }.getOrNull()
                    } else {
                        null
                    },
                notificationPermissionGranted = notificationPermissionGranted(),
                notificationsAllowed =
                    runCatching {
                        NotificationManagerCompat.from(context).areNotificationsEnabled()
                    }.getOrNull(),
                notificationsPaused =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { notificationManager?.areNotificationsPaused() }.getOrNull()
                    } else {
                        null
                    },
                foregroundNotificationActive = foregroundNotificationActive,
                foregroundNotificationChannelState = foregroundNotificationChannelState(),
                foregroundServiceType =
                    foregroundServiceState.serviceType.takeIf { foregroundServiceState.active },
                userUnlocked = runCatching { userManager?.isUserUnlocked }.getOrNull(),
                processImportance = processMemoryState.importance,
                lastTrimLevel = processMemoryState.lastTrimLevel,
                manufacturer = Build.MANUFACTURER,
            )
        }

        override fun observeChanges(onChanged: () -> Unit): DeviceStateObservation {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        onChanged()
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    addAction(PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED)
                    addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                }
            val receiverRegistered =
                runCatching {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    true
                }.getOrDefault(false)

            val thermalListener =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                    PowerManager.OnThermalStatusChangedListener { onChanged() }
                } else {
                    null
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
                runCatching {
                    powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermalListener)
                }
            }

            val closed = AtomicBoolean(false)
            return DeviceStateObservation {
                if (!closed.compareAndSet(false, true)) return@DeviceStateObservation
                if (receiverRegistered) {
                    runCatching { context.unregisterReceiver(receiver) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
                    runCatching { powerManager?.removeThermalStatusListener(thermalListener) }
                }
            }
        }

        private fun notificationPermissionGranted(): Boolean? =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                null
            } else {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }

        private fun foregroundNotificationActive(): Boolean? =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                null
            } else {
                runCatching {
                    notificationManager
                        ?.activeNotifications
                        ?.any { status -> status.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
                }.getOrNull()
            }

        private fun foregroundNotificationChannelState(): NotificationChannelState =
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> NotificationChannelState.NotSupported
                else -> notificationChannelStateOnSupportedApi()
            }

        private fun notificationChannelStateOnSupportedApi(): NotificationChannelState {
            val channels =
                listOf(VpnNotificationChannelId, ProxyNotificationChannelId)
                    .mapNotNull { channelId ->
                        runCatching { notificationManager?.getNotificationChannel(channelId) }.getOrNull()
                    }
            val enabledCount = channels.count { it.importance != NotificationManager.IMPORTANCE_NONE }
            return when {
                channels.isEmpty() -> NotificationChannelState.Missing
                enabledCount == channels.size -> NotificationChannelState.Enabled
                enabledCount == 0 -> NotificationChannelState.Blocked
                else -> NotificationChannelState.Partial
            }
        }
    }

internal data class ProcessMemoryState(
    val importance: Int?,
    val lastTrimLevel: Int?,
)

internal fun captureProcessMemoryState(
    readMemoryState: (ActivityManager.RunningAppProcessInfo) -> Unit = ActivityManager::getMyMemoryState,
): ProcessMemoryState {
    val processInfo = ActivityManager.RunningAppProcessInfo()
    return runCatching {
        readMemoryState(processInfo)
        ProcessMemoryState(
            importance = processInfo.importance,
            lastTrimLevel = processInfo.lastTrimLevel,
        )
    }.getOrElse {
        ProcessMemoryState(
            importance = null,
            lastTrimLevel = null,
        )
    }
}

@Suppress("LongParameterList")
internal fun buildDeviceStateSnapshot(
    apiLevel: Int,
    screenInteractive: Boolean?,
    deviceIdle: Boolean?,
    powerSaver: Boolean?,
    backgroundRestricted: Boolean?,
    batteryOptimizationExempt: Boolean?,
    lowPowerStandby: Boolean?,
    lowPowerStandbyExempt: Boolean?,
    thermalStatus: Int?,
    batteryLevel: Int?,
    batteryScale: Int?,
    charging: Boolean?,
    standbyBucket: Int?,
    notificationPermissionGranted: Boolean?,
    notificationsAllowed: Boolean?,
    notificationsPaused: Boolean?,
    foregroundNotificationActive: Boolean?,
    foregroundNotificationChannelState: NotificationChannelState,
    foregroundServiceType: DeviceRuntimeForegroundServiceType?,
    userUnlocked: Boolean?,
    processImportance: Int?,
    lastTrimLevel: Int?,
    manufacturer: String?,
): DeviceStateSnapshot =
    DeviceStateSnapshot(
        screenInteractive = screenInteractive.toDeviceStateValue(),
        deviceIdle = deviceIdle.toDeviceStateValue(minApi = Build.VERSION_CODES.M, apiLevel = apiLevel),
        powerSaver = powerSaver.toDeviceStateValue(),
        backgroundRestricted =
            backgroundRestricted.toDeviceStateValue(minApi = Build.VERSION_CODES.P, apiLevel = apiLevel),
        batteryOptimizationExempt =
            batteryOptimizationExempt.toDeviceStateValue(minApi = Build.VERSION_CODES.M, apiLevel = apiLevel),
        lowPowerStandby =
            lowPowerStandby.toDeviceStateValue(minApi = Build.VERSION_CODES.TIRAMISU, apiLevel = apiLevel),
        lowPowerStandbyExempt =
            lowPowerStandbyExempt.toDeviceStateValue(
                minApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                apiLevel = apiLevel,
            ),
        batteryLevel = batteryBand(batteryLevel, batteryScale),
        charging = charging.toDeviceStateValue(),
        standbyBucket = standbyBucket.toStandbyBucket(apiLevel),
        notificationPermission =
            if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
                DeviceStateValue.NotRequired
            } else {
                notificationPermissionGranted.toDeviceStateValue()
            },
        notificationsAllowed = notificationsAllowed.toDeviceStateValue(),
        notificationsPaused =
            notificationsPaused.toDeviceStateValue(minApi = Build.VERSION_CODES.Q, apiLevel = apiLevel),
        foregroundNotificationActive =
            foregroundNotificationActive.toDeviceStateValue(minApi = Build.VERSION_CODES.M, apiLevel = apiLevel),
        foregroundNotificationChannels = foregroundNotificationChannelState,
        foregroundServiceType = foregroundServiceType.toForegroundServiceTypeBand(apiLevel),
        userUnlocked = userUnlocked.toDeviceStateValue(minApi = Build.VERSION_CODES.N, apiLevel = apiLevel),
        processImportance = processImportance.toProcessImportanceBand(),
        memoryPressure = lastTrimLevel.toMemoryPressureBand(),
        thermalStatus = thermalStatus.toThermalBand(apiLevel),
        manufacturerFamily = manufacturer.toManufacturerFamily(),
    )

private fun DeviceRuntimeForegroundServiceType?.toForegroundServiceTypeBand(apiLevel: Int): ForegroundServiceTypeBand =
    when {
        apiLevel < Build.VERSION_CODES.Q -> ForegroundServiceTypeBand.NotSupported
        this == null -> ForegroundServiceTypeBand.None
        this == DeviceRuntimeForegroundServiceType.SpecialUse -> ForegroundServiceTypeBand.SpecialUse
        else -> ForegroundServiceTypeBand.Unknown
    }

private fun Int?.toProcessImportanceBand(): ProcessImportanceBand =
    when {
        this == null -> {
            ProcessImportanceBand.Unknown
        }

        this == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> {
            ProcessImportanceBand.Gone
        }

        this <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> {
            ProcessImportanceBand.Foreground
        }

        this <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> {
            ProcessImportanceBand.ForegroundService
        }

        this <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> {
            ProcessImportanceBand.Visible
        }

        this <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> {
            ProcessImportanceBand.Service
        }

        this >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> {
            ProcessImportanceBand.Cached
        }

        else -> {
            ProcessImportanceBand.Background
        }
    }

private fun Intent.batteryChargingState(): Boolean? =
    when (getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
        BatteryManager.BATTERY_STATUS_CHARGING,
        BatteryManager.BATTERY_STATUS_FULL,
        -> true

        BatteryManager.BATTERY_STATUS_DISCHARGING,
        BatteryManager.BATTERY_STATUS_NOT_CHARGING,
        -> false

        else -> null
    }

private fun batteryBand(
    level: Int?,
    scale: Int?,
): DeviceBatteryBand {
    if (level == null || scale == null || scale <= 0) return DeviceBatteryBand.Unknown
    val percent = (level * PercentScale / scale).coerceIn(MinBatteryPercent, MaxBatteryPercent)
    return when (percent) {
        MinBatteryPercent -> DeviceBatteryBand.Empty
        in 1..CriticalBatteryPercent -> DeviceBatteryBand.Critical
        in (CriticalBatteryPercent + 1)..LowBatteryPercent -> DeviceBatteryBand.Low
        in (LowBatteryPercent + 1)..MediumBatteryPercent -> DeviceBatteryBand.Medium
        in (MediumBatteryPercent + 1) until MaxBatteryPercent -> DeviceBatteryBand.High
        else -> DeviceBatteryBand.Full
    }
}

private fun Int?.toStandbyBucket(apiLevel: Int): DeviceStandbyBucket =
    when {
        apiLevel < Build.VERSION_CODES.P -> DeviceStandbyBucket.NotSupported
        this == UsageStatsManager.STANDBY_BUCKET_ACTIVE -> DeviceStandbyBucket.Active
        this == UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> DeviceStandbyBucket.WorkingSet
        this == UsageStatsManager.STANDBY_BUCKET_FREQUENT -> DeviceStandbyBucket.Frequent
        this == UsageStatsManager.STANDBY_BUCKET_RARE -> DeviceStandbyBucket.Rare
        this == UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> DeviceStandbyBucket.Restricted
        else -> DeviceStandbyBucket.Unknown
    }

@Suppress("DEPRECATION")
private fun Int?.toMemoryPressureBand(): MemoryPressureBand =
    when (this) {
        null -> MemoryPressureBand.Unknown

        0 -> MemoryPressureBand.None

        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressureBand.UiHidden

        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressureBand.Background

        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        -> MemoryPressureBand.Moderate

        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        -> MemoryPressureBand.Critical

        else -> MemoryPressureBand.Unknown
    }

private fun Boolean?.toDeviceStateValue(
    minApi: Int = Int.MIN_VALUE,
    apiLevel: Int = Int.MAX_VALUE,
): DeviceStateValue =
    when {
        apiLevel < minApi -> DeviceStateValue.NotSupported
        this == true -> DeviceStateValue.Enabled
        this == false -> DeviceStateValue.Disabled
        else -> DeviceStateValue.Unknown
    }

private fun Int?.toThermalBand(apiLevel: Int): DeviceThermalBand =
    when {
        apiLevel < Build.VERSION_CODES.Q -> DeviceThermalBand.NotSupported
        this == null -> DeviceThermalBand.Unknown
        this == PowerManager.THERMAL_STATUS_NONE -> DeviceThermalBand.None
        this == PowerManager.THERMAL_STATUS_LIGHT -> DeviceThermalBand.Light
        this == PowerManager.THERMAL_STATUS_MODERATE -> DeviceThermalBand.Moderate
        this == PowerManager.THERMAL_STATUS_SEVERE -> DeviceThermalBand.Severe
        this == PowerManager.THERMAL_STATUS_CRITICAL -> DeviceThermalBand.Critical
        this == PowerManager.THERMAL_STATUS_EMERGENCY -> DeviceThermalBand.Emergency
        this == PowerManager.THERMAL_STATUS_SHUTDOWN -> DeviceThermalBand.Shutdown
        else -> DeviceThermalBand.Unknown
    }

private fun String?.toManufacturerFamily(): DeviceManufacturerFamily =
    when (this?.trim()?.lowercase(Locale.US)) {
        null, "" -> DeviceManufacturerFamily.Unknown
        else -> DeviceManufacturerFamily.Other
    }

private const val VpnNotificationChannelId = "RIPDPIVpn"
private const val ProxyNotificationChannelId = "RIPDPI Proxy"
private const val PercentScale = 100
private const val MinBatteryPercent = 0
private const val CriticalBatteryPercent = 10
private const val LowBatteryPercent = 20
private const val MediumBatteryPercent = 60
private const val MaxBatteryPercent = 100
