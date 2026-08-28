package com.poyka.ripdpi.service.runtime.vpn

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.classifyFailureReason
import com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisor
import com.poyka.ripdpi.services.AmneziaWgRuntimeSupervisorFactory
import com.poyka.ripdpi.services.AutolearnActivationReceiptPublisher
import com.poyka.ripdpi.services.BaseServiceRuntimeCoordinator
import com.poyka.ripdpi.services.ConnectionPolicyResolution
import com.poyka.ripdpi.services.ConnectionPolicyResolver
import com.poyka.ripdpi.services.DirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.InitialRelayRacePolicy
import com.poyka.ripdpi.services.LocalProxyEndpoint
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import com.poyka.ripdpi.services.NoOpDirectPathPolicyTelemetryConsumer
import com.poyka.ripdpi.services.PermissionChangeEvent
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.ProxyRuntimeStartResult
import com.poyka.ripdpi.services.ProxyRuntimeSupervisor
import com.poyka.ripdpi.services.ProxyRuntimeSupervisorFactory
import com.poyka.ripdpi.services.RootHelperManager
import com.poyka.ripdpi.services.RuntimeStartEvidence
import com.poyka.ripdpi.services.RuntimeStartTransaction
import com.poyka.ripdpi.services.RuntimeStopGuard
import com.poyka.ripdpi.services.ScreenStateObserver
import com.poyka.ripdpi.services.ServiceClock
import com.poyka.ripdpi.services.ServiceRuntimeHandoverHooks
import com.poyka.ripdpi.services.ServiceRuntimeModeHooks
import com.poyka.ripdpi.services.ServiceRuntimePermissionHooks
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.ServiceRuntimeStartHooks
import com.poyka.ripdpi.services.ServiceRuntimeStatusHooks
import com.poyka.ripdpi.services.ServiceRuntimeStopHooks
import com.poyka.ripdpi.services.ServiceStatusReporter
import com.poyka.ripdpi.services.ServiceStatusReporterFactory
import com.poyka.ripdpi.services.SharedProxyRuntimeStack
import com.poyka.ripdpi.services.SystemServiceClock
import com.poyka.ripdpi.services.TelemetryFingerprintHasher
import com.poyka.ripdpi.services.TelemetryJobReplacer
import com.poyka.ripdpi.services.TransportFailoverApplyTracker
import com.poyka.ripdpi.services.TransportFailoverTarget
import com.poyka.ripdpi.services.UpstreamRelaySupervisor
import com.poyka.ripdpi.services.UpstreamRelaySupervisorFactory
import com.poyka.ripdpi.services.VpnCoordinatorHost
import com.poyka.ripdpi.services.VpnDnsPolicyCoordinator
import com.poyka.ripdpi.services.VpnEncryptedDnsFailoverController
import com.poyka.ripdpi.services.VpnProtectFailureMonitor
import com.poyka.ripdpi.services.VpnResolverRefreshPlanner
import com.poyka.ripdpi.services.VpnRuntimeCompositionCoordinator
import com.poyka.ripdpi.services.VpnRuntimeSession
import com.poyka.ripdpi.services.VpnSupervisorExitHandler
import com.poyka.ripdpi.services.VpnTelemetryStateAccess
import com.poyka.ripdpi.services.VpnTunnelRefreshCallbacks
import com.poyka.ripdpi.services.VpnTunnelRefreshCoordinator
import com.poyka.ripdpi.services.VpnTunnelRefreshDependencies
import com.poyka.ripdpi.services.VpnTunnelRuntime
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.WarpRuntimeSupervisor
import com.poyka.ripdpi.services.WarpRuntimeSupervisorFactory
import com.poyka.ripdpi.services.XrayProviderSessionController
import com.poyka.ripdpi.services.toRuntimeStartEvidence
import com.poyka.ripdpi.services.transportFailoverTargetOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Concrete VPN-mode runtime coordinator. Composes the VPN tunnel, the proxy
 * runtime stack, DNS policy, relay supervision, and the protect server into a
 * running VPN session and drives start / stop / handover. The coordinator
 * (DI-wiring) half of the `service` layer over the `services` implementations.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class VpnServiceRuntimeCoordinator(
    vpnHost: VpnCoordinatorHost,
    connectionPolicyResolver: ConnectionPolicyResolver,
    private val resolverOverrideStore: ResolverOverrideStore,
    serviceRuntimeRegistry: ServiceRuntimeRegistry,
    rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    networkHandoverMonitor: NetworkHandoverMonitor,
    policyHandoverEventStore: PolicyHandoverEventStore,
    permissionWatchdog: PermissionWatchdog,
    private val vpnProtectFailureMonitor: VpnProtectFailureMonitor,
    private val vpnTunnelRuntime: VpnTunnelRuntime,
    private val resolverRefreshPlanner: VpnResolverRefreshPlanner,
    private val encryptedDnsFailoverController: VpnEncryptedDnsFailoverController,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val autolearnActivationReceiptPublisher: AutolearnActivationReceiptPublisher,
    private val statusReporter: ServiceStatusReporter,
    private val transportFailoverApplyTracker: TransportFailoverApplyTracker,
    private val screenStateObserver: ScreenStateObserver,
    private val directPathPolicyTelemetryConsumer:
        DirectPathPolicyTelemetryConsumer = NoOpDirectPathPolicyTelemetryConsumer,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: ServiceClock = SystemServiceClock,
    private val transportFailoverRuntimeTimeoutMillis: Long = 45_000L,
    private val rootHelperManager: RootHelperManager = RootHelperManager(),
    /**
     * Optional embedded-Xray provider seam. Null when the provider is not wired;
     * non-null in production (provided by `VpnServiceSessionModule`). It only
     * takes over when the durable selection is Xray — the native path is
     * untouched otherwise.
     */
    private val xrayProviderSessionController: XrayProviderSessionController? = null,
    private val initialRelayRacePolicy: InitialRelayRacePolicy? = null,
) : BaseServiceRuntimeCoordinator<VpnRuntimeSession>(
        mode = Mode.VPN,
        host = vpnHost,
        connectionPolicyResolver = connectionPolicyResolver,
        serviceRuntimeRegistry = serviceRuntimeRegistry,
        rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
        networkHandoverMonitor = networkHandoverMonitor,
        policyHandoverEventStore = policyHandoverEventStore,
        permissionWatchdog = permissionWatchdog,
        ioDispatcher = ioDispatcher,
        clock = clock,
    ) {
    private val proxyRuntimeStack =
        SharedProxyRuntimeStack(
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            amneziaWgRuntimeSupervisor = amneziaWgRuntimeSupervisor,
            proxyRuntimeSupervisor = proxyRuntimeSupervisor,
            clearForeignRelayFailed = statusReporter::clearForeignRelayFailed,
        )
    private val dnsPolicyCoordinator =
        VpnDnsPolicyCoordinator(
            resolverRefreshPlanner = resolverRefreshPlanner,
            encryptedDnsFailoverController = encryptedDnsFailoverController,
        )
    private val supervisorExitHandler =
        VpnSupervisorExitHandler(
            host = vpnHost,
            ioDispatcher = ioDispatcher,
            proxyRuntimeStack = proxyRuntimeStack,
            upstreamRelaySupervisor = upstreamRelaySupervisor,
            warpRuntimeSupervisor = warpRuntimeSupervisor,
            amneziaWgRuntimeSupervisor = amneziaWgRuntimeSupervisor,
            updateStatus = ::updateStatus,
            markForeignRelayFailed = statusReporter::markForeignRelayFailed,
            stopService = { skipRuntimeShutdown -> stop(skipRuntimeShutdown = skipRuntimeShutdown) },
        )
    private val runtimeCompositionCoordinator =
        VpnRuntimeCompositionCoordinator(
            proxyRuntimeStack = proxyRuntimeStack,
            vpnTunnelRuntime = vpnTunnelRuntime,
            supervisorExitHandler = supervisorExitHandler,
            applyActiveConnectionPolicy = ::applyActiveConnectionPolicy,
            providerController = xrayProviderSessionController,
            initialRelayRacePolicy = initialRelayRacePolicy,
        )
    private val telemetryCoordinator =
        createVpnRuntimeTelemetryCoordinator(
            runtimePorts =
                VpnRuntimeTelemetryRuntimePorts(
                    host = vpnHost,
                    ioDispatcher = ioDispatcher,
                    mutex = mutex,
                    protectFailureMonitor = vpnProtectFailureMonitor,
                    tunnelRuntime = vpnTunnelRuntime,
                    xrayController = xrayProviderSessionController,
                ),
            supervisors =
                VpnRuntimeTelemetrySupervisors(
                    upstreamRelay = upstreamRelaySupervisor,
                    warp = warpRuntimeSupervisor,
                    amneziaWg = amneziaWgRuntimeSupervisor,
                    proxy = proxyRuntimeSupervisor,
                ),
            reporterPorts =
                VpnRuntimeTelemetryReporterPorts(
                    statusReporter = statusReporter,
                    screenStateObserver = screenStateObserver,
                    directPathPolicyTelemetryConsumer = directPathPolicyTelemetryConsumer,
                ),
            stateBindings =
                VpnRuntimeTelemetryStateBindings(
                    currentStatus = { status },
                    isStopping = { stopping || handoverRestarting },
                    currentSession = { runtimeSession },
                    currentLocalProxyEndpoint = { runtimeCompositionCoordinator.currentLocalProxyEndpoint },
                    currentNetworkHandoverState = { currentNetworkHandoverState() },
                    applyPendingNetworkHandoverClass = { snapshot -> applyPendingNetworkHandoverClass(snapshot) },
                ),
            actions =
                VpnRuntimeTelemetryActions(
                    updateStatus = { status, failureReason ->
                        if (status == ServiceStatus.Failed) {
                            updateFailedStatusAfterRetainingProviderBarrier(failureReason)
                        } else {
                            updateStatus(status, failureReason)
                        }
                    },
                    failAndStopService = { failureReason, guard, beforeFailureStatus ->
                        failAndStopAfterRetainingProviderBarrier(
                            failureReason = failureReason,
                            guard = guard,
                            beforeFailureStatus = beforeFailureStatus,
                        )
                    },
                    stopService = { guard -> stop(guard = guard) },
                ),
        )
    private val tunnelRefreshCoordinator =
        VpnTunnelRefreshCoordinator(
            dependencies =
                object : VpnTunnelRefreshDependencies {
                    override val mutex = this@VpnServiceRuntimeCoordinator.mutex
                    override val vpnTunnelRuntime = this@VpnServiceRuntimeCoordinator.vpnTunnelRuntime
                    override val dnsPolicyCoordinator = this@VpnServiceRuntimeCoordinator.dnsPolicyCoordinator
                },
            state =
                object : VpnTelemetryStateAccess {
                    override fun status(): ServiceStatus = status

                    override fun stopping(): Boolean = stopping || handoverRestarting

                    override fun runtimeSession(): VpnRuntimeSession? = runtimeSession

                    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? =
                        runtimeCompositionCoordinator.currentLocalProxyEndpoint

                    override fun currentNetworkHandoverState(): String? =
                        this@VpnServiceRuntimeCoordinator.currentNetworkHandoverState()

                    override fun applyPendingNetworkHandoverClass(
                        snapshot: com.poyka.ripdpi.data.NativeRuntimeSnapshot,
                    ): com.poyka.ripdpi.data.NativeRuntimeSnapshot =
                        this@VpnServiceRuntimeCoordinator.applyPendingNetworkHandoverClass(snapshot)
                },
            callbacks =
                object : VpnTunnelRefreshCallbacks {
                    override suspend fun recomposeRuntimeForPolicyChange(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) {
                        if (runtimeSession?.runtimeId != session.runtimeId) return
                        withContext(NonCancellable) {
                            val startResult =
                                runtimeCompositionCoordinator.restartAfterPolicyChange(
                                    session = session,
                                    resolution = resolution,
                                    appliedAt = clock.nowMillis(),
                                    restartReason = "routing_policy_refresh",
                                )
                            publishReplacementEvidence(session, resolution, startResult)
                        }
                    }

                    override fun updateRuntimeDnsState(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) {
                        runtimeCompositionCoordinator.updateRuntimeDnsState(session, resolution)
                    }

                    override suspend fun failTunnelRefresh(
                        session: VpnRuntimeSession,
                        error: Exception,
                    ) {
                        if (runtimeSession?.runtimeId != session.runtimeId) return
                        updateFailedStatusAfterRetainingProviderBarrier(
                            failureReason = classifyFailureReason(error, isTunnelContext = true),
                            lifecycleMutexHeld = true,
                        )
                    }
                },
        )

    override val runtimeHooks =
        ServiceRuntimeModeHooks(
            serviceLabel = "VPN",
            startHooks =
                ServiceRuntimeStartHooks(
                    createRuntimeSession = ::createRuntimeSession,
                    resolveInitialConnectionPolicy = ::resolveInitialConnectionPolicy,
                    applyActiveConnectionPolicy = ::applyActiveConnectionPolicy,
                    startResolvedRuntime = ::startResolvedRuntime,
                    publishRuntimeStartEvidence = ::publishRuntimeStartEvidence,
                    startModeTelemetryUpdates = ::startModeTelemetryUpdates,
                ),
            stopHooks =
                ServiceRuntimeStopHooks(
                    stopModeRuntime = ::stopModeRuntime,
                    captureFinalTelemetry = telemetryCoordinator::captureFinalTelemetry,
                    onAfterStopCleanup = ::onAfterStopCleanup,
                ),
            handoverHooks =
                ServiceRuntimeHandoverHooks(
                    resolveConnectionPolicy = ::resolveHandoverConnectionPolicy,
                    restartAfterHandover = ::restartAfterHandover,
                    classifyFailure = ::classifyHandoverFailure,
                    retainFailClosedAfterExhaustion = {
                        runtimeSession?.revokeInPathLease()
                        runtimeCompositionCoordinator.retainFailClosedAfterHandoverFailure()
                    },
                ),
            statusHooks =
                ServiceRuntimeStatusHooks(
                    updateStatus = ::updateStatus,
                    classifyStartupFailure = ::classifyStartupFailure,
                ),
            permissionHooks = ServiceRuntimePermissionHooks(::onPermissionRevoked),
        )

    private fun createRuntimeSession(): VpnRuntimeSession = VpnRuntimeSession()

    private suspend fun resolveInitialConnectionPolicy(): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.VPN,
            resolverOverride = resolverOverrideStore.override.value,
        )

    private suspend fun resolveHandoverConnectionPolicy(
        fingerprint: NetworkFingerprint,
        handoverClassification: String,
    ): ConnectionPolicyResolution =
        connectionPolicyResolver.resolve(
            mode = Mode.VPN,
            resolverOverride = resolverOverrideStore.override.value,
            fingerprint = fingerprint,
            handoverClassification = handoverClassification,
        )

    private fun applyActiveConnectionPolicy(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        restartReason: String,
        appliedAt: Long,
    ) {
        session.updateActiveConnectionPolicy(
            resolution.toVpnActiveConnectionPolicy(
                restartReason = restartReason,
                appliedAt = appliedAt,
            ),
        )
    }

    private suspend fun startResolvedRuntime(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
    ): RuntimeStartEvidence {
        val startResult = runtimeCompositionCoordinator.start(session, resolution)
        return startResult.toRuntimeStartEvidence()
    }

    private suspend fun publishRuntimeStartEvidence(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        evidence: RuntimeStartEvidence,
    ) {
        if (evidence !is RuntimeStartEvidence.ProxySnapshot) return
        autolearnActivationReceiptPublisher.publish(
            session = session,
            resolution = resolution,
            evidence = evidence,
            observedAt = clock.nowMillis(),
        )
        statusReporter.reportRuntimeStartTelemetry(
            activePolicy = session.currentActiveConnectionPolicy,
            currentNetworkHandoverState = currentNetworkHandoverState,
            proxyTelemetry = evidence.snapshot,
            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
            xrayProviderSnapshot = xrayProviderSessionController?.currentSnapshotOrNull(),
        )
    }

    override fun onDestroy() {
        xrayProviderSessionController?.closeServiceOwner()
        super.onDestroy()
    }

    private suspend fun stopModeRuntime(skipRuntimeShutdown: Boolean) {
        runtimeSession?.revokeInPathLease()
        runtimeCompositionCoordinator.stop(skipRuntimeShutdown)
    }

    private fun startModeTelemetryUpdates(replaceTelemetryJob: TelemetryJobReplacer) {
        telemetryCoordinator.start(tunnelRefreshCoordinator, replaceTelemetryJob)
    }

    private suspend fun restartAfterHandover(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        appliedAt: Long,
    ) {
        val startResult = runtimeCompositionCoordinator.restartAfterHandover(session, resolution, appliedAt)
        publishReplacementEvidence(session, resolution, startResult)
    }

    /**
     * Applies a Simple-flavor transport failover inside the active VPN session.
     *
     * The installed TUN remains the fail-closed barrier while the proxy/relay stack is
     * replaced, so Android lockdown never has to permit a user-style service Stop.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun restartAfterTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
    ) {
        var terminalFailure: Exception? = null
        mutex.withLock {
            var runtimeClaimed = false
            var runtimeApplied = false
            try {
                withContext(NonCancellable) {
                    withTimeout(transportFailoverRuntimeTimeoutMillis) {
                        val preparation = prepareTransportFailover(requestId, expectedTarget) ?: return@withTimeout
                        runtimeClaimed = true
                        handoverRestarting = true
                        val startResult =
                            runtimeCompositionCoordinator.restartAfterPolicyChange(
                                session = preparation.session,
                                resolution = preparation.resolution,
                                appliedAt = clock.nowMillis(),
                                restartReason = "transport_failover",
                            )
                        publishReplacementEvidence(preparation.session, preparation.resolution, startResult)
                        check(transportFailoverApplyTracker.recordApplied(requestId)) {
                            "Transport failover apply request expired before runtime acknowledgement"
                        }
                        runtimeApplied = true
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!handleTransportFailoverFailure(requestId, cancelled, runtimeClaimed, runtimeApplied)) {
                    terminalFailure = cancelled
                }
            } catch (failure: Exception) {
                if (handleTransportFailoverFailure(requestId, failure, runtimeClaimed, runtimeApplied)) {
                    return@withLock
                }
                terminalFailure = failure
            } finally {
                if (runtimeClaimed) {
                    transportFailoverApplyTracker.releaseRuntimeOwnership(requestId)
                }
                handoverRestarting = false
            }
        }
        terminalFailure?.let { failure ->
            val rollbackSafe = terminateFailedTransportReplacement(requestId)
            transportFailoverApplyTracker.recordRuntimeFailure(requestId, rollbackSafe = rollbackSafe)
            if (failure !is CancellationException) {
                throw failure
            }
        }
    }

    /** Explicit editor activation supports cold start and barrier-preserving replacement. */
    suspend fun activateTransport(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
    ) {
        if (mutex.withLock { status == ServiceStatus.Connected && !stopping }) {
            restartAfterTransportFailover(requestId, expectedTarget)
            return
        }
        var claimed = false
        var applied = false
        try {
            withContext(NonCancellable) {
                withTimeout(transportFailoverRuntimeTimeoutMillis) {
                    startTransaction(
                        RuntimeStartTransaction(
                            beforeStart = { resolution ->
                                check(resolution.transportFailoverTargetOrNull() == expectedTarget) {
                                    "Selected transport changed before startup"
                                }
                                check(transportFailoverApplyTracker.claimApplying(requestId)) {
                                    "Transport activation request expired before startup"
                                }
                                claimed = true
                            },
                            onStarted = {
                                check(transportFailoverApplyTracker.recordApplied(requestId)) {
                                    "Transport activation acknowledgement rejected"
                                }
                                applied = true
                            },
                        ),
                    )
                }
            }
        } finally {
            if (!applied) {
                val rollbackSafe = !claimed || terminateFailedTransportReplacement(requestId)
                transportFailoverApplyTracker.recordRuntimeFailure(requestId, rollbackSafe)
            }
            if (claimed) transportFailoverApplyTracker.releaseRuntimeOwnership(requestId)
        }
    }

    private suspend fun publishReplacementEvidence(
        session: VpnRuntimeSession,
        resolution: ConnectionPolicyResolution,
        startResult: ProxyRuntimeStartResult?,
    ) {
        publishRuntimeStartEvidence(session, resolution, startResult.toRuntimeStartEvidence())
    }

    private suspend fun handleTransportFailoverFailure(
        requestId: Long,
        failure: Exception,
        runtimeClaimed: Boolean,
        runtimeApplied: Boolean,
    ): Boolean =
        when {
            runtimeApplied -> {
                Logger.i { "Transport failover request=$requestId completed before command cancellation" }
                true
            }

            !runtimeClaimed -> {
                transportFailoverApplyTracker.recordRollbackSafeFailure(requestId)
                Logger.w(failure) { "Transport failover failed before runtime mutation request=$requestId" }
                true
            }

            else -> {
                updateFailedStatusAfterRetainingProviderBarrier(
                    failureReason = classifyFailureReason(failure, isTunnelContext = true),
                    lifecycleMutexHeld = true,
                )
                false
            }
        }

    private suspend fun terminateFailedTransportReplacement(requestId: Long): Boolean =
        withContext(NonCancellable) {
            runCatching { stop() }
                .onFailure { cleanupFailure ->
                    Logger.e(cleanupFailure) {
                        "Failed to terminate transport replacement request=$requestId"
                    }
                }
            val cleanupCompleted =
                runtimeSession == null &&
                    !vpnTunnelRuntime.isRunning &&
                    !vpnTunnelRuntime.isForwarding
            if (!cleanupCompleted) {
                Logger.e { "Transport replacement cleanup remained in flight request=$requestId" }
            }
            cleanupCompleted
        }

    private data class TransportFailoverPreparation(
        val session: VpnRuntimeSession,
        val resolution: ConnectionPolicyResolution,
    )

    private suspend fun prepareTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
    ): TransportFailoverPreparation? {
        val session = currentTransportFailoverSession(requestId)
        val resolution = session?.let { resolveTransportFailoverPolicy(requestId, expectedTarget) }
        return if (
            session != null &&
            resolution != null &&
            transportFailoverApplyTracker.claimApplying(requestId)
        ) {
            TransportFailoverPreparation(session, resolution)
        } else {
            if (session != null && resolution != null) {
                Logger.i { "Ignoring stale transport failover request=$requestId before runtime mutation" }
            }
            null
        }
    }

    private fun currentTransportFailoverSession(requestId: Long): VpnRuntimeSession? {
        var session: VpnRuntimeSession? = null
        val currentSession = runtimeSession
        if (status != ServiceStatus.Connected || stopping || currentSession == null) {
            Logger.w { "Ignoring transport failover restart while VPN runtime is not connected" }
            transportFailoverApplyTracker.recordRollbackSafeFailure(requestId)
        } else {
            session = currentSession
        }
        return session
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveTransportFailoverPolicy(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
    ): ConnectionPolicyResolution? {
        val resolution =
            try {
                resolveInitialConnectionPolicy()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                transportFailoverApplyTracker.recordRollbackSafeFailure(requestId)
                Logger.w(error) { "Failed to resolve transport failover request=$requestId" }
                null
            }
        return if (resolution?.transportFailoverTargetOrNull() == expectedTarget) {
            resolution
        } else {
            resolution?.let {
                transportFailoverApplyTracker.recordRollbackSafeFailure(requestId)
                Logger.w { "Resolved policy does not match transport failover request=$requestId" }
            }
            null
        }
    }

    private suspend fun updateFailedStatusAfterRetainingProviderBarrier(
        failureReason: FailureReason?,
        lifecycleMutexHeld: Boolean = false,
    ) {
        val updateFailed =
            suspend {
                retainProviderFailClosedBarrierIfActiveLocked()
                updateStatus(ServiceStatus.Failed, failureReason)
            }
        if (lifecycleMutexHeld) {
            updateFailed()
        } else {
            mutex.withLock { updateFailed() }
        }
    }

    private suspend fun failAndStopAfterRetainingProviderBarrier(
        failureReason: FailureReason,
        guard: RuntimeStopGuard?,
        beforeFailureStatus: suspend () -> Unit,
    ): Boolean =
        failAndStopRuntime(
            failureReason = failureReason,
            guard = guard,
        ) {
            beforeFailureStatus()
            retainProviderFailClosedBarrierIfActiveLocked()
        }

    private suspend fun retainProviderFailClosedBarrierIfActiveLocked() {
        if (xrayProviderSessionController?.isActive != true) return
        val retained =
            withContext(NonCancellable) {
                runtimeCompositionCoordinator.retainFailClosedAfterHandoverFailure()
            }
        if (!retained && vpnTunnelRuntime.isForwarding) {
            Logger.e { "Failed to retain Xray fail-closed VPN barrier before status failure" }
        }
    }

    private fun updateStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        Logger.d { "VPN status: $status -> $newStatus" }
        if (newStatus == ServiceStatus.Failed) {
            runtimeSession?.revokeInPathLease()
        }
        status = newStatus
        statusReporter.reportStatus(
            newStatus = newStatus,
            activePolicy = runtimeSession?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
            currentNetworkHandoverState = currentNetworkHandoverState,
            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
            failureReason = failureReason,
            xrayProviderSnapshot = xrayProviderSessionController?.currentSnapshotOrNull(),
        )
    }

    private fun classifyStartupFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    private fun classifyHandoverFailure(error: Exception): FailureReason =
        classifyFailureReason(error, isTunnelContext = true)

    private fun onPermissionRevoked(event: PermissionChangeEvent) {
        when (event.kind) {
            PermissionChangeEvent.KIND_VPN_CONSENT -> {
                Logger.e { "VPN consent revoked while running" }
                updateStatus(ServiceStatus.Failed, FailureReason.PermissionLost("VPN"))
                host.serviceScope.launch(ioDispatcher) { stop() }
            }

            PermissionChangeEvent.KIND_NOTIFICATIONS -> {
                Logger.i { "Notification permission revoked while VPN running" }
            }
        }
    }

    private fun onAfterStopCleanup(session: VpnRuntimeSession?) {
        telemetryCoordinator.stopProtectFailureMonitoring()
        resolverOverrideStore.clear()
        runtimeCompositionCoordinator.resetAfterStop(session)
        rootHelperManager.stop()
    }
}

@Suppress("LongParameterList")
internal class VpnServiceRuntimeRuntimeDependencies
    @Inject
    constructor(
        val appSettingsRepository: AppSettingsRepository,
        val proxyGroupRepository: ProxyGroupRepository,
        val connectionPolicyResolver: ConnectionPolicyResolver,
        val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
        val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
        val resolverOverrideStore: ResolverOverrideStore,
        val serviceRuntimeRegistry: ServiceRuntimeRegistry,
        val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        val networkHandoverMonitor: NetworkHandoverMonitor,
        val policyHandoverEventStore: PolicyHandoverEventStore,
        val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        val dnsDependencies: VpnServiceRuntimeDnsDependencies,
        val upstreamRelaySupervisorFactory: UpstreamRelaySupervisorFactory,
        val warpRuntimeSupervisorFactory: WarpRuntimeSupervisorFactory,
        val amneziaWgRuntimeSupervisorFactory: AmneziaWgRuntimeSupervisorFactory,
        val proxyRuntimeSupervisorFactory: ProxyRuntimeSupervisorFactory,
        val screenStateObserver: ScreenStateObserver,
    )

internal class VpnServiceRuntimeDnsDependencies
    @Inject
    constructor(
        val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        val networkDnsBlockedPathStore: NetworkDnsBlockedPathStore,
        val resolverRefreshPlanner: VpnResolverRefreshPlanner,
    )

internal class VpnServiceRuntimeStatusDependencies
    @Inject
    constructor(
        val serviceStateStore: ServiceStateStore,
        val networkFingerprintProvider: NetworkFingerprintProvider,
        val telemetryFingerprintHasher: TelemetryFingerprintHasher,
        val serviceStatusReporterFactory: ServiceStatusReporterFactory,
    )
