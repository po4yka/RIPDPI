package com.poyka.ripdpi.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
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
import com.poyka.ripdpi.services.selector.SelectorRuntimeLifecycleListener
import com.poyka.ripdpi.utility.NotificationContentBuilder
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.createDynamicConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import dagger.hilt.EntryPoints
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider

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
    lateinit var rootHelperManagerProvider: Provider<RootHelperManager>
    private val rootHelperManager get() = rootHelperManagerProvider.get()

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

    @Inject
    lateinit var selectorRuntimeLifecycleListenersProvider:
        Provider<Set<@JvmSuppressWildcards SelectorRuntimeLifecycleListener>>
    private val selectorRuntimeLifecycleListeners get() = selectorRuntimeLifecycleListenersProvider.get()

    @Inject lateinit var profileRecovery: com.poyka.ripdpi.data.ProfileMutationRecoveryAccess

    @Inject lateinit var pauseController: TimedPauseController

    @Inject lateinit var pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority

    private val activeOwnership = ActiveSessionOwnership()
    private val activeSessionAttached get() = activeOwnership.hasOwnership

    @Volatile private var lastPausedStartId = 0

    @Volatile private var latestStartId = 0
    private val entryMutex = kotlinx.coroutines.sync.Mutex()
    private var destroyCleanupJob: kotlinx.coroutines.Job? = null

    private var sessionComponent: ProxyServiceSessionComponent? = null
    private var stateInitializer: ServiceSessionStateInitializer? = null
    private var sessionStateStore: ServiceStateStore? = null
    private var coordinator: ProxyServiceRuntimeCoordinator? = null
    private var shellDelegate: ServiceShellDelegate? = null

    override val serviceScope = lifecycleScope

    override fun onCreate() {
        super.onCreate()
        runtimeEvidenceReporter.recordLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.Created)
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
        pauseController.attach(pauseHost)
    }

    private suspend fun ensureActiveSession() {
        if (activeOwnership.attached) return
        runCatching {
            activeOwnership.attach {
                activeOwnership.own { stateInitializer?.close() }
                activeOwnership.own { coordinator?.onDestroy() }
                activeOwnership.own { shellDelegate?.close() }

                sessionComponent = sessionComponentBuilderProvider.get().host(this).build()
                val sessionEntryPoint =
                    EntryPoints.get(
                        checkNotNull(sessionComponent),
                        ProxyServiceSessionEntryPoint::class.java,
                    )
                runCatching {
                    val initializer = sessionEntryPoint.stateInitializer()
                    stateInitializer = initializer
                    val stateStore = initializer.initialize(Mode.Proxy)
                    val runtimeCoordinator = sessionEntryPoint.coordinator()
                    val ownedRootHelper = rootHelperManager
                    activeOwnership.own { ownedRootHelper.stopOnDestroy() }
                    sessionStateStore = stateStore
                    coordinator = runtimeCoordinator
                    shellDelegate =
                        ServiceShellDelegate(
                            serviceScope = lifecycleScope,
                            serviceIntentArbiter = serviceIntentArbiter,
                            serviceLabel = "proxy",
                            onStart = runtimeCoordinator::start,
                            onStartWithId = { _, startId -> runtimeCoordinator.start(stopSelfStartId = startId) },
                            onStop = { startId, provenance ->
                                serviceStopProvenanceRecorder.record(Mode.Proxy, provenance)
                                runtimeCoordinator.stop(startId)
                            },
                            intentCallbacks =
                                ServiceShellIntentCallbacks(
                                    acceptedStart = runtimeResumeIntentTracker::recordAcceptedStart,
                                    acceptedStop = acceptedUserStopRecorder::record,
                                ),
                            isCompensatingStopCurrent = runtimeResumeIntentTracker::isCurrentIntentStopped,
                        )
                }.getOrThrow()
                selectorRuntimeLifecycleListeners.forEach { listener ->
                    activeOwnership.own { listener.stop(Mode.Proxy) }
                    listener.start(Mode.Proxy)
                }
            }
        }.onFailure { failure ->
            if (failure !is Exception) throw failure
            if (activeOwnership.hasOwnership) {
                pauseController.retainPartialCleanup(
                    pauseHost,
                )
            } else {
                clearSessionReferences()
            }
        }.getOrThrow()
    }

    /** Called only under entryMutex; native and ancillary ownership must both be released. */
    private suspend fun releaseActiveSession(guard: RuntimeStopGuard): RuntimeStopOutcome =
        when {
            !guard.isCurrent() -> {
                RuntimeStopOutcome.Superseded
            }

            !activeSessionAttached -> {
                RuntimeStopOutcome.FullyReleased
            }

            !activeOwnership.attached -> {
                activeOwnership.release().also {
                    if (it ==
                        RuntimeStopOutcome.FullyReleased
                    ) {
                        clearSessionReferences()
                    }
                }
            }

            else -> {
                val result =
                    checkNotNull(coordinator).stop(
                        guard = guard,
                        disposition = RuntimeStopDisposition.RetainPausedShell,
                    )
                val outcome = if (result == RuntimeStopOutcome.FullyReleased) activeOwnership.release() else result
                if (outcome == RuntimeStopOutcome.FullyReleased) clearSessionReferences()
                outcome
            }
        }

    private val pauseHost by lazy {
        PausedServiceHost(
            identity = this,
            mode = Mode.Proxy,
            scope = serviceScope,
            release = { intent ->
                entryMutex.withLock {
                    releaseActiveSession(RuntimeStopGuard(isCurrent = { pauseAuthority.isCurrent(intent) }))
                }
            },
            resume = { intent ->
                entryMutex.withLock {
                    if (pauseAuthority.isCurrent(intent)) {
                        ensureActiveSession()
                        checkNotNull(coordinator).start()
                    }
                }
            },
            showPaused = { intent ->
                lastPausedStartId = latestStartId
                startPausedForeground(this, intent, NOTIFICATION_CHANNEL_ID, FOREGROUND_SERVICE_ID)
            },
            stopShell = { lease ->
                entryMutex.withLock {
                    val guard = RuntimeStopGuard(isCurrent = { serviceIntentArbiter.isCurrent(lease) })
                    val outcome = releaseActiveSession(guard)
                    if (outcome == RuntimeStopOutcome.FullyReleased && guard.isCurrent()) stopSelf()
                    if (guard.isCurrent()) outcome else RuntimeStopOutcome.Superseded
                }
            },
            discardIdleShell = { old ->
                entryMutex.withLock {
                    if (!activeSessionAttached && pauseAuthority.snapshot() == null) {
                        if (pauseAuthority.reference().generation != old.generation && lastPausedStartId > 0) {
                            stopSelfResult(lastPausedStartId)
                        }
                    }
                }
            },
        )
    }

    override fun onDestroy() {
        pauseController.detach(this)
        runtimeEvidenceReporter.recordLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.Destroyed)
        destroyCleanupJob =
            kotlinx.coroutines
                .CoroutineScope(
                    kotlinx.coroutines.Dispatchers.IO,
                ).launch(kotlinx.coroutines.NonCancellable) {
                    entryMutex.withLock {
                        val outcome = releaseActiveSession(RuntimeStopGuard(isCurrent = { true }))
                        if (outcome != RuntimeStopOutcome.FullyReleased) {
                            Logger.e { "Proxy destruction retained cleanup ownership" }
                        }
                    }
                }
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        latestStartId = startId
        startForegroundService()
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                entryMutex.withLock {
                    profileRecovery.recover()
                    if (!pauseController.handleShellStart(pauseHost, intent)) dispatchAfterRecovery(intent, startId)
                }
            }.onFailure { failure ->
                if (failure !is Exception || failure is kotlinx.coroutines.CancellationException) throw failure
                Logger.e { "Proxy recovery prerequisite failed: ${failure::class.java.simpleName}" }
                if (activeOwnership.hasOwnership) {
                    pauseController.retainPartialCleanup(pauseHost)
                } else {
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun dispatchAfterRecovery(
        intent: Intent?,
        startId: Int,
    ): Int {
        val action = intent?.action
        return when {
            intent.hasStaleServiceCommand(serviceIntentArbiter) -> {
                START_STICKY
            }

            action == TimedPauseController.PauseRestoreAction ||
                (isUserServiceStopAction(action) && !activeSessionAttached) -> {
                discardIdleStart(startId)
            }

            isServiceRecoveryStartAction(action) &&
                !pauseAuthority.allowsRecovery(
                    intent.durableAuthorityReference() ?: pauseAuthority.reference(),
                    Mode.Proxy,
                ) -> {
                discardIdleStart(startId)
            }

            else -> {
                dispatchActiveCommand(intent, startId)
            }
        }
    }

    private fun discardIdleStart(startId: Int): Int {
        if (!activeSessionAttached) stopSelf(startId)
        return if (activeSessionAttached) START_STICKY else START_NOT_STICKY
    }

    private suspend fun dispatchActiveCommand(
        intent: Intent?,
        startId: Int,
    ): Int {
        ensureActiveSession()
        runtimeEvidenceReporter.recordLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.StartCommand)
        runtimeEvidenceReporter.runForegroundCall(
            Mode.Proxy,
            DeviceRuntimeForegroundCallKind.Initial,
            DeviceRuntimeForegroundServiceType.SpecialUse,
        ) {
            startForegroundService()
        }
        // A null action is a START_STICKY re-delivery after a process kill (LMK /
        // memory limiter). Publish Reconnecting ONLY from a Halted baseline (a genuinely
        // fresh process): a null re-delivery to a still-Running service must not be
        // demoted to Reconnecting, since the follow-up start is rejected as
        // already-running and would never restore Running, leaving it stuck.
        // Running/Halted resolves it once the runtime settles.
        val stateStore = checkNotNull(sessionStateStore) { "Proxy service session has not been created" }
        if (intent?.action == null && stateStore.status.value.first == AppStatus.Halted) {
            stateStore.setStatus(AppStatus.Reconnecting, Mode.Proxy)
        }
        return checkNotNull(shellDelegate) { "Proxy service shell has not been created" }.onStartCommand(
            intent?.action,
            startId,
            explicitUserIntentGeneration = intent.explicitUserIntentGeneration(),
            durableReference = intent.durableAuthorityReference() ?: pauseAuthority.reference(),
        )
    }

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) {
        val startedAt = sessionStateStore?.telemetry?.value?.serviceStartedAt ?: return
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

    private fun clearSessionReferences() {
        shellDelegate = null
        coordinator = null
        sessionStateStore = null
        stateInitializer = null
        sessionComponent = null
    }

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPI Proxy"
    }
}
