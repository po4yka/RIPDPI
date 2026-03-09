package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import logcat.LogPriority
import logcat.logcat
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION

object ServiceManager {
    fun start(
        context: Context,
        mode: Mode,
    ) {
        when (mode) {
            Mode.VPN -> {
                logcat(LogPriority.INFO) { "Starting VPN" }
                val intent = Intent(context, RipDpiVpnService::class.java)
                intent.action = START_ACTION
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                logcat(LogPriority.INFO) { "Starting proxy" }
                val intent = Intent(context, RipDpiProxyService::class.java)
                intent.action = START_ACTION
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun stop(context: Context) {
        val (_, mode) = AppStateManager.status.value
        when (mode) {
            Mode.VPN -> {
                logcat(LogPriority.INFO) { "Stopping VPN" }
                val intent = Intent(context, RipDpiVpnService::class.java)
                intent.action = STOP_ACTION
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                logcat(LogPriority.INFO) { "Stopping proxy" }
                val intent = Intent(context, RipDpiProxyService::class.java)
                intent.action = STOP_ACTION
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
