package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.ServiceStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import logcat.LogPriority
import logcat.logcat
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
        override fun start(mode: Mode) {
            if (serviceAutomationController.map { it.interceptStart(mode) }.orElse(false)) {
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                logcat(LogPriority.WARN) {
                    "Cannot start service: POST_NOTIFICATIONS permission not granted"
                }
                return
            }
            if (mode == Mode.VPN && VpnService.prepare(context) != null) {
                logcat(LogPriority.WARN) {
                    "Cannot start VPN service: VPN consent not given"
                }
                return
            }
            when (mode) {
                Mode.VPN -> {
                    logcat(LogPriority.INFO) { "Starting VPN" }
                    val intent =
                        Intent(context, RipDpiVpnService::class.java).apply {
                            action = START_ACTION
                        }
                    ContextCompat.startForegroundService(context, intent)
                }

                Mode.Proxy -> {
                    logcat(LogPriority.INFO) { "Starting proxy" }
                    val intent =
                        Intent(context, RipDpiProxyService::class.java).apply {
                            action = START_ACTION
                        }
                    ContextCompat.startForegroundService(context, intent)
                }
            }
        }

        override fun stop() {
            val currentMode = serviceStateStore.status.value.second
            if (serviceAutomationController.map { it.interceptStop(currentMode) }.orElse(false)) {
                return
            }
            when (currentMode) {
                Mode.VPN -> {
                    logcat(LogPriority.INFO) { "Stopping VPN" }
                    val intent =
                        Intent(context, RipDpiVpnService::class.java).apply {
                            action = STOP_ACTION
                        }
                    ContextCompat.startForegroundService(context, intent)
                }

                Mode.Proxy -> {
                    logcat(LogPriority.INFO) { "Stopping proxy" }
                    val intent =
                        Intent(context, RipDpiProxyService::class.java).apply {
                            action = STOP_ACTION
                        }
                    ContextCompat.startForegroundService(context, intent)
                }
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
