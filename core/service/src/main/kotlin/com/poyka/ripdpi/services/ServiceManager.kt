package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
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
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

interface ServiceController {
    fun start(mode: Mode): ServiceStartResult

    /** Process-death recovery start; unlike [start], this is not a newer explicit user intent. */
    fun startForRecovery(mode: Mode): ServiceStartResult = start(mode)

    fun stop()

    /** Recompose the active VPN transport without accepting a user Stop or releasing the TUN barrier. */
    fun restartVpnForTransportFailover(): ServiceStartResult = start(Mode.VPN)

    /** Internal diagnostics resume that must not replace explicit user intent. */
    fun startForDiagnostics(mode: Mode): ServiceStartResult = start(mode)

    /** Internal diagnostics pause that must not replace explicit user intent. */
    fun stopForDiagnostics() = stop()

    /** Reconcile a user Stop after a diagnostics resume raced it. */
    fun stopForDiagnosticsCompensation() = stopForDiagnostics()

    /** Ask a running VPN service to refresh its cached Android lockdown state. */
    fun refreshHardKillSwitchState() = Unit
}

internal const val hardKillSwitchRefreshBroadcastAction =
    "com.poyka.ripdpi.action.REFRESH_HARD_KILL_SWITCH"

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

/** Serializes service start/stop intent decisions that must have a single winner. */
@Singleton
class ServiceIntentArbiter
    @Inject
    constructor() {
        private val lock = ReentrantLock()
        private var explicitUserIntentRecorded = false

        fun <T> serialize(block: () -> T): T = lock.withLock(block)

        fun <T> userStart(
            action: () -> T,
            isAccepted: (T) -> Boolean,
        ): T =
            lock.withLock {
                action().also { result ->
                    if (isAccepted(result)) explicitUserIntentRecorded = true
                }
            }

        fun userStop(action: () -> Unit) {
            lock.withLock {
                explicitUserIntentRecorded = true
                action()
            }
        }

        fun <T> recovery(action: () -> T): T? =
            lock.withLock {
                if (explicitUserIntentRecorded) null else action()
            }
    }

/** Records a service-side accepted user Stop before its asynchronous teardown. */
@Singleton
class AcceptedUserStopRecorder
    @Inject
    constructor(
        private val bootSessionStateStore: BootSessionStateStore,
        private val runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
        private val serviceIntentArbiter: ServiceIntentArbiter,
    ) {
        fun record() {
            serviceIntentArbiter.userStop {
                bootSessionStateStore.setWasRunningAtUpdate(false)
                runtimeResumeIntentTracker.recordAcceptedStop()
            }
        }
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
        private val serviceIntentArbiter: ServiceIntentArbiter,
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
            serviceIntentArbiter = ServiceIntentArbiter(),
        )

        @Suppress("ReturnCount")
        override fun start(mode: Mode): ServiceStartResult =
            serviceIntentArbiter.userStart(
                action = {
                    runtimeResumeIntentTracker.withUserStart(
                        action = { startInternal(mode, startAction) },
                        isAccepted = { it is ServiceStartResult.Accepted },
                    )
                },
                isAccepted = { it is ServiceStartResult.Accepted },
            )

        override fun startForRecovery(mode: Mode): ServiceStartResult = startInternal(mode, startAction)

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
            serviceIntentArbiter.userStop {
                stopInternal(action = stopAction)
            }
        }

        override fun restartVpnForTransportFailover(): ServiceStartResult =
            startInternal(Mode.VPN, transportFailoverRestartAction)

        override fun stopForDiagnostics() {
            stopInternal(action = diagnosticsStopAction)
        }

        override fun stopForDiagnosticsCompensation() {
            stopInternal(action = diagnosticsCompensatingStopAction)
        }

        override fun refreshHardKillSwitchState() {
            val (status, mode) = serviceStateStore.status.value
            if (status != AppStatus.Running || mode != Mode.VPN) {
                return
            }
            context.sendBroadcast(
                Intent(hardKillSwitchRefreshBroadcastAction).setPackage(context.packageName),
            )
        }

        private fun stopInternal(action: String) {
            val currentMode = serviceStateStore.status.value.second
            if (serviceAutomationController.map { it.interceptStop(currentMode) }.orElse(false)) {
                if (action == stopAction) {
                    // Automation consumed the user Stop, so no service callback will
                    // record acceptance. Commit the same durable intent here.
                    bootSessionStateStore.setWasRunningAtUpdate(false)
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
