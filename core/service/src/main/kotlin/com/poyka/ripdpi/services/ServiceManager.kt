package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
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
    fun start(mode: Mode): ServiceStartResult

    fun stop()

    /** Internal diagnostics resume that must not replace explicit user intent. */
    fun startForDiagnostics(mode: Mode): ServiceStartResult = start(mode)

    /** Internal diagnostics pause that must not replace explicit user intent. */
    fun stopForDiagnostics() = stop()

    /** Reconcile a user Stop after a diagnostics resume raced it. */
    fun stopForDiagnosticsCompensation() = stopForDiagnostics()
}

sealed interface ServiceStartResult {
    val mode: Mode

    data class Accepted(
        override val mode: Mode,
    ) : ServiceStartResult

    data class Rejected(
        override val mode: Mode,
        val reason: ServiceStartRejectionReason,
    ) : ServiceStartResult
}

sealed interface ServiceStartRejectionReason {
    data object NotificationsPermissionMissing : ServiceStartRejectionReason

    data object VpnConsentMissing : ServiceStartRejectionReason

    data class ForegroundServiceBlocked(
        val message: String?,
    ) : ServiceStartRejectionReason
}

interface ForegroundServiceStarter {
    fun startForegroundService(
        context: Context,
        intent: Intent,
    )
}

@Singleton
class ContextCompatForegroundServiceStarter
    @Inject
    constructor() : ForegroundServiceStarter {
        override fun startForegroundService(
            context: Context,
            intent: Intent,
        ) {
            ContextCompat.startForegroundService(context, intent)
        }
    }

@Singleton
class DefaultServiceController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serviceStateStore: ServiceStateStore,
        private val serviceAutomationController: Optional<ServiceAutomationController>,
        private val foregroundServiceStarter: ForegroundServiceStarter,
        private val bootSessionStateStore: BootSessionStateStore,
        private val runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
    ) : ServiceController {
        internal constructor(
            context: Context,
            serviceStateStore: ServiceStateStore,
            serviceAutomationController: Optional<ServiceAutomationController>,
            foregroundServiceStarter: ForegroundServiceStarter,
            bootSessionStateStore: BootSessionStateStore,
        ) : this(
            context = context,
            serviceStateStore = serviceStateStore,
            serviceAutomationController = serviceAutomationController,
            foregroundServiceStarter = foregroundServiceStarter,
            bootSessionStateStore = bootSessionStateStore,
            runtimeResumeIntentTracker = RuntimeResumeIntentTracker(),
        )

        @Suppress("ReturnCount")
        override fun start(mode: Mode): ServiceStartResult =
            runtimeResumeIntentTracker.withUserStart(
                action = { startInternal(mode, startAction) },
                isAccepted = { it is ServiceStartResult.Accepted },
            )

        override fun startForDiagnostics(mode: Mode): ServiceStartResult = startInternal(mode, diagnosticsStartAction)

        @Suppress("ReturnCount")
        private fun startInternal(
            mode: Mode,
            action: String,
        ): ServiceStartResult {
            if (serviceAutomationController.map { it.interceptStart(mode) }.orElse(false)) {
                return ServiceStartResult.Accepted(mode)
            }
            if (mode == Mode.VPN && VpnService.prepare(context) != null) {
                Logger.i {
                    "Cannot start VPN service: VPN consent not given"
                }
                return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.VpnConsentMissing)
            }
            when (mode) {
                Mode.VPN -> {
                    Logger.i { "Starting VPN" }
                    val intent =
                        Intent(context, RipDpiVpnService::class.java).apply {
                            this.action = action
                        }
                    try {
                        foregroundServiceStarter.startForegroundService(context, intent)
                    } catch (e: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
                        Logger.w(e) { "Foreground service start blocked" }
                        return ServiceStartResult.Rejected(
                            mode = mode,
                            reason = ServiceStartRejectionReason.ForegroundServiceBlocked(e.message),
                        )
                    }
                }

                Mode.Proxy -> {
                    Logger.i { "Starting proxy" }
                    val intent =
                        Intent(context, RipDpiProxyService::class.java).apply {
                            this.action = action
                        }
                    try {
                        foregroundServiceStarter.startForegroundService(context, intent)
                    } catch (e: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
                        Logger.w(e) { "Foreground service start blocked" }
                        return ServiceStartResult.Rejected(
                            mode = mode,
                            reason = ServiceStartRejectionReason.ForegroundServiceBlocked(e.message),
                        )
                    }
                }
            }
            return ServiceStartResult.Accepted(mode)
        }

        override fun stop() {
            stopInternal(action = stopAction, clearUpdateResumeMarker = true)
        }

        override fun stopForDiagnostics() {
            stopInternal(action = diagnosticsStopAction, clearUpdateResumeMarker = false)
        }

        override fun stopForDiagnosticsCompensation() {
            stopInternal(action = diagnosticsCompensatingStopAction, clearUpdateResumeMarker = false)
        }

        private fun stopInternal(
            action: String,
            clearUpdateResumeMarker: Boolean,
        ) {
            // Explicit (user / automation) stop through the controller: clear the
            // "was running at update" flag so a later MY_PACKAGE_REPLACED does NOT
            // resurrect a deliberately-stopped tunnel. A process kill (LMK / update)
            // never reaches here, so the flag stays set in that case — exactly the
            // signal the boot resume worker needs.
            if (clearUpdateResumeMarker) {
                bootSessionStateStore.setWasRunningAtUpdate(false)
            }
            val currentMode = serviceStateStore.status.value.second
            if (serviceAutomationController.map { it.interceptStop(currentMode) }.orElse(false)) {
                if (action == stopAction) {
                    runtimeResumeIntentTracker.recordAcceptedStop()
                }
                return
            }
            val intent =
                when (currentMode) {
                    Mode.VPN -> {
                        Logger.i { "Stopping VPN" }
                        Intent(context, RipDpiVpnService::class.java).apply {
                            this.action = action
                        }
                    }

                    Mode.Proxy -> {
                        Logger.i { "Stopping proxy" }
                        Intent(context, RipDpiProxyService::class.java).apply {
                            this.action = action
                        }
                    }
                }
            try {
                foregroundServiceStarter.startForegroundService(context, intent)
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
abstract class ForegroundServiceStarterModule {
    @Binds
    @Singleton
    abstract fun bindForegroundServiceStarter(starter: ContextCompatForegroundServiceStarter): ForegroundServiceStarter
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiagnosticsRuntimeCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsRuntimeCoordinator(
        coordinator: DefaultDiagnosticsRuntimeCoordinator,
    ): DiagnosticsRuntimeCoordinator
}
