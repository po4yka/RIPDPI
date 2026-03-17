package com.poyka.ripdpi.services

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.deriveRuntimeFieldTelemetry
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.registerNotificationChannel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

@AndroidEntryPoint
class RipDpiProxyService : LifecycleService() {
    @Inject
    lateinit var connectionPolicyResolver: ConnectionPolicyResolver

    @Inject
    lateinit var ripDpiProxyFactory: RipDpiProxyFactory

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    lateinit var networkFingerprintProvider: NetworkFingerprintProvider

    @Inject
    lateinit var telemetryFingerprintHasher: TelemetryFingerprintHasher

    @Inject
    lateinit var serviceRuntimeRegistry: ServiceRuntimeRegistry

    @Inject
    lateinit var rememberedNetworkPolicyStore: RememberedNetworkPolicyStore

    @Inject
    lateinit var networkHandoverMonitor: NetworkHandoverMonitor

    @Inject
    lateinit var policyHandoverEventStore: PolicyHandoverEventStore

    private var proxy: RipDpiProxyRuntime? = null
    private var proxyJob: Job? = null
    private var telemetryJob: Job? = null
    private var handoverMonitorJob: Job? = null
    private var runtimeSession: ProxyRuntimeSession? = null
    private val mutex = Mutex()

    @Volatile
    private var stopping: Boolean = false

    private var status: ServiceStatus = ServiceStatus.Disconnected
    private val lifecycleState = ServiceLifecycleStateMachine()

    companion object {
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val NOTIFICATION_CHANNEL_ID: String = "RIPDPI Proxy"
        private const val HandoverCooldownMs: Long = 10_000L
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground()
        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop(stopSelfStartId = startId) }
                START_NOT_STICKY
            }

            else -> {
                logcat(LogPriority.WARN) { "Unknown action: $action" }
                lifecycleScope.launch { stop(stopSelfStartId = startId) }
                START_NOT_STICKY
            }
        }
    }

    private suspend fun start() {
        logcat(LogPriority.INFO) { "Starting" }

        var matchedRememberedPolicy: com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity? = null
        val session = ProxyRuntimeSession()
        try {
            val started =
                mutex.withLock {
                    if (!lifecycleState.tryBeginStart()) {
                        logcat(LogPriority.WARN) {
                            "Ignoring proxy start while lifecycle state is ${lifecycleState.state}"
                        }
                        return@withLock false
                    }

                    try {
                        val resolution = connectionPolicyResolver.resolve(mode = Mode.Proxy)
                        matchedRememberedPolicy = resolution.matchedNetworkPolicy
                        applyActiveConnectionPolicy(
                            session = session,
                            resolution = resolution,
                            restartReason = "initial_start",
                            appliedAt = System.currentTimeMillis(),
                        )
                        startProxy(resolution.proxyPreferences)
                        runtimeSession = session
                        serviceRuntimeRegistry.register(session)
                        updateStatus(ServiceStatus.Connected)
                        startNetworkHandoverMonitoring()
                        startTelemetryUpdates()
                        lifecycleState.markStarted()
                        true
                    } catch (e: Exception) {
                        lifecycleState.beginStop()
                        throw e
                    }
                }

            if (!started) {
                return
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to start proxy\n${e.asLog()}" }
            matchedRememberedPolicy?.let { policy ->
                rememberedNetworkPolicyStore.recordFailure(policy)
            }
            val reason = classifyFailureReason(e)
            updateStatus(ServiceStatus.Failed, reason)
            stop()
        }
    }

    private fun startForeground() {
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

    private suspend fun stop(
        skipProxyShutdown: Boolean = false,
        stopSelfStartId: Int? = null,
    ) {
        logcat(LogPriority.INFO) { "Stopping proxy" }

        val stopped =
            mutex.withLock {
                if (stopping) {
                    logcat(LogPriority.WARN) { "Proxy stop already in progress" }
                    return@withLock false
                }

                if (lifecycleState.state != ServiceLifecycleStateMachine.State.STOPPING) {
                    lifecycleState.beginStop()
                }
                stopping = true
                try {
                    handoverMonitorJob?.cancel()
                    handoverMonitorJob = null
                    if (!skipProxyShutdown) {
                        stopProxy()
                    } else {
                        proxyJob = null
                        proxy = null
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to stop proxy\n${e.asLog()}" }
                } finally {
                    try {
                        val session = runtimeSession
                        updateStatus(ServiceStatus.Disconnected)
                        telemetryJob?.cancel()
                        telemetryJob = null
                        session?.clearActiveConnectionPolicy()
                        session?.let {
                            serviceRuntimeRegistry.unregister(
                                mode = Mode.Proxy,
                                runtimeId = it.runtimeId,
                            )
                        }
                        runtimeSession = null
                        val stoppedSelf = stopSelfStartId?.let(::stopSelfResult)
                        if (stoppedSelf == null) {
                            stopSelf()
                        }
                    } finally {
                        stopping = false
                        lifecycleState.markStopped()
                    }
                }
                true
            }

        if (!stopped) {
            return
        }
    }

    private suspend fun startProxy(preferences: RipDpiProxyPreferences) {
        logcat(LogPriority.INFO) { "Starting proxy" }

        if (proxyJob != null) {
            logcat(LogPriority.WARN) { "Proxy fields not null" }
            throw IllegalStateException("Proxy fields not null")
        }

        val proxyInstance = ripDpiProxyFactory.create()
        proxy = proxyInstance

        val exitResult = CompletableDeferred<Result<Int>>()
        val job =
            lifecycleScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    exitResult.complete(runCatching { proxyInstance.startProxy(preferences) })
                } finally {
                    if (!exitResult.isCompleted) {
                        exitResult.complete(Result.failure(IllegalStateException("Proxy job cancelled")))
                    }
                }
            }
        proxyJob = job

        try {
            proxyInstance.awaitReady()
        } catch (e: Exception) {
            val proxyStartWasActive = job.isActive
            runCatching {
                if (proxyStartWasActive) {
                    proxyInstance.stopProxy()
                }
            }
            job.join()
            proxyJob = null
            proxy = null
            throw resolveProxyStartupFailure(
                readinessError = e,
                proxyStartWasActive = proxyStartWasActive,
                proxyStartResult = exitResult.await(),
            )
        }

        job.invokeOnCompletion {
            if (stopping) {
                return@invokeOnCompletion
            }
            lifecycleScope.launch {
                handleProxyExit(exitResult.await())
            }
        }

        logcat(LogPriority.INFO) { "Proxy started" }
    }

    private suspend fun stopProxy() {
        logcat(LogPriority.INFO) { "Stopping proxy" }

        val proxyInstance = proxy
        if (proxyInstance == null) {
            logcat(LogPriority.WARN) { "Proxy instance missing during stop" }
            proxyJob = null
            return
        }

        proxyInstance.stopProxy()
        proxyJob?.join()
        proxyJob = null
        proxy = null

        logcat(LogPriority.INFO) { "Proxy stopped" }
    }

    private suspend fun handleProxyExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("Proxy runtime failed", throwable)
                logcat(LogPriority.ERROR) { "Proxy failed\n${error.asLog()}" }
                classifyFailureReason(error)
            } ?: result.getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    logcat(LogPriority.ERROR) { "Proxy stopped with code $code" }
                    FailureReason.NativeError("Proxy exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        stop(skipProxyShutdown = true)
    }

    private fun updateStatus(newStatus: ServiceStatus, failureReason: FailureReason? = null) {
        logcat { "Proxy status changed from $status to $newStatus" }

        status = newStatus
        val currentTelemetry = serviceStateStore.telemetry.value
        val proxyTelemetry =
            if (newStatus == ServiceStatus.Connected) {
                NativeRuntimeSnapshot.idle(source = "proxy")
            } else {
                currentTelemetry.proxyTelemetry
            }
        val tunnelTelemetry =
            applyPendingNetworkHandoverClass(
                if (newStatus == ServiceStatus.Connected) {
                    NativeRuntimeSnapshot.idle(source = "tunnel")
                } else {
                    currentTelemetry.tunnelTelemetry
                },
            )
        val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
            currentWinningFamilies(currentTelemetry.runtimeFieldTelemetry)

        val appStatus =
            when (newStatus) {
                ServiceStatus.Connected -> {
                    AppStatus.Running
                }

                ServiceStatus.Failed,
                -> AppStatus.Halted

                ServiceStatus.Disconnected -> {
                    proxyJob = null
                    proxy = null
                    AppStatus.Halted
                }
            }
        serviceStateStore.setStatus(appStatus, Mode.Proxy)
        serviceStateStore.updateTelemetry(
            ServiceTelemetrySnapshot(
                mode = Mode.Proxy,
                status = appStatus,
                tunnelStats = proxyTelemetry.tunnelStats,
                proxyTelemetry = proxyTelemetry,
                tunnelTelemetry = tunnelTelemetry,
                runtimeFieldTelemetry =
                    deriveRuntimeFieldTelemetry(
                        telemetryNetworkFingerprintHash =
                            currentTelemetryFingerprintHash(currentTelemetry.runtimeFieldTelemetry),
                        winningTcpStrategyFamily = winningTcpStrategyFamily,
                        winningQuicStrategyFamily = winningQuicStrategyFamily,
                        winningDnsStrategyFamily = winningDnsStrategyFamily,
                        proxyTelemetry = proxyTelemetry,
                        tunnelTelemetry = tunnelTelemetry,
                        tunnelRecoveryRetryCount = 0,
                        failureReason = failureReason,
                    ),
                updatedAt = System.currentTimeMillis(),
            ),
        )

        if (newStatus == ServiceStatus.Failed) {
            serviceStateStore.emitFailed(
                Sender.Proxy,
                failureReason ?: FailureReason.Unexpected(IllegalStateException("Unknown failure")),
            )
        }
    }

    private fun startTelemetryUpdates() {
        telemetryJob?.cancel()
        telemetryJob =
            lifecycleScope.launch {
                while (status == ServiceStatus.Connected) {
                    val proxyTelemetry =
                        runCatching { proxy?.pollTelemetry() }.getOrNull()
                            ?: NativeRuntimeSnapshot.idle(source = "proxy")
                    val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
                        currentWinningFamilies(serviceStateStore.telemetry.value.runtimeFieldTelemetry)
                    val tunnelTelemetry =
                        applyPendingNetworkHandoverClass(
                            NativeRuntimeSnapshot.idle(source = "tunnel"),
                        )
                    serviceStateStore.updateTelemetry(
                        ServiceTelemetrySnapshot(
                            mode = Mode.Proxy,
                            status = AppStatus.Running,
                            tunnelStats = proxyTelemetry.tunnelStats,
                            proxyTelemetry = proxyTelemetry,
                            tunnelTelemetry = tunnelTelemetry,
                            runtimeFieldTelemetry =
                                deriveRuntimeFieldTelemetry(
                                    telemetryNetworkFingerprintHash =
                                        currentTelemetryFingerprintHash(
                                            serviceStateStore.telemetry.value.runtimeFieldTelemetry,
                                        ),
                                    winningTcpStrategyFamily = winningTcpStrategyFamily,
                                    winningQuicStrategyFamily = winningQuicStrategyFamily,
                                    winningDnsStrategyFamily = winningDnsStrategyFamily,
                                    proxyTelemetry = proxyTelemetry,
                                    tunnelTelemetry = tunnelTelemetry,
                                    tunnelRecoveryRetryCount = 0,
                                ),
                            updatedAt = maxOf(System.currentTimeMillis(), proxyTelemetry.capturedAt),
                        ),
                    )
                    delay(1_000)
                }
            }
    }

    private fun currentWinningFamilies(fallback: RuntimeFieldTelemetry): Triple<String?, String?, String?> {
        val activePolicy = runtimeSession?.currentActiveConnectionPolicy?.policy
        return if (activePolicy != null) {
            Triple(
                activePolicy.winningTcpStrategyFamily,
                activePolicy.winningQuicStrategyFamily,
                activePolicy.winningDnsStrategyFamily,
            )
        } else {
            Triple(
                fallback.winningTcpStrategyFamily,
                fallback.winningQuicStrategyFamily,
                fallback.winningDnsStrategyFamily,
            )
        }
    }

    private fun currentTelemetryFingerprintHash(fallback: RuntimeFieldTelemetry): String? =
        telemetryFingerprintHasher.hash(networkFingerprintProvider.capture())
            ?: fallback.telemetryNetworkFingerprintHash

    private fun startNetworkHandoverMonitoring() {
        handoverMonitorJob?.cancel()
        handoverMonitorJob =
            lifecycleScope.launch {
                networkHandoverMonitor.events.collect { event ->
                    runtimeSession?.pendingNetworkHandoverClass = event.classification
                    if (!event.isActionable) {
                        return@collect
                    }
                    handleNetworkHandover(event)
                }
            }
    }

    private suspend fun handleNetworkHandover(event: NetworkHandoverEvent) {
        if (status != ServiceStatus.Connected || stopping) {
            return
        }
        val session = runtimeSession ?: return
        val currentFingerprint = event.currentFingerprint ?: return
        val fingerprintHash = currentFingerprint.scopeKey()
        val now = System.currentTimeMillis()
        if (
            session.lastSuccessfulHandoverFingerprintHash == fingerprintHash &&
            now - session.lastSuccessfulHandoverAt < HandoverCooldownMs
        ) {
            return
        }

        val previousFingerprintHash = session.currentActiveConnectionPolicy?.fingerprintHash
        try {
            val resolution =
                connectionPolicyResolver.resolve(
                    mode = Mode.Proxy,
                    fingerprint = currentFingerprint,
                    handoverClassification = event.classification,
                )
            mutex.withLock {
                val activeSession = runtimeSession
                if (status != ServiceStatus.Connected || stopping || activeSession?.runtimeId != session.runtimeId) {
                    return
                }
                stopping = true
                try {
                    stopProxy()
                    applyActiveConnectionPolicy(
                        session = activeSession,
                        resolution = resolution,
                        restartReason = "network_handover",
                        appliedAt = now,
                    )
                    startProxy(resolution.proxyPreferences)
                } finally {
                    stopping = false
                }
            }

            session.lastSuccessfulHandoverFingerprintHash = fingerprintHash
            session.lastSuccessfulHandoverAt = now
            policyHandoverEventStore.publish(
                PolicyHandoverEvent(
                    mode = Mode.Proxy,
                    previousFingerprintHash = previousFingerprintHash,
                    currentFingerprintHash = fingerprintHash,
                    classification = event.classification,
                    usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                    policySignature = resolution.policySignature,
                    occurredAt = now,
                ),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to restart proxy after handover\n${e.asLog()}" }
            val reason = classifyFailureReason(e)
            updateStatus(ServiceStatus.Failed, reason)
            stop()
        }
    }

    private fun applyActiveConnectionPolicy(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        val policy = resolution.appliedPolicy ?: run {
            session.clearActiveConnectionPolicy()
            return
        }
        session.updateActiveConnectionPolicy(
            ActiveConnectionPolicy(
                mode = Mode.Proxy,
                policy = policy,
                matchedPolicy = resolution.matchedNetworkPolicy,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                fingerprintHash = resolution.fingerprintHash,
                policySignature = resolution.policySignature,
                appliedAt = appliedAt,
                restartReason = restartReason,
                handoverClassification = resolution.handoverClassification,
            ),
        )
    }

    private fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val session = runtimeSession ?: return snapshot
        val classification = session.pendingNetworkHandoverClass ?: return snapshot
        session.pendingNetworkHandoverClass = null
        return snapshot.copy(networkHandoverClass = classification)
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            RipDpiProxyService::class.java,
        )
}
