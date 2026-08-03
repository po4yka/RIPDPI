package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxySettingsSection
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

internal class VpnTunnelRuntime(
    private val vpnHost: VpnCoordinatorHost,
    private val appSettingsRepository: AppSettingsRepository,
    private val proxyGroupRepository: ProxyGroupRepository,
    private val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    private val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
    private val protectPath: String? = null,
    /**
     * Absolute `<filesDir>/lua` directory the native TUN egress strategy loader
     * jails `lua`-step `script_paths` to. `null` falls back to the process CWD
     * (`"."`); production supplies the app's absolute lua directory.
     */
    private val luaScriptBaseDir: String? = null,
    private val rootHelperSocketPathProvider: () -> String? = { null },
    /**
     * Bridge the native tun2socks worker calls to report flow 5-tuples for per-app
     * attribution. `null` disables attribution (the tunnel still runs); passed
     * straight through to [Tun2SocksBridge.start].
     */
    private val flowAttributionBridge: FlowAttributionBridge? = null,
    private val nativeUidPolicyProvider: ((VpnAppRoutingPlan) -> NativeUidPolicy)? = null,
    private val geositeDbPath: String? = null,
    private val callbacks: VpnTunnelRuntimeCallbacks = VpnTunnelRuntimeCallbacks(),
    private val appliedNetworkReceiptStore: VpnTunnelAppliedNetworkReceiptStore = VpnTunnelAppliedNetworkReceiptStore(),
    private val pcapCaptureRuntimeController: PcapCaptureRuntimeController? = null,
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
    private val forwardingLease = AtomicReference<TunnelForwardingLease?>()
    private var tunnelStartCount: Int = 0

    @Volatile
    var currentDnsSignature: String? = null
        private set

    @Volatile
    var currentInterfacePolicySignature: String? = null
        private set

    var tunnelRecoveryRetryCount: Long = 0
        private set

    val isRunning: Boolean
        get() = tunSession != null

    val isForwarding: Boolean
        get() = tun2SocksBridge != null

    fun desiredInterfacePolicySignatures(): Flow<String> =
        combine(
            appSettingsRepository.settings,
            proxyGroupRepository.groups(),
            vpnHost.observeInstalledPackages(),
        ) { settings, groups, installedPackages ->
            InterfacePolicyInput(settings, groups, installedPackages)
        }.map { input ->
            resolveInterfacePolicy(input.settings, input.groups, input.installedPackages).signature
        }.distinctUntilChanged()

    suspend fun requiresInterfacePolicyRebuild(): Boolean {
        val appliedSignature = currentInterfacePolicySignature ?: return false
        return isRunning && desiredInterfacePolicySignature() != appliedSignature
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun start(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean = false,
        splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy? = null,
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
            )
        // Builder.establish() has already installed Android's default routes.
        // Retain this session as a fail-closed barrier until native forwarding
        // reaches its readiness point or the orchestrated Failed cleanup closes
        // it. Closing it here would briefly restore direct routing before the
        // service can publish Failed.
        pendingSession = pendingTunnel.session
        startBridge(pendingTunnel, retainFailedBridge = false)
    }

    /**
     * Replaces an active Android VPN interface without exposing a direct-path gap.
     *
     * Android keeps the old interface active when establishing the replacement fails,
     * and switches routes to the replacement only after establishment succeeds. From
     * that point this runtime deliberately retains the replacement session even if the
     * native bridge cannot start, so the TUN remains a fail-closed traffic barrier.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun rebuild(
        activeDns: ActiveDnsSettings,
        overrideReason: String?,
        logContext: RipDpiLogContext?,
        localProxyEndpoint: LocalProxyEndpoint,
        forceTunnelDns: Boolean = false,
        splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy? = null,
    ) {
        val previousSession = checkNotNull(tunSession) { "VPN tunnel is not running" }
        check(tun2SocksBridge == null || retiringBridge == null || tun2SocksBridge === retiringBridge) {
            "VPN tunnel has multiple bridges pending retirement"
        }
        val previousBridge =
            checkNotNull(tun2SocksBridge ?: retiringBridge) {
                "VPN tunnel has no bridge to replace"
            }
        val pendingTunnel =
            prepareTunnel(
                activeDns = activeDns,
                overrideReason = overrideReason,
                logContext = logContext,
                localProxyEndpoint = localProxyEndpoint,
                forceTunnelDns = forceTunnelDns,
                splitStrictDnsPolicy = splitStrictDnsPolicy,
            )

        // Establishment has already moved Android routing to this replacement TUN.
        // Publish it before retiring the old bridge so every subsequent failure keeps
        // a live interface that captures traffic instead of falling back to direct.
        tunSession = pendingTunnel.session
        forwardingLease.set(null)
        appliedNetworkReceiptStore.invalidate()
        tun2SocksBridge = null
        retiringBridge = null
        try {
            try {
                try {
                    pcapCaptureRuntimeController?.retireTunnel(previousBridge)
                    previousBridge.stop()
                } catch (error: Exception) {
                    retiringBridge = previousBridge
                    throw error
                }
            } finally {
                previousSession.close()
            }
        } catch (error: Exception) {
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
    ): PendingTunnel {
        val settings = appSettingsRepository.snapshot()
        val dnsPlan = vpnTunnelDnsPlan(activeDns, forceTunnelDns, splitStrictDnsPolicy)
        val directDnsPrepareToken =
            vpnHost.prepareDirectDnsUnderlay(
                splitStrictDnsPolicy?.directResolverCandidates.orEmpty(),
                splitStrictDnsPolicy?.underlayLeaseGeneration,
            )
        try {
            val ipv6 = settings.ipv6Enable
            val tunnelNetworkParameters = vpnHost.currentTunnelNetworkParameters()
            val interfacePolicy =
                resolveInterfacePolicy(
                    settings = settings,
                    groups = proxyGroupRepository.list(),
                    installedPackages = vpnHost.currentInstalledPackages(),
                )
            val appRoutingPlan = interfacePolicy.appRoutingPlan
            val uidPolicy =
                nativeUidPolicyProvider?.invoke(appRoutingPlan)
                    ?: flowAttributionBridge?.nativeUidPolicy(appRoutingPlan)
                    ?: NativeUidPolicy.Disarmed
            val config =
                buildVpnTun2SocksConfig(
                    dnsPlan = dnsPlan,
                    overrideReason = overrideReason,
                    localProxyEndpoint = localProxyEndpoint,
                    ipv6Enabled = ipv6,
                    webrtcProtectionEnabled = settings.webrtcProtectionEnabled,
                    tunnelMtu = tunnelNetworkParameters.tunnelMtu,
                    logContext = logContext,
                    encryptedDnsTlsRootsPem = settings.encryptedDnsTlsRootsPem.takeIf { it.isNotBlank() },
                    strategyChainYaml = settings.strategyChainYaml.takeIf { it.isNotBlank() },
                    protectPath = protectPath,
                    rootHelperSocketPath = rootHelperSocketPathProvider().takeIf { settings.rootModeEnabled },
                    luaScriptBaseDir = luaScriptBaseDir,
                    uidPolicy = uidPolicy,
                    geositeDbPath = geositeDbPath,
                )
            val tunnelSession =
                vpnTunnelSessionProvider.establish(
                    host = vpnHost,
                    dns = dnsPlan.builderDnsAddress,
                    ipv6 = ipv6,
                    appRoutingPlan = appRoutingPlan,
                    httpProxyPort = interfacePolicy.httpProxyPort,
                    interfaceSettings = settings,
                    networkParameters = tunnelNetworkParameters,
                )
            return PendingTunnel(
                session = tunnelSession,
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
                networkParameters = tunnelNetworkParameters,
            )
        } catch (error: Exception) {
            vpnHost.finishDirectDnsUnderlay(directDnsPrepareToken, DirectDnsUnderlayAction.Abort)
            throw error
        }
    }

    private suspend fun desiredInterfacePolicySignature(): String {
        val settings = appSettingsRepository.snapshot()
        return resolveInterfacePolicy(
            settings = settings,
            groups = proxyGroupRepository.list(),
            installedPackages = vpnHost.currentInstalledPackages(),
        ).signature
    }

    private suspend fun resolveInterfacePolicy(
        settings: AppSettings,
        groups: List<ProxyGroup>,
        installedPackages: Set<String>,
    ): ResolvedVpnInterfacePolicy {
        val packageRoutingRules = groups.flatMap { it.packageRoutingRules }
        val appRoutingPlan = vpnHost.resolveAppRoutingPlan(settings, packageRoutingRules, installedPackages)
        val proxy = settings.toSettingsSections().proxy
        val httpProxyPort =
            if (proxy.appendHttpProxy) effectiveListenerPort(proxy) else null
        return ResolvedVpnInterfacePolicy(
            appRoutingPlan = appRoutingPlan,
            httpProxyPort = httpProxyPort,
            signature = vpnTunnelInterfacePolicySignature(settings, appRoutingPlan, httpProxyPort),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startBridge(
        pendingTunnel: PendingTunnel,
        retainFailedBridge: Boolean,
    ) {
        val tunnelBridge = tun2SocksBridgeFactory.create()
        flowAttributionBridge?.activateUidPolicy(
            NativeUidPolicy(
                mode = pendingTunnel.config.uidPolicyMode,
                uids = pendingTunnel.config.uidPolicyUids,
            ),
        )
        try {
            vpnHost.finishDirectDnsUnderlay(pendingTunnel.directDnsPrepareToken, DirectDnsUnderlayAction.Commit)
            withTimeout(NativeTunnelStartTimeoutMillis) {
                tunnelBridge.start(pendingTunnel.config, pendingTunnel.session.tunFd, flowAttributionBridge)
            }
        } catch (error: Exception) {
            flowAttributionBridge?.deactivateUidPolicy()
            vpnHost.finishDirectDnsUnderlay(pendingTunnel.directDnsPrepareToken, DirectDnsUnderlayAction.FailClosed)
            if (retainFailedBridge) retiringBridge = tunnelBridge
            throw error
        }
        tun2SocksBridge = tunnelBridge
        pcapCaptureRuntimeController?.bindTunnel(tunnelBridge)
        forwardingLease.set(TunnelForwardingLease(tunnelBridge))
        retiringBridge = null
        tunSession = pendingTunnel.session
        if (pendingSession === pendingTunnel.session) {
            pendingSession = null
        }
        currentDnsSignature = pendingTunnel.dnsSignature
        currentInterfacePolicySignature = pendingTunnel.interfacePolicySignature
        appliedNetworkReceiptStore.publish(pendingTunnel.networkParameters, sdkInt)
        if (tunnelStartCount > 0) {
            tunnelRecoveryRetryCount += 1
        }
        tunnelStartCount += 1
        callbacks.onTunnelReady()
    }

    suspend fun stop() {
        val session = tunSession ?: pendingSession ?: return
        appliedNetworkReceiptStore.invalidate()
        val activeBridge = tun2SocksBridge
        val inactiveBridge = retiringBridge

        forwardingLease.set(null)
        try {
            activeBridge?.let { pcapCaptureRuntimeController?.retireTunnel(it) }
            activeBridge?.stop()
        } finally {
            try {
                if (inactiveBridge !== activeBridge) {
                    inactiveBridge?.let { pcapCaptureRuntimeController?.retireTunnel(it) }
                }
                if (inactiveBridge !== activeBridge) inactiveBridge?.stop()
            } finally {
                tun2SocksBridge = null
                retiringBridge = null
                session.close()
                tunSession = null
                pendingSession = null
                flowAttributionBridge?.deactivateUidPolicy()
            }
        }
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

    fun resetRuntimeState() {
        appliedNetworkReceiptStore.invalidate()
        currentDnsSignature = null
        currentInterfacePolicySignature = null
        tunnelStartCount = 0
        tunnelRecoveryRetryCount = 0L
    }

    private data class PendingTunnel(
        val session: VpnTunnelSession,
        val config: Tun2SocksConfig,
        val directDnsPrepareToken: Long,
        val dnsSignature: String,
        val interfacePolicySignature: String,
        val networkParameters: VpnTunnelNetworkParameters,
    )

    private data class InterfacePolicyInput(
        val settings: AppSettings,
        val groups: List<ProxyGroup>,
        val installedPackages: Set<String>,
    )

    private data class ResolvedVpnInterfacePolicy(
        val appRoutingPlan: VpnAppRoutingPlan,
        val httpProxyPort: Int?,
        val signature: String,
    )
}

private class TunnelForwardingLease(
    val bridge: Tun2SocksBridge,
)

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
