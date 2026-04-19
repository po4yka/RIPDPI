package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

interface ServiceController {
    fun start(mode: Mode)

    fun stop()
}

@Singleton
class DefaultServiceController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serviceStateStore: ServiceStateStore,
        private val serviceAutomationController: Optional<ServiceAutomationController>,
    ) : ServiceController {
        @Suppress("ReturnCount")
        override fun start(mode: Mode) {
            if (serviceAutomationController.map { it.interceptStart(mode) }.orElse(false)) {
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Logger.i {
                    "Cannot start service: POST_NOTIFICATIONS permission not granted"
                }
                return
            }
            if (mode == Mode.VPN && VpnService.prepare(context) != null) {
                Logger.i {
                    "Cannot start VPN service: VPN consent not given"
                }
                return
            }
            when (mode) {
                Mode.VPN -> {
                    Logger.i { "Starting VPN" }
                    val intent =
                        Intent(context, RipDpiVpnService::class.java).apply {
                            action = startAction
                        }
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
                        Logger.w(e) { "Foreground service start blocked" }
                        return
                    }
                }

                Mode.Proxy -> {
                    Logger.i { "Starting proxy" }
                    val intent =
                        Intent(context, RipDpiProxyService::class.java).apply {
                            action = startAction
                        }
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
                        Logger.w(e) { "Foreground service start blocked" }
                        return
                    }
                }
            }
        }

        override fun stop() {
            val currentMode = serviceStateStore.status.value.second
            if (serviceAutomationController.map { it.interceptStop(currentMode) }.orElse(false)) {
                return
            }
            val intent =
                when (currentMode) {
                    Mode.VPN -> {
                        Logger.i { "Stopping VPN" }
                        Intent(context, RipDpiVpnService::class.java).apply {
                            action = stopAction
                        }
                    }

                    Mode.Proxy -> {
                        Logger.i { "Stopping proxy" }
                        Intent(context, RipDpiProxyService::class.java).apply {
                            action = stopAction
                        }
                    }
                }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
                Logger.w(e) { "Foreground service start blocked" }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceControllerModule {
    @Binds
    @Singleton
    abstract fun bindServiceController(serviceController: DefaultServiceController): ServiceController
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsRuntimeCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsRuntimeCoordinator(
        coordinator: DefaultDiagnosticsRuntimeCoordinator,
    ): DiagnosticsRuntimeCoordinator
}
