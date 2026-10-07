package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.core.TunKernelIdentityNativeBindings
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxySettingsSection
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

private const val DefaultSocksListenerPort = 1080
private const val DefaultMixedInboundListenerPort = 2080
private const val NativeTunnelStartTimeoutMillis = 6_000L

internal data class VpnTunnelRuntimeCallbacks(
    val afterForwardingLeaseAcquired: suspend () -> Unit = {},
    val onTunnelReady: () -> Unit = {},
    val onTunnelTelemetry: (com.poyka.ripdpi.data.NativeRuntimeSnapshot) -> Unit = {},
)

internal class VpnRouteForwardingEvidenceSink(
    val generation: Long,
    private val receiptStore: VpnRouteLifecycleReceiptStore,
) {
    fun record(
        outcome: String,
        terminal: Boolean,
        revision: Long,
    ) {
        receiptStore.recordForwardingOutcome(generation, outcome, terminal, revision)
    }
}

internal data class VpnTunnelRuntimeEnvironment(
    val protectPath: String? = null,
    /**
     * Absolute `<filesDir>/lua` directory the native TUN egress strategy loader
     * jails `lua`-step `script_paths` to. `null` falls back to the process CWD.
     */
    val luaScriptBaseDir: String? = null,
    val rootHelperSocketPathProvider: () -> String? = { null },
    val geositeDbPath: String? = null,
    val tunIdentityReader: (Int) -> PrivateTunIdentity? =
        TunKernelIdentityReader(TunKernelIdentityNativeBindings())::read,
)

internal class VpnTunnelRuntime(
    private val vpnHost: VpnCoordinatorHost,
    private val appSettingsRepository: AppSettingsRepository,
    private val proxyGroupRepository: ProxyGroupRepository,
    private val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    private val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
    private val environment: VpnTunnelRuntimeEnvironment = VpnTunnelRuntimeEnvironment(),
    /**
     * Bridge the native tun2socks worker calls to report flow 5-tuples for per-app
     * attribution. `null` disables attribution (the tunnel still runs); passed
     * straight through to [Tun2SocksBridge.start].
     */
    private val flowAttributionBridge: FlowAttributionBridge? = null,
    private val nativeUidPolicyProvider: ((VpnAppRoutingPlan) -> NativeUidPolicy)? = null,
    private val callbacks: VpnTunnelRuntimeCallbacks = VpnTunnelRuntimeCallbacks(),
    private val appliedNetworkReceiptStore: VpnTunnelAppliedNetworkReceiptStore = VpnTunnelAppliedNetworkReceiptStore(),
    private val routeLifecycleReceiptStore: VpnRouteLifecycleReceiptStore = VpnRouteLifecycleReceiptStore(),
    private val pcapCaptureRuntimeController: PcapCaptureRuntimeController? = null,
    private val ownPackageName: String? = null,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    @Volatile
    private var tun2SocksBridge: Tun2SocksBridge? = null

    @Volatile
    private var retiringBridge: Tun2SocksBridge? = null

    @Volatile
    private var tunSession: VpnTunnelSession? = null

    @Volatile
    private var pendingSession: VpnTunnelSession? = null

    @Volatile
    private var retiringSession: VpnTunnelSession? = null
    private var activeRouteLifecycleGeneration: Long? = null
    private var pendingRouteLifecycleGeneration: Long? = null
    private val forwardingLease = AtomicReference<TunnelForwardingLease?>()
    private var tunnelStartCount: Int = 0
    private var currentProfileInterface: VpnProfileInterface? = null

    @Volatile
    var currentDnsSignature: String? = null
        private set

    @Volatile
    var currentInterfacePolicySignature: String? = null
        private set

    var tunnelRecoveryRetryCount: Long = 0
        private set

    val isRunning: Boolean
        get() = tunSession != null || retiringSession != null

    private var readyEvidence: RuntimeTunnelReadyEvidence? = null
    private var consumedConfigurationInput: VpnTunnelConfigurationInput? = null
    var consumedForceTunnelDns: Boolean = false
        private set
    val consumedProfileInterface: VpnProfileInterface? get() = currentProfileInterface

    fun requireReadyEvidence(): RuntimeTunnelReadyEvidence {
        check(isForwarding) { "TUN bridge is not ready" }
        return checkNotNull(readyEvidence)
    }

    val consumedConfiguration: VpnTunnelConfigurationInput
        get() {
            check(isForwarding) { "DNS refresh requires confirmed native forwarding" }
            return checkNotNull(consumedConfigurationInput)
        }

    val isForwarding: Boolean
        get() = tun2SocksBridge != null

    fun publishInPathLease(
        session: VpnRuntimeSession,
        endpoint: LocalProxyEndpoint,
    ) {
        val hasCredentials = !endpoint.username.isNullOrBlank() && !endpoint.password.isNullOrBlank()
        if (hasCredentials) {
            session.publishInPathLease(endpoint, checkNotNull(activeRouteLifecycleGeneration))
        } else {
            session.revokeInPathLease()
        }
    }

    val desiredInterfacePolicySignatures: Flow<String>
        get() =
            vpnHost
                .observeInstalledPackages()
                .map { installedPackages ->
                    val consumed = checkNotNull(consumedConfigurationInput)
                    vpnHost
                        .resolveTunnelInterfacePolicy(
                            consumed.settings,
                            consumed.packageRoutingRules,
                            installedPackages,
                        ).signature
                }.distinctUntilChanged()

    suspend fun captureConfigurationInput(): VpnTunnelConfigurationInput =
        VpnTunnelConfigurationInput(
            appSettingsRepository.snapshot(),
            proxyGroupRepository.list().flatMap {
                it.packageRoutingRules
            },
        )

    suspend fun requiresInterfacePolicyRebuild(): Boolean {
        val appliedSignature = currentInterfacePolicySignature
        if (appliedSignature == null || !isRunning) return false
        val desiredPolicy =
            vpnHost.resolveTunnelInterfacePolicy(
                settings = checkNotNull(consumedConfigurationInput).settings,
                packageRoutingRules = checkNotNull(consumedConfigurationInput).packageRoutingRules,
                installedPackages = vpnHost.currentInstalledPackages(),
            )
        return desiredPolicy.signature != appliedSignature
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun start(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean = false,
        splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy? = null,
        profileInterface: VpnProfileInterface? = null,
        configurationInput: VpnTunnelConfigurationInput,
    ) {
        check(tunSession == null) { "VPN field not null" }
        appliedNetworkReceiptStore.invalidate()

        val pendingTunnel =
            prepareTunnel(
                activeDns = activeDns,
                overrideReason = overrideReason,
                logContext = logContext,
                localProxyEndpoint = localProxyEndpoint,
                forceTunnelDns = forceTunnelDns,
                splitStrictDnsPolicy = splitStrictDnsPolicy,
                profileInterface = profileInterface,
                configurationInput = configurationInput,
            )
        // Builder.establish() can return before Android's route updates converge.
        // Retain ownership until native readiness or orchestrated Failed cleanup;
        // once routes are installed, this session blocks traffic if forwarding fails.
        // Closing it here would remove that barrier before Failed is published.
        pendingSession = pendingTunnel.session
        pendingRouteLifecycleGeneration = pendingTunnel.lifecycleGeneration
        startBridge(pendingTunnel, retainFailedBridge = false)
    }

    /**
     * Replaces an active Android VPN interface while retaining cleanup ownership.
     *
     * Android keeps the old interface when establishment fails. Successful establishment
     * deactivates it while route updates can still be pending, so retaining a descriptor
     * does not guarantee a zero-gap handover. This runtime retains the replacement even
     * if native startup fails; after route convergence it remains a traffic barrier.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun rebuild(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean = false,
        splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy? = null,
        profileInterface: VpnProfileInterface? = null,
        configurationInput: VpnTunnelConfigurationInput,
    ) {
        val previouslyRetiringSession = retiringSession
        if (previouslyRetiringSession != null) {
            previouslyRetiringSession.close()
            if (retiringSession === previouslyRetiringSession) retiringSession = null
        }
        val previousSession = checkNotNull(tunSession) { "VPN tunnel is not running" }
        check(tun2SocksBridge == null || retiringBridge == null || tun2SocksBridge === retiringBridge) {
            "VPN tunnel has multiple bridges pending retirement"
        }
        val previousBridge = tun2SocksBridge ?: retiringBridge
        val pendingTunnel =
            prepareTunnel(
                activeDns = activeDns,
                overrideReason = overrideReason,
                logContext = logContext,
                localProxyEndpoint = localProxyEndpoint,
                forceTunnelDns = forceTunnelDns,
                splitStrictDnsPolicy = splitStrictDnsPolicy,
                profileInterface = profileInterface,
                configurationInput = configurationInput,
            )

        // Own the replacement before retiring the old bridge, including failure paths.
        // Android routing may still be converging; descriptor ownership is not route evidence.
        tunSession = pendingTunnel.session
        activeRouteLifecycleGeneration = pendingTunnel.lifecycleGeneration
        forwardingLease.set(null)
        appliedNetworkReceiptStore.invalidate()
        tun2SocksBridge = null
        retiringBridge = null
        retiringSession = previousSession
        try {
            try {
                try {
                    retireAndStopBridge(previousBridge)
                } catch (error: Exception) {
                    retiringBridge = previousBridge
                    throw error
                }
            } finally {
                previousSession.close()
                if (retiringSession === previousSession) retiringSession = null
            }
        } catch (error: Exception) {
            routeLifecycleReceiptStore.markEnded(
                pendingTunnel.lifecycleGeneration,
                VpnRouteLifecycleState.FailClosed,
            )
            vpnHost.finishDirectDnsUnderlay(pendingTunnel.directDnsPrepareToken, DirectDnsUnderlayAction.FailClosed)
            throw error
        }
        startBridge(pendingTunnel, retainFailedBridge = true)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareTunnel(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean,
        splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy?,
        profileInterface: VpnProfileInterface?,
        configurationInput: VpnTunnelConfigurationInput,
    ): PendingTunnel {
        val settings = configurationInput.settings.withProfileInterface(profileInterface)
        val dnsPlan = vpnTunnelDnsPlan(activeDns, forceTunnelDns, splitStrictDnsPolicy)
        val directDnsPrepareToken =
            vpnHost.prepareDirectDnsUnderlay(
                splitStrictDnsPolicy?.directResolverCandidates.orEmpty(),
                splitStrictDnsPolicy?.underlayLeaseGeneration,
            )
        try {
            val tunnelNetworkParameters = vpnHost.profileTunnelNetworkParameters(profileInterface)
            val interfacePolicy =
                vpnHost.resolveTunnelInterfacePolicy(
                    settings = settings,
                    packageRoutingRules = configurationInput.packageRoutingRules,
                    installedPackages = vpnHost.currentInstalledPackages(),
                )
            val appRoutingPlan = interfacePolicy.appRoutingPlan
            val uidPolicy =
                nativeUidPolicyProvider?.invoke(appRoutingPlan)
                    ?: flowAttributionBridge?.nativeUidPolicy(appRoutingPlan)
                    ?: NativeUidPolicy.Disarmed
            val config =
                buildConsumedVpnTunConfig(
                    settings,
                    environment,
                    dnsPlan,
                    overrideReason,
                    localProxyEndpoint,
                    tunnelNetworkParameters.tunnelMtu,
                    logContext,
                    uidPolicy,
                )
            val (tunnelSession, lifecycleGeneration) =
                establishRouteObservedTunnel(
                    dns = dnsPlan.builderDnsAddress,
                    ipv6 = settings.ipv6Enable,
                    appRoutingPlan = appRoutingPlan,
                    httpProxyPort = interfacePolicy.httpProxyPort,
                    settings = settings,
                    networkParameters = tunnelNetworkParameters,
                    profileInterface = profileInterface,
                )
            return PendingTunnel(
                session =
                    GenerationBoundVpnTunnelSession(
                        delegate = tunnelSession,
                        lifecycleGeneration = lifecycleGeneration,
                        receiptStore = routeLifecycleReceiptStore,
                    ),
                lifecycleGeneration = lifecycleGeneration,
                config = config,
                directDnsPrepareToken = directDnsPrepareToken,
                dnsSignature =
                    dnsSignature(
                        activeDns,
                        overrideReason,
                        splitStrictDnsPolicy?.canonicalDigest.orEmpty(),
                        splitStrictDnsPolicy?.underlayLeaseGeneration,
                    ),
                interfacePolicySignature = interfacePolicy.signature,
                profileInterface = profileInterface,
                networkParameters = tunnelNetworkParameters,
                configurationInput = VpnTunnelConfigurationInput(settings, configurationInput.packageRoutingRules),
                forceTunnelDns = forceTunnelDns,
                resolverDns = dnsPlan.resolverDns,
                splitStrictDnsPolicy = splitStrictDnsPolicy,
            )
        } catch (error: Exception) {
            vpnHost.finishDirectDnsUnderlay(directDnsPrepareToken, DirectDnsUnderlayAction.Abort)
            throw error
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun establishRouteObservedTunnel(
        dns: String,
        ipv6: Boolean,
        appRoutingPlan: VpnAppRoutingPlan,
        httpProxyPort: Int?,
        settings: AppSettings,
        networkParameters: VpnTunnelNetworkParameters,
        profileInterface: VpnProfileInterface?,
    ): Pair<VpnTunnelSession, Long> {
        val generation =
            routeLifecycleReceiptStore.beginIntended(
                ipv6Enabled = ipv6,
                dns = dns,
                appRoutingPlan = appRoutingPlan,
                ownPackage = ownPackageName,
                networkParameters = networkParameters,
                apiLevel = sdkInt,
                profileInterface = profileInterface,
            )
        val session =
            try {
                vpnTunnelSessionProvider.establish(
                    host = vpnHost,
                    dns = dns,
                    ipv6 = ipv6,
                    appRoutingPlan = appRoutingPlan,
                    httpProxyPort = httpProxyPort,
                    interfaceSettings = settings,
                    networkParameters = networkParameters,
                    profileInterface = profileInterface,
                )
            } catch (error: Exception) {
                routeLifecycleReceiptStore.abortIntended(generation)
                throw error
            }
        routeLifecycleReceiptStore.markEstablished(generation, environment.tunIdentityReader(session.tunFd))
        return session to generation
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startBridge(
        pendingTunnel: PendingTunnel,
        retainFailedBridge: Boolean,
    ) {
        var createdBridge: Tun2SocksBridge? = null
        var startSucceeded = false
        try {
            val tunnelBridge = tun2SocksBridgeFactory.create()
            createdBridge = tunnelBridge
            flowAttributionBridge?.activateUidPolicy(
                NativeUidPolicy(
                    mode = pendingTunnel.config.uidPolicyMode,
                    uids = pendingTunnel.config.uidPolicyUids,
                ),
            )
            vpnHost.finishDirectDnsUnderlay(pendingTunnel.directDnsPrepareToken, DirectDnsUnderlayAction.Commit)
            withTimeout(NativeTunnelStartTimeoutMillis) {
                tunnelBridge.start(pendingTunnel.config, pendingTunnel.session.tunFd, flowAttributionBridge)
            }
            startSucceeded = true
            publishBridgeReady(pendingTunnel, tunnelBridge)
            consumedConfigurationInput = pendingTunnel.configurationInput
            consumedForceTunnelDns = pendingTunnel.forceTunnelDns
            readyEvidence =
                RuntimeTunnelReadyEvidence(
                    pendingTunnel.configurationInput,
                    pendingTunnel.forceTunnelDns,
                    pendingTunnel.resolverDns,
                    pendingTunnel.splitStrictDnsPolicy,
                    pendingTunnel.interfacePolicySignature,
                )
        } catch (error: Exception) {
            val rollback =
                createdBridge
                    ?.takeIf { startSucceeded }
                    ?.let { rollbackStartedBridge(it) }
                    ?: BridgeRollbackResult(forwardingStopped = true)
            if (rollback.forwardingStopped) {
                routeLifecycleReceiptStore.markEnded(
                    pendingTunnel.lifecycleGeneration,
                    VpnRouteLifecycleState.FailClosed,
                )
            }
            rollback.failure?.let(error::addSuppressed)
            flowAttributionBridge?.deactivateUidPolicy()
            vpnHost.finishDirectDnsUnderlay(pendingTunnel.directDnsPrepareToken, DirectDnsUnderlayAction.FailClosed)
            if (retainFailedBridge && startSucceeded && !rollback.forwardingStopped) {
                retiringBridge = createdBridge
            }
            throw error
        }
    }

    private suspend fun rollbackStartedBridge(tunnelBridge: Tun2SocksBridge): BridgeRollbackResult {
        forwardingLease.set(null)
        appliedNetworkReceiptStore.invalidate()
        if (tun2SocksBridge === tunnelBridge) tun2SocksBridge = null
        currentDnsSignature = null
        currentInterfacePolicySignature = null
        currentProfileInterface = null

        var rollbackFailure =
            runCatching {
                pcapCaptureRuntimeController?.retireTunnel(tunnelBridge)
            }.exceptionOrNull()
        var forwardingStopped = false
        val bridgeStopFailure =
            runCatching {
                tunnelBridge.stop()
                forwardingStopped = true
                retiringBridge = null
            }.exceptionOrNull()
        if (bridgeStopFailure != null) {
            rollbackFailure = rollbackFailure.combineCleanupFailure(bridgeStopFailure)
            retiringBridge = tunnelBridge
        }
        return BridgeRollbackResult(forwardingStopped = forwardingStopped, failure = rollbackFailure)
    }

    private suspend fun publishBridgeReady(
        pendingTunnel: PendingTunnel,
        tunnelBridge: Tun2SocksBridge,
    ) {
        tun2SocksBridge = tunnelBridge
        pcapCaptureRuntimeController?.bindTunnel(tunnelBridge)
        forwardingLease.set(TunnelForwardingLease(tunnelBridge))
        retiringBridge = null
        tunSession = pendingTunnel.session
        activeRouteLifecycleGeneration = pendingTunnel.lifecycleGeneration
        if (pendingSession === pendingTunnel.session) {
            pendingSession = null
            pendingRouteLifecycleGeneration = null
        }
        currentDnsSignature = pendingTunnel.dnsSignature
        currentInterfacePolicySignature = pendingTunnel.interfacePolicySignature
        currentProfileInterface = pendingTunnel.profileInterface
        val appliedReceipt = appliedNetworkReceiptStore.publish(pendingTunnel.networkParameters, sdkInt)
        routeLifecycleReceiptStore.markBridgeReady(
            generation = pendingTunnel.lifecycleGeneration,
            appliedTunnelReceiptGeneration = appliedReceipt.generation,
        )
        callbacks.onTunnelReady()
        if (tunnelStartCount > 0) {
            tunnelRecoveryRetryCount += 1
        }
        tunnelStartCount += 1
    }

    suspend fun stop() {
        val session = tunSession ?: pendingSession
        val oldSession = retiringSession
        if (session == null && oldSession == null) return
        appliedNetworkReceiptStore.invalidate()
        val activeBridge = tun2SocksBridge
        val inactiveBridge = retiringBridge

        forwardingLease.set(null)
        var stopFailure =
            runCatching {
                stopBridges(activeBridge, inactiveBridge)
            }.exceptionOrNull()
        tun2SocksBridge = null
        retiringBridge = null
        val sessionCloseFailure = session?.let { runCatching { it.close() }.exceptionOrNull() }
        if (sessionCloseFailure == null) {
            if (tunSession === session) {
                tunSession = null
                activeRouteLifecycleGeneration = null
            }
            if (pendingSession === session) {
                pendingSession = null
                pendingRouteLifecycleGeneration = null
            }
        }
        val oldSessionCloseFailure =
            oldSession
                ?.takeUnless { it === session }
                ?.let { runCatching { it.close() }.exceptionOrNull() }
        if (oldSessionCloseFailure == null && retiringSession === oldSession) retiringSession = null
        sessionCloseFailure?.let { failure ->
            stopFailure = stopFailure.combineCleanupFailure(failure)
        }
        oldSessionCloseFailure?.let { failure ->
            stopFailure = stopFailure.combineCleanupFailure(failure)
        }
        if (sessionCloseFailure != null || oldSessionCloseFailure != null) {
            (activeRouteLifecycleGeneration ?: pendingRouteLifecycleGeneration)?.let { generation ->
                routeLifecycleReceiptStore.markEnded(generation, VpnRouteLifecycleState.FailClosed)
            }
        }
        flowAttributionBridge?.deactivateUidPolicy()
        stopFailure?.let { throw it }
    }

    private suspend fun stopBridges(
        activeBridge: Tun2SocksBridge?,
        inactiveBridge: Tun2SocksBridge?,
    ) {
        try {
            retireAndStopBridge(activeBridge)
        } finally {
            if (inactiveBridge !== activeBridge) retireAndStopBridge(inactiveBridge)
        }
    }

    private suspend fun retireAndStopBridge(bridge: Tun2SocksBridge?) {
        bridge ?: return
        pcapCaptureRuntimeController?.retireTunnel(bridge)
        bridge.stop()
    }

    suspend fun pollTelemetry(): RuntimeTelemetryOutcome {
        val bridge = tun2SocksBridge ?: return RuntimeTelemetryOutcome.NoData
        return runCatching { bridge.telemetry() }
            .fold(
                onSuccess = {
                    callbacks.onTunnelTelemetry(it)
                    RuntimeTelemetryOutcome.Snapshot(it)
                },
                onFailure = { error ->
                    RuntimeTelemetryOutcome.EngineError(
                        message = error.message ?: "Tunnel telemetry polling failed",
                        causeClass = error.javaClass.name,
                    )
                },
            )
    }

    suspend fun pollTelemetryAndForwardingEvidence(): RuntimeTelemetryEvidencePoll<TunForwardingEvidence> {
        val lease =
            forwardingLease.get()
                ?: return RuntimeTelemetryEvidencePoll(
                    RuntimeTelemetryOutcome.NoData,
                    RuntimeForwardingEvidence.Unavailable,
                )
        callbacks.afterForwardingLeaseAcquired()
        val bridge = lease.bridge
        val telemetry =
            runCatching { bridge.telemetry() }
                .fold(
                    onSuccess = { snapshot ->
                        callbacks.onTunnelTelemetry(snapshot)
                        RuntimeTelemetryOutcome.Snapshot(snapshot)
                    },
                    onFailure = { error ->
                        if (error is CancellationException || error !is Exception) throw error
                        RuntimeTelemetryOutcome.EngineError(
                            message = error.message ?: "Tunnel telemetry polling failed",
                            causeClass = error.javaClass.name,
                        )
                    },
                )
        val evidence =
            try {
                RuntimeForwardingEvidence.Available(bridge.forwardingEvidence())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RuntimeForwardingEvidence.Unavailable
            }
        return RuntimeTelemetryEvidencePoll(
            telemetry = telemetry,
            forwardingEvidence =
                evidence.takeIf { forwardingLease.get() === lease }
                    ?: RuntimeForwardingEvidence.Unavailable,
        )
    }

    /** Stops native forwarding while deliberately retaining the installed TUN. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun retainFailClosedBarrier(): Boolean {
        val session = tunSession ?: pendingSession ?: return false
        val activeBridge = tun2SocksBridge
        val inactiveBridge = retiringBridge
        val bridges =
            buildList {
                activeBridge?.let(::add)
                if (inactiveBridge !== activeBridge) inactiveBridge?.let(::add)
            }
        forwardingLease.set(null)
        appliedNetworkReceiptStore.invalidate()
        (activeRouteLifecycleGeneration ?: pendingRouteLifecycleGeneration)?.let(
            { generation ->
                routeLifecycleReceiptStore.markEnded(generation, VpnRouteLifecycleState.FailClosed)
            },
        )
        tun2SocksBridge = null
        retiringBridge = null

        var stopFailure: Throwable? = null
        for (bridge in bridges) {
            try {
                pcapCaptureRuntimeController?.retireTunnel(bridge)
                bridge.stop()
            } catch (error: Exception) {
                retiringBridge = bridge
                if (stopFailure == null) {
                    stopFailure = error
                } else {
                    stopFailure.addSuppressed(error)
                }
            }
        }
        stopFailure?.let { throw it }
        flowAttributionBridge?.deactivateUidPolicy()
        tunSession = session
        return !isForwarding
    }

    suspend fun pollForwardingEvidence(): TunForwardingEvidence? {
        val lease = forwardingLease.get() ?: return null
        return try {
            lease.bridge.forwardingEvidence().takeIf { forwardingLease.get() === lease }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    val routeForwardingEvidenceSink: VpnRouteForwardingEvidenceSink?
        get() =
            activeRouteLifecycleGeneration?.let { generation ->
                VpnRouteForwardingEvidenceSink(generation, routeLifecycleReceiptStore)
            }

    fun resetRuntimeState() {
        appliedNetworkReceiptStore.invalidate()
        currentDnsSignature = null
        currentInterfacePolicySignature = null
        currentProfileInterface = null
        tunnelStartCount = 0
        tunnelRecoveryRetryCount = 0L
    }

    private data class PendingTunnel(
        val session: VpnTunnelSession,
        val lifecycleGeneration: Long,
        val config: Tun2SocksConfig,
        val directDnsPrepareToken: Long,
        val dnsSignature: String,
        val interfacePolicySignature: String,
        val profileInterface: VpnProfileInterface?,
        val networkParameters: VpnTunnelNetworkParameters,
        val configurationInput: VpnTunnelConfigurationInput,
        val forceTunnelDns: Boolean,
        val resolverDns: ActiveDnsSettings,
        val splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy?,
    )

    private data class BridgeRollbackResult(
        val forwardingStopped: Boolean,
        val failure: Throwable? = null,
    )
}

private class TunnelForwardingLease(
    val bridge: Tun2SocksBridge,
)

private class GenerationBoundVpnTunnelSession(
    private val delegate: VpnTunnelSession,
    private val lifecycleGeneration: Long,
    private val receiptStore: VpnRouteLifecycleReceiptStore,
) : VpnTunnelSession {
    override val tunFd: Int
        get() = delegate.tunFd

    override fun close() {
        delegate.close()
        receiptStore.markEnded(lifecycleGeneration, VpnRouteLifecycleState.Closed)
    }
}

private fun Throwable?.combineCleanupFailure(additionalFailure: Throwable): Throwable =
    this?.also { it.addSuppressed(additionalFailure) } ?: additionalFailure

/**
 * Returns the effective local listener port using the same resolution logic as
 * `buildListenConfig` in core/engine — the single source of truth for which port the
 * native core binds to. A positive [ProxySettingsSection.proxyPort] always wins; otherwise
 * the port defaults based on whether the mixed inbound listener is enabled.
 *
 * This mirrors the logic in `NativeProxyRuntimePreferencesMapper.buildListenConfig` without
 * importing that internal helper across module boundaries.
 */
internal fun effectiveListenerPort(proxy: ProxySettingsSection): Int =
    proxy.proxyPort.takeIf { it > 0 }
        ?: if (proxy.mixedInboundEnabled) DefaultMixedInboundListenerPort else DefaultSocksListenerPort

private fun AppSettings.withProfileInterface(profile: VpnProfileInterface?): AppSettings =
    profile?.let { toBuilder().setIpv6Enable(it.ipv6Enabled).build() } ?: this
