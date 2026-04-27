package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.toStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ProxyRuntimeSupervisorBundle(
    val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
)

internal class ProxyServiceRuntimeCoordinator(
    host: ServiceCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    supervisors: ProxyRuntimeSupervisorBundle,
    private val statusReporter: ServiceStatusReporter,
    private val screenStateObserver: ScreenStateObserver,
    private val directPathPolicyTelemetryConsumer:
        DirectPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
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

    private val upstreamRelaySupervisor = supervisors.upstreamRelaySupervisor
    private val warpRuntimeSupervisor = supervisors.warpRuntimeSupervisor
    private val proxyRuntimeSupervisor = supervisors.proxyRuntimeSupervisor

    private val proxyRuntimeStack =
        SharedProxyRuntimeStack(
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
        )

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
        proxyRuntimeStack.start(
            proxyPreferences =
                resolution.proxyPreferences.withLogContext(
                    session.buildLogContext(session.currentActiveConnectionPolicy),
                ),
            onRelayExit = ::handleRelayExit,
            onWarpExit = ::handleWarpExit,
            onProxyExit = ::handleProxyExit,
        )
    }

    override suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        proxyRuntimeStack.stop(skipRuntimeShutdown)
    }

    override fun startModeTelemetryUpdates() {
        replaceTelemetryJob {
            while (status == ServiceStatus.Connected) {
                val proxyTelemetryOutcome = proxyRuntimeSupervisor.pollTelemetry()
                val relayTelemetryOutcome = upstreamRelaySupervisor.pollTelemetry()
                val warpTelemetryOutcome = warpRuntimeSupervisor.pollTelemetry()
                val proxyTelemetry = proxyTelemetryOutcome.snapshotOrIdle(source = "proxy")
                val relayTelemetry = relayTelemetryOutcome.snapshotOrIdle(source = "relay")
                val warpTelemetry = warpTelemetryOutcome.snapshotOrIdle(source = "warp")
                val tunnelTelemetry =
                    consumePendingNetworkHandoverClass()
                        ?.let { classification ->
                            NativeRuntimeSnapshot.idle(source = "tunnel").copy(
                                networkHandoverClass = classification,
                            )
                        }
                        ?: NativeRuntimeSnapshot.idle(source = "tunnel")
                directPathPolicyTelemetryConsumer.consume(proxyTelemetry)
                statusReporter.reportTelemetry(
                    activePolicy = runtimeSession?.currentActiveConnectionPolicy,
                    consumePendingNetworkHandoverClass = { null },
                    currentNetworkHandoverState = currentNetworkHandoverState,
                    proxyTelemetry = proxyTelemetry,
                    relayTelemetry = relayTelemetry,
                    warpTelemetry = warpTelemetry,
                    tunnelTelemetry = tunnelTelemetry,
                    proxyTelemetryStatus = proxyTelemetryOutcome.toStatus(),
                    relayTelemetryStatus = relayTelemetryOutcome.toStatus(),
                    warpTelemetryStatus = warpTelemetryOutcome.toStatus(),
                    tunnelTelemetryStatus = RuntimeTelemetryOutcome.NoData.toStatus(),
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
        proxyRuntimeStack.stop(skipRuntimeShutdown = false)
        applyActiveConnectionPolicy(
            session = session,
            resolution = resolution,
            restartReason = "network_handover",
            appliedAt = appliedAt,
        )
        proxyRuntimeStack.start(
            proxyPreferences =
                resolution.proxyPreferences.withLogContext(
                    session.buildLogContext(session.currentActiveConnectionPolicy),
                ),
            onRelayExit = ::handleRelayExit,
            onWarpExit = ::handleWarpExit,
            onProxyExit = ::handleProxyExit,
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
            currentNetworkHandoverState = currentNetworkHandoverState,
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

    private suspend fun handleProxyExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "Proxy stopped with code ${cause.code}" }
                    FailureReason.NativeError("Proxy exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "Proxy failed" }
                    classifyFailureReason(error)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "Proxy runtime was cancelled unexpectedly" }
                    FailureReason.NativeError("Proxy runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        proxyRuntimeSupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private suspend fun handleWarpExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "WARP stopped with code ${cause.code}" }
                    FailureReason.WarpRuntimeFailed("WARP exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "WARP failed" }
                    classifyFailureReason(error)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "WARP runtime was cancelled unexpectedly" }
                    FailureReason.WarpRuntimeFailed("WARP runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        warpRuntimeSupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private suspend fun handleRelayExit(cause: SupervisorExitCause) {
        if (cause is SupervisorExitCause.ExpectedStop) {
            return
        }

        val failureReason =
            when (cause) {
                is SupervisorExitCause.Crash -> {
                    Logger.e { "Relay stopped with code ${cause.code}" }
                    FailureReason.NativeError("Relay exited with code ${cause.code}")
                }

                is SupervisorExitCause.StartupFailure -> {
                    val error = cause.throwable
                    Logger.e(error) { "Relay failed" }
                    classifyFailureReason(error)
                }

                SupervisorExitCause.Cancellation -> {
                    Logger.e { "Relay runtime was cancelled unexpectedly" }
                    FailureReason.NativeError("Relay runtime was cancelled unexpectedly")
                }

                SupervisorExitCause.ExpectedStop -> {
                    null
                }
            }

        if (failureReason != null) {
            updateStatus(ServiceStatus.Failed, failureReason)
        }

        upstreamRelaySupervisor.detach()
        host.serviceScope.launch(ioDispatcher) { stop(skipRuntimeShutdown = true) }
    }

    private fun RuntimeTelemetryOutcome.snapshotOrIdle(source: String): NativeRuntimeSnapshot =
        when (this) {
            is RuntimeTelemetryOutcome.Snapshot -> snapshot

            RuntimeTelemetryOutcome.NoData,
            is RuntimeTelemetryOutcome.EngineError,
            -> NativeRuntimeSnapshot.idle(source = source)
        }
}
