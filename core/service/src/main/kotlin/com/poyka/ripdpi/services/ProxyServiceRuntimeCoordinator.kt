package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.warpConfigOrNull
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

internal class ProxyServiceRuntimeCoordinator(
    host: ServiceCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val statusReporter: ServiceStatusReporter,
    private val screenStateObserver: ScreenStateObserver,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: ServiceClock = SystemServiceClock,
) : BaseServiceRuntimeCoordinator<ProxyRuntimeSession>(
        mode = Mode.Proxy,
        host = host,
        connectionPolicyResolver = connectionPolicyResolver,
        serviceRuntimeRegistry = serviceRuntimeRegistry,
        rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
        networkHandoverMonitor = networkHandoverMonitor,
        policyHandoverEventStore = policyHandoverEventStore,
        permissionWatchdog = permissionWatchdog,
        ioDispatcher = ioDispatcher,
        clock = clock,
    ) {
    private companion object {
        private const val TelemetryPollIntervalMs = 1_000L
        private const val TelemetryPollIntervalBackgroundMs = 5_000L
    }

    override val serviceLabel: String = "proxy"

    override fun createRuntimeSession(): ProxyRuntimeSession = ProxyRuntimeSession()

    override suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(mode = Mode.Proxy)

    override suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.Proxy,
            fingerprint = fingerprint,
            handoverClassification = handoverClassification,
        )

    override fun applyActiveConnectionPolicy(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        val policy =
            resolution.appliedPolicy ?: run {
                session.clearActiveConnectionPolicy()
                return
            }
        session.updateActiveConnectionPolicy(
            ActiveConnectionPolicy(
                mode = Mode.Proxy,
                policy = policy,
                matchedPolicy = resolution.matchedNetworkPolicy,
                usedRememberedPolicy = resolution.matchedNetworkPolicy != null,
                rememberedPolicyAppliedByExactMatch = resolution.rememberedPolicyAppliedByExactMatch,
                fingerprintHash = resolution.fingerprintHash,
                policySignature = resolution.policySignature,
                appliedAt = appliedAt,
                restartReason = restartReason,
                handoverClassification = resolution.handoverClassification,
            ),
        )
    }

    override suspend fun startResolvedRuntime(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ) {
        resolution.proxyPreferences.relayConfigOrNull()?.let { relayConfig ->
            upstreamRelaySupervisor.start(relayConfig, ::handleRelayExit)
        }
        resolution.proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
            warpRuntimeSupervisor.start(warpConfig, ::handleWarpExit)
        }
        proxyRuntimeSupervisor.start(
            resolution.proxyPreferences.withLogContext(
                session.buildLogContext(session.currentActiveConnectionPolicy),
            ),
            ::handleProxyExit,
        )
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        if (skipRuntimeShutdown) {
            upstreamRelaySupervisor.detach()
            warpRuntimeSupervisor.detach()
            proxyRuntimeSupervisor.detach()
        } else {
            proxyRuntimeSupervisor.stop()
            warpRuntimeSupervisor.stop()
            upstreamRelaySupervisor.stop()
        }
    }

    override fun startModeTelemetryUpdates() {
        replaceTelemetryJob {
            while (status == ServiceStatus.Connected) {
                val proxyTelemetry =
                    proxyRuntimeSupervisor.pollTelemetry()
                        ?: NativeRuntimeSnapshot.idle(source = "proxy")
                val relayTelemetry =
                    upstreamRelaySupervisor.pollTelemetry()
                        ?: NativeRuntimeSnapshot.idle(source = "relay")
                val warpTelemetry =
                    warpRuntimeSupervisor.pollTelemetry()
                        ?: NativeRuntimeSnapshot.idle(source = "warp")
                val tunnelTelemetry =
                    consumePendingNetworkHandoverClass()
                        ?.let { classification ->
                            NativeRuntimeSnapshot.idle(source = "tunnel").copy(
                                networkHandoverClass = classification,
                            )
                        }
                        ?: NativeRuntimeSnapshot.idle(source = "tunnel")
                statusReporter.reportTelemetry(
                    activePolicy = runtimeSession?.currentActiveConnectionPolicy,
                    consumePendingNetworkHandoverClass = { null },
                    proxyTelemetry = proxyTelemetry,
                    relayTelemetry = relayTelemetry,
                    warpTelemetry = warpTelemetry,
                    tunnelTelemetry = tunnelTelemetry,
                    tunnelRecoveryRetryCount = 0,
                )
                if (statusReporter.startedAt != null && screenStateObserver.isInteractive.value) {
                    host.updateNotification(
                        tunnelStats = proxyTelemetry.tunnelStats,
                        proxyTelemetry = proxyTelemetry,
                    )
                }
                val interval =
                    if (screenStateObserver.isInteractive.value) {
                        TelemetryPollIntervalMs
                    } else {
                        TelemetryPollIntervalBackgroundMs
                    }
                delay(interval)
            }
        }
    }

    override suspend fun restartAfterHandover(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        upstreamRelaySupervisor.stop()
        warpRuntimeSupervisor.stop()
        proxyRuntimeSupervisor.stop()
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
        resolution.proxyPreferences.relayConfigOrNull()?.let { relayConfig ->
            upstreamRelaySupervisor.start(relayConfig, ::handleRelayExit)
        }
        resolution.proxyPreferences.warpConfigOrNull()?.let { warpConfig ->
            warpRuntimeSupervisor.start(warpConfig, ::handleWarpExit)
        }
        proxyRuntimeSupervisor.start(
            resolution.proxyPreferences.withLogContext(
                session.buildLogContext(session.currentActiveConnectionPolicy),
            ),
            ::handleProxyExit,
        )
    }

    override fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        Logger.d { "Proxy status: $status -> $newStatus" }
        status = newStatus
        statusReporter.reportStatus(
            newStatus = newStatus,
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
            tunnelRecoveryRetryCount = 0,
            failureReason = failureReason,
        )
    }

    override fun onPermissionRevoked(event: PermissionChangeEvent) {
        if (event.kind == PermissionChangeEvent.KIND_NOTIFICATIONS) {
            Logger.i { "Notification permission revoked while proxy running" }
        }
    }

    override fun classifyStartupFailure(error: Exception): FailureReason = classifyFailureReason(error)

    override fun classifyHandoverFailure(error: Exception): FailureReason = classifyFailureReason(error)

    private suspend fun handleProxyExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("Proxy runtime failed", throwable)
                Logger.e(error) { "Proxy failed" }
                classifyFailureReason(error)
            } ?: result
                .getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    Logger.e { "Proxy stopped with code $code" }
                    FailureReason.NativeError("Proxy exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        proxyRuntimeSupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private suspend fun handleWarpExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("WARP runtime failed", throwable)
                Logger.e(error) { "WARP failed" }
                classifyFailureReason(error)
            } ?: result
                .getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    Logger.e { "WARP stopped with code $code" }
                    FailureReason.NativeError("WARP exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        warpRuntimeSupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private suspend fun handleRelayExit(result: Result<Int>) {
        if (stopping) {
            return
        }

        val failureReason =
            result.exceptionOrNull()?.let { throwable ->
                val error = throwable as? Exception ?: IllegalStateException("Relay runtime failed", throwable)
                Logger.e(error) { "Relay failed" }
                classifyFailureReason(error)
            } ?: result
                .getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    Logger.e { "Relay stopped with code $code" }
                    FailureReason.NativeError("Relay exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        upstreamRelaySupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }
}

internal class ProxyServiceRuntimeCoordinatorFactory
    @Inject
    constructor(
        private val connectionPolicyResolver: ConnectionPolicyResolver,
        private val serviceStateStore: ServiceStateStore,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val telemetryFingerprintHasher: TelemetryFingerprintHasher,
        private val serviceRuntimeRegistry: ServiceRuntimeRegistry,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val networkHandoverMonitor: NetworkHandoverMonitor,
        private val policyHandoverEventStore: PolicyHandoverEventStore,
        private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        private val upstreamRelaySupervisorFactory: UpstreamRelaySupervisorFactory,
        private val warpRuntimeSupervisorFactory: WarpRuntimeSupervisorFactory,
        private val proxyRuntimeSupervisorFactory: ProxyRuntimeSupervisorFactory,
        private val serviceStatusReporterFactory: ServiceStatusReporterFactory,
        private val permissionWatchdog: PermissionWatchdog,
        private val screenStateObserver: ScreenStateObserver,
    ) {
        fun create(host: ServiceCoordinatorHost): ProxyServiceRuntimeCoordinator =
            ProxyServiceRuntimeCoordinator(
                host = host,
                connectionPolicyResolver = connectionPolicyResolver,
                serviceRuntimeRegistry = serviceRuntimeRegistry,
                rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                networkHandoverMonitor = networkHandoverMonitor,
                policyHandoverEventStore = policyHandoverEventStore,
                permissionWatchdog = permissionWatchdog,
                upstreamRelaySupervisor =
                    upstreamRelaySupervisorFactory.create(
                        scope = host.serviceScope,
                        dispatcher = Dispatchers.IO,
                    ),
                warpRuntimeSupervisor =
                    warpRuntimeSupervisorFactory.create(
                        scope = host.serviceScope,
                        dispatcher = Dispatchers.IO,
                    ),
                proxyRuntimeSupervisor =
                    proxyRuntimeSupervisorFactory.create(
                        scope = host.serviceScope,
                        dispatcher = Dispatchers.IO,
                        networkSnapshotProvider = networkSnapshotProvider,
                    ),
                statusReporter =
                    serviceStatusReporterFactory.create(
                        mode = Mode.Proxy,
                        sender = Sender.Proxy,
                        serviceStateStore = serviceStateStore,
                        networkFingerprintProvider = networkFingerprintProvider,
                        telemetryFingerprintHasher = telemetryFingerprintHasher,
                    ),
                screenStateObserver = screenStateObserver,
            )
    }
