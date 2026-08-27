package com.poyka.ripdpi.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DeviceRuntimeForegroundCallKind
import com.poyka.ripdpi.data.DeviceRuntimeForegroundServiceType
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.service.runtime.proxy.ProxyServiceRuntimeCoordinator
import com.poyka.ripdpi.utility.NotificationContentBuilder
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.createDynamicConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import dagger.hilt.EntryPoints
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider

internal enum class StickyRestartDecision { ABORT, PROCEED }

internal fun stickyRestartDecision(
    intentIsNull: Boolean,
    sdkAtLeastTiramisu: Boolean,
    notificationsGranted: Boolean,
): StickyRestartDecision =
    if (intentIsNull && sdkAtLeastTiramisu && !notificationsGranted) {
        StickyRestartDecision.ABORT
    } else {
        StickyRestartDecision.PROCEED
    }

/**
 * Proxy-mode foreground `Service` — the Android entry point for proxy mode.
 * Hosts the proxy session component; runtime orchestration is delegated to
 * `ProxyServiceRuntimeCoordinator`. Lifecycle-callback behavior is frozen —
 * see this module's `README.md`.
 */
@AndroidEntryPoint
class RipDpiProxyService :
    LifecycleService(),
    ServiceCoordinatorHost {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    lateinit var rootHelperManager: RootHelperManager

    @Inject
    internal lateinit var sessionComponentBuilderProvider: Provider<ProxyServiceSessionComponentBuilder>

    @Inject
    lateinit var runtimeResumeIntentTracker: RuntimeResumeIntentTracker

    @Inject
    lateinit var serviceIntentArbiter: ServiceIntentArbiter

    @Inject
    lateinit var acceptedUserStopRecorder: AcceptedUserStopRecorder

    @Inject
    lateinit var runtimeEvidenceReporter: AndroidRuntimeEvidenceReporter

    @Inject
    internal lateinit var serviceStopProvenanceRecorder: RoomServiceStopProvenanceRecorder

    private var sessionComponent: ProxyServiceSessionComponent? = null
    private lateinit var coordinator: ProxyServiceRuntimeCoordinator
    private lateinit var shellDelegate: ServiceShellDelegate

    override val serviceScope = lifecycleScope

    override fun onCreate() {
        super.onCreate()
        runtimeEvidenceReporter.recordLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.Created)
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
        sessionComponent = sessionComponentBuilderProvider.get().host(this).build()
        coordinator =
            EntryPoints
                .get(
                    checkNotNull(sessionComponent),
                    ProxyServiceSessionEntryPoint::class.java,
                ).coordinator()
        shellDelegate =
            ServiceShellDelegate(
                serviceScope = lifecycleScope,
                serviceIntentArbiter = serviceIntentArbiter,
                serviceLabel = "proxy",
                onStart = coordinator::start,
                onStartWithId = { _, startId -> coordinator.start(stopSelfStartId = startId) },
                onStop = { startId, provenance ->
                    serviceStopProvenanceRecorder.record(Mode.Proxy, provenance)
                    coordinator.stop(startId)
                },
                intentCallbacks =
                    ServiceShellIntentCallbacks(
                        acceptedStart = runtimeResumeIntentTracker::recordAcceptedStart,
                        acceptedStop = acceptedUserStopRecorder::record,
                    ),
                isCompensatingStopCurrent = runtimeResumeIntentTracker::isCurrentIntentStopped,
            )
    }

    override fun onDestroy() {
        runtimeEvidenceReporter.recordLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.Destroyed)
        coordinator.onDestroy()
        rootHelperManager.stop()
        sessionComponent = null
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        runtimeEvidenceReporter.recordLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.StartCommand)
        runtimeEvidenceReporter.runForegroundCall(
            Mode.Proxy,
            DeviceRuntimeForegroundCallKind.Initial,
            DeviceRuntimeForegroundServiceType.SpecialUse,
        ) {
            startForegroundService()
        }
        if (stickyRestartDecision(
                intentIsNull = intent == null,
                sdkAtLeastTiramisu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                notificationsGranted =
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
            ) == StickyRestartDecision.ABORT
        ) {
            Logger.w { "Sticky restart aborted: notification permission revoked" }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A null action is a START_STICKY re-delivery after a process kill (LMK /
        // memory limiter) — and we are past the abort guard, so the restart will
        // proceed. Publish Reconnecting ONLY from a Halted baseline (a genuinely
        // fresh process): a null re-delivery to a still-Running service must not be
        // demoted to Reconnecting, since the follow-up start is rejected as
        // already-running and would never restore Running, leaving it stuck.
        // Running/Halted resolves it once the runtime settles.
        if (intent?.action == null && serviceStateStore.status.value.first == AppStatus.Halted) {
            serviceStateStore.setStatus(AppStatus.Reconnecting, Mode.Proxy)
        }
        return shellDelegate.onStartCommand(
            intent?.action,
            startId,
            explicitUserIntentGeneration = intent.explicitUserIntentGeneration(),
        )
    }

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) {
        val startedAt = serviceStateStore.telemetry.value.serviceStartedAt ?: return
        val elapsedMs = System.currentTimeMillis() - startedAt
        val content =
            NotificationContentBuilder.buildContentText(
                txBytes = proxyTelemetry.tunnelStats.txBytes,
                rxBytes = proxyTelemetry.tunnelStats.rxBytes,
                elapsedMs = elapsedMs,
            )
        val subText =
            NotificationContentBuilder.buildSubText(
                activeSessions = proxyTelemetry.activeSessions,
                rttMs = proxyTelemetry.upstreamRttMs,
            )
        val notification =
            createDynamicConnectionNotification(
                context = this,
                channelId = NOTIFICATION_CHANNEL_ID,
                title = getString(R.string.notification_title),
                content = content,
                subText = subText,
                service = RipDpiProxyService::class.java,
                whenTimestamp = startedAt,
            )
        @Suppress("SwallowedException")
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(FOREGROUND_SERVICE_ID, notification)
        } catch (e: SecurityException) {
            Logger.w { "Cannot update notification: permission revoked" }
        }
    }

    override fun requestStopSelf(stopSelfStartId: Int?) {
        requestStopSelfWithFallback(
            stopSelfStartId = stopSelfStartId,
            stopSelfResult = ::stopSelfResult,
            stopSelf = ::stopSelf,
        )
    }

    private fun startForegroundService() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            RipDpiProxyService::class.java,
        )

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPI Proxy"
    }
}
