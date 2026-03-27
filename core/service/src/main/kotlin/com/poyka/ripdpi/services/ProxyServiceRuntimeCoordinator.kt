package com.poyka.ripdpi.services

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
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import javax.inject.Inject

internal class ProxyServiceRuntimeCoordinator(
    host: ServiceCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val statusReporter: ServiceStatusReporter,
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
        ioDispatcher = ioDispatcher,
        clock = clock,
    ) {
    private companion object {
        private const val TelemetryPollIntervalMs = 1_000L
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
        proxyRuntimeSupervisor.start(
            resolution.proxyPreferences.withLogContext(
                session.buildLogContext(session.currentActiveConnectionPolicy),
            ),
            ::handleProxyExit,
        )
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        if (skipRuntimeShutdown) {
            proxyRuntimeSupervisor.detach()
        } else {
            proxyRuntimeSupervisor.stop()
        }
    }

    override fun startModeTelemetryUpdates() {
        replaceTelemetryJob {
            while (status == ServiceStatus.Connected) {
                val proxyTelemetry =
                    proxyRuntimeSupervisor.pollTelemetry()
                        ?: NativeRuntimeSnapshot.idle(source = "proxy")
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
                    tunnelTelemetry = tunnelTelemetry,
                    tunnelRecoveryRetryCount = 0,
                )
                if (statusReporter.startedAt != null) {
                    host.updateNotification(
                        tunnelStats = proxyTelemetry.tunnelStats,
                        proxyTelemetry = proxyTelemetry,
                    )
                }
                delay(TelemetryPollIntervalMs)
            }
        }
    }

    override suspend fun restartAfterHandover(
        session: ProxyRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        proxyRuntimeSupervisor.stop()
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
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
        logcat(LogPriority.DEBUG) { "Proxy status: $status -> $newStatus" }
        status = newStatus
        statusReporter.reportStatus(
            newStatus = newStatus,
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
            tunnelRecoveryRetryCount = 0,
            failureReason = failureReason,
        )
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
                logcat(LogPriority.ERROR) { "Proxy failed\n${error.asLog()}" }
                classifyFailureReason(error)
            } ?: result
                .getOrNull()
                ?.takeIf { it != 0 }
                ?.let { code ->
                    logcat(LogPriority.ERROR) { "Proxy stopped with code $code" }
                    FailureReason.NativeError("Proxy exited with code $code")
                }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        proxyRuntimeSupervisor.detach()
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
        private val proxyRuntimeSupervisorFactory: ProxyRuntimeSupervisorFactory,
        private val serviceStatusReporterFactory: ServiceStatusReporterFactory,
    ) {
        fun create(host: ServiceCoordinatorHost): ProxyServiceRuntimeCoordinator =
            ProxyServiceRuntimeCoordinator(
                host = host,
                connectionPolicyResolver = connectionPolicyResolver,
                serviceRuntimeRegistry = serviceRuntimeRegistry,
                rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                networkHandoverMonitor = networkHandoverMonitor,
                policyHandoverEventStore = policyHandoverEventStore,
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
            )
    }
