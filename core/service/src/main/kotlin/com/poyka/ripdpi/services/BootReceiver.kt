package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import co.touchlab.kermit.Logger

/**
 * Resumes the previously-active RIPDPI session after the device boots or the app
 * is updated. Handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` (direct boot, so
 * `android:directBootAware="true"` in the manifest), and `MY_PACKAGE_REPLACED`.
 *
 * Declared `android:enabled="false"` and toggled on only while the user's
 * "Start on boot" preference is enabled — see [setEnabled]. `onReceive` does no
 * real work; it offloads to [BootResumeWorker], which reads the device-protected
 * session pointer and asks the [ServiceController] to start the recorded mode.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Logger.withTag("BootReceiver").i { "received $action; enqueueing resume" }
                BootResumeWorker.enqueue(context.applicationContext, action)
            }

            else -> {
                Unit
            }
        }
    }

    companion object {
        /**
         * Enables or disables the [BootReceiver] component at runtime, wired to the
         * "Start on boot" Settings toggle. `DONT_KILL_APP` keeps the process alive
         * while the component state is flipped. Disabling removes the broadcast
         * filter entirely so the receiver does not keep the package warm when unused.
         */
        fun setEnabled(
            context: Context,
            enabled: Boolean,
        ) {
            val state =
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootReceiver::class.java),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
