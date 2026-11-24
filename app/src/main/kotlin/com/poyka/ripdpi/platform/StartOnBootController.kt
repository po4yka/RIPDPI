package com.poyka.ripdpi.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.poyka.ripdpi.services.BootReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the "Start on boot" preference: toggles the [BootReceiver] component and
 * surfaces the battery-optimization / vendor-autostart exemption flow.
 *
 * The vendor autostart mapping is factored into the pure top-level
 * [vendorAutoStartComponents] so it can be unit-tested without a [Context].
 */
@Singleton
open class StartOnBootController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        open fun setReceiverEnabled(enabled: Boolean) {
            BootReceiver.setEnabled(context, enabled)
        }

        open fun isBatteryOptimizationIgnored(): Boolean {
            val powerManager = context.getSystemService(PowerManager::class.java)
            return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        }

        fun vendorAutostartIntents(): List<Intent> =
            vendorAutoStartComponents(Build.MANUFACTURER).map { component ->
                Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun batteryOptimizationIntents(): List<Intent> =
            listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}")),
            ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

/**
 * Maps a device [manufacturer] to the ordered list of known vendor autostart-management
 * activities. Pure and Context-free for unit testing. Unknown manufacturers return an
 * empty list; matching is case-insensitive.
 */
internal fun vendorAutoStartComponents(manufacturer: String): List<ComponentName> =
    when (manufacturer.lowercase(Locale.ROOT)) {
        "xiaomi" -> {
            listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )
        }

        "huawei" -> {
            listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            )
        }

        "oppo" -> {
            listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            )
        }

        "vivo" -> {
            listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            )
        }

        else -> {
            emptyList()
        }
    }
