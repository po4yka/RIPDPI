package com.poyka.ripdpi.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.os.Build
import androidx.annotation.Keep
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DeviceRuntimeForegroundCallKind
import com.poyka.ripdpi.data.DeviceRuntimeForegroundServiceType
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.proto.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.Optional
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

/**
 * VPN-mode foreground `VpnService` — the Android entry point for VPN mode.
 * Hosts the VPN session lifecycle, the foreground notification, underlying-
 * network binding, and `onRevoke`; runtime orchestration is delegated to
 * `VpnServiceRuntimeCoordinator`. Lifecycle-callback behavior is frozen — see
 * this module's `README.md`.
 */
@AndroidEntryPoint
class RipDpiVpnService :
    LifecycleVpnService(),
    VpnCoordinatorHost {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    lateinit var vpnAppExclusionPolicy: VpnAppExclusionPolicy

    @Inject
    lateinit var installedPackagesProvider: InstalledPackagesProvider

    @Inject
    lateinit var vpnDhtMitigationPolicy: VpnDhtMitigationPolicy

    @Inject
    lateinit var rootHelperManagerProvider: Provider<RootHelperManager>
    private val rootHelperManager get() = rootHelperManagerProvider.get()

    @Inject
    lateinit var hardKillSwitchStateStore: AndroidHardKillSwitchStateStore

    @Inject
    lateinit var runtimeEvidenceReporter: AndroidRuntimeEvidenceReporter

    @Inject
    internal lateinit var serviceStopProvenanceRecorder: RoomServiceStopProvenanceRecorder

    @Inject
    internal lateinit var sessionComponentBuilderProvider: Provider<VpnServiceSessionComponentBuilder>

    @Inject
    internal lateinit var activeProtectSocketPathProvider: ActiveProtectSocketPathProvider

    @Inject
    lateinit var runtimeResumeIntentTracker: RuntimeResumeIntentTracker

    @Inject
    lateinit var serviceIntentArbiter: ServiceIntentArbiter

    @Inject
    lateinit var acceptedUserStopRecorder: AcceptedUserStopRecorder

    @Inject
    lateinit var profileMutationCoordinator: ProfileMutationCoordinator

    @Inject
    lateinit var explicitUserStartPreparer: Optional<ExplicitUserStartPreparer>

    @Inject
    lateinit var serviceRecoveryStartGate: Optional<ServiceRecoveryStartGate>

    @Inject
    internal lateinit var recoveryReceiptCollector: RemoteDeviceRecoveryReceiptCollector

    @Inject
    internal lateinit var directDnsUnderlayAuthority: DirectDnsUnderlayAuthority

    @Inject
    internal lateinit var vpnRouteObservationAuthority: VpnRouteObservationAuthority

    @Inject
    internal lateinit var transportFailoverApplyTracker: TransportFailoverApplyTracker

    @Inject
    lateinit var selectorRuntimeLifecycleListenersProvider:
        Provider<Set<@JvmSuppressWildcards com.poyka.ripdpi.services.selector.SelectorRuntimeLifecycleListener>>
    private val selectorRuntimeLifecycleListeners get() = selectorRuntimeLifecycleListenersProvider.get()

    @Inject lateinit var pauseController: TimedPauseController

    @Inject lateinit var pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority

    private val activeOwnership = ActiveSessionOwnership()
    private val activeSessionAttached get() = activeOwnership.hasOwnership

    @Volatile private var lastPausedStartId = 0

    @Volatile private var latestStartId = 0
    private val entryMutex = kotlinx.coroutines.sync.Mutex()
    private var destroyCleanupJob: kotlinx.coroutines.Job? = null

    private lateinit var sessionLifecycle: VpnServiceSessionLifecycle
    private lateinit var shellDelegate: ServiceShellDelegate
    private lateinit var notificationController: VpnForegroundNotificationController
    private lateinit var recoveryUserUnlockReceiver: RecoveryUserUnlockReceiverLifecycle
    internal val recoveryServiceInstanceId: String = UUID.randomUUID().toString()

    @Volatile
    internal var activeRecoveryGeneration: String? = null
    internal lateinit var underlyingNetworkBinder: VpnUnderlyingNetworkBinder
    private val connectivityManager: ConnectivityManager
        get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    override val serviceScope = lifecycleScope

    @Inject lateinit var liveVpnLockdownReader: LiveVpnLockdownReader

    private val runtimeCommands = RuntimeCommands()

    override fun onCreate() {
        super.onCreate()
        runtimeEvidenceReporter.recordLifecycle(Mode.VPN, DeviceRuntimeLifecyclePhase.Created)
        notificationController = VpnForegroundNotificationController(serviceStateStore)
        recoveryUserUnlockReceiver =
            RecoveryUserUnlockReceiverLifecycle(
                context = this,
                generationProvider = { activeRecoveryGeneration },
                onUnlocked = recoveryReceiptCollector::recordUserUnlocked,
            )
        notificationController.registerChannel(this)
        refreshHardKillSwitchState()
        liveVpnLockdownReader.register(this, ::refreshHardKillSwitchState)
        pauseController.attach(pauseHost)
    }

    private suspend fun ensureActiveSession() {
        if (activeOwnership.attached) return
        runCatching {
            activeOwnership.attach {
                activeOwnership.own { if (::underlyingNetworkBinder.isInitialized) underlyingNetworkBinder.stop() }
                activeOwnership.own { vpnRouteObservationAuthority.stop() }
                activeOwnership.own { if (::sessionLifecycle.isInitialized) sessionLifecycle.destroy() }
                activeOwnership.own { if (::shellDelegate.isInitialized) shellDelegate.close() }

                underlyingNetworkBinder = VpnUnderlyingNetworkBinder(this, directDnsUnderlayAuthority)
                underlyingNetworkBinder.start()
                vpnRouteObservationAuthority.start()
                sessionLifecycle =
                    VpnServiceSessionLifecycle(
                        service = this,
                        sessionComponentBuilderProvider = sessionComponentBuilderProvider,
                        activeProtectSocketPathProvider = activeProtectSocketPathProvider,
                        runtimeResumeIntentTracker = runtimeResumeIntentTracker,
                        serviceIntentArbiter = serviceIntentArbiter,
                        acceptedUserStopRecorder = acceptedUserStopRecorder,
                        transportFailoverApplyTracker = transportFailoverApplyTracker,
                        serviceStopProvenanceRecorder = serviceStopProvenanceRecorder,
                        beforeUserStart = { guard ->
                            explicitUserStartPreparer.orElse(null)?.prepare(Mode.VPN, guard)
                        },
                        awaitStartupReadiness = {
                            serviceRecoveryStartGate.orElse(null)?.awaitReady() ?: true
                        },
                        recoverProfileMutations = profileMutationCoordinator::recover,
                        awaitRecoveryUnderlay = underlyingNetworkBinder::awaitEligibleUnderlay,
                    )
                shellDelegate = sessionLifecycle.createShellDelegate()
                val ownedRootHelper = rootHelperManager
                activeOwnership.own { ownedRootHelper.stopOnDestroy() }
                // Start the selector-runtime loops (member hot-reload + latency failover)
                // for the lifetime of the service. Each listener is idempotent.
                selectorRuntimeLifecycleListeners.forEach { listener ->
                    activeOwnership.own { listener.stop(Mode.VPN) }
                    listener.start(Mode.VPN)
                }
            }
        }.onFailure { failure ->
            if (failure !is Exception) throw failure
            if (activeOwnership.hasOwnership) pauseController.retainPartialCleanup(pauseHost)
        }.getOrThrow()
    }

    /** Called only under entryMutex; native and ancillary ownership must both be released. */
    private val pauseHost by lazy {
        PausedServiceHost(
            identity = this,
            mode = Mode.VPN,
            scope = serviceScope,
            release = { intent ->
                entryMutex.withLock {
                    runtimeCommands.releaseActiveSession(
                        RuntimeStopGuard(isCurrent = { pauseAuthority.isCurrent(intent) }),
                    )
                }
            },
            resume = { intent ->
                entryMutex.withLock {
                    if (pauseAuthority.isCurrent(intent)) {
                        ensureActiveSession()
                        sessionLifecycle.startForPauseResume()
                    }
                }
            },
            showPaused = { intent ->
                lastPausedStartId = latestStartId
                startPausedForeground(this, intent, "RIPDPIVpn", 1)
            },
            stopShell = { lease ->
                entryMutex.withLock {
                    val guard = RuntimeStopGuard(isCurrent = { serviceIntentArbiter.isCurrent(lease) })
                    val outcome = runtimeCommands.releaseActiveSession(guard)
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
        liveVpnLockdownReader.unregister(this)
        recoveryUserUnlockReceiver.close()
        recoveryReceiptCollector.cancelServiceInstance(recoveryServiceInstanceId)
        activeRecoveryGeneration = null
        runtimeEvidenceReporter.recordLifecycle(Mode.VPN, DeviceRuntimeLifecyclePhase.Destroyed)
        pauseController.detach(this)
        destroyCleanupJob =
            kotlinx.coroutines
                .CoroutineScope(
                    kotlinx.coroutines.Dispatchers.IO,
                ).launch(kotlinx.coroutines.NonCancellable) {
                    entryMutex.withLock {
                        if (::sessionLifecycle.isInitialized) sessionLifecycle.closeRefreshReceiver()
                        val outcome = runtimeCommands.releaseActiveSession(RuntimeStopGuard(isCurrent = { true }))
                        if (outcome != RuntimeStopOutcome.FullyReleased) {
                            co.touchlab.kermit.Logger
                                .e { "VPN destruction retained cleanup ownership" }
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
        notificationController.startForeground(this)
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                entryMutex.withLock {
                    profileMutationCoordinator.recover()
                    if (!pauseController.handleShellStart(
                            pauseHost,
                            intent,
                        )
                    ) {
                        runtimeCommands.dispatchAfterRecovery(intent, startId)
                    }
                }
            }.onFailure { failure ->
                if (failure !is Exception || failure is kotlinx.coroutines.CancellationException) throw failure
                co.touchlab.kermit.Logger
                    .e { "VPN recovery prerequisite failed: ${failure::class.java.simpleName}" }
                if (activeOwnership.hasOwnership) {
                    pauseController.retainPartialCleanup(pauseHost)
                } else {
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        refreshHardKillSwitchState()
        if (activeSessionAttached) {
            shellDelegate.onRevoke()
        } else {
            pauseAuthority.snapshot()?.let {
                pauseAuthority.transition(
                    it,
                    com.poyka.ripdpi.data.PausePhase.Deferred,
                    com.poyka.ripdpi.data.PauseFailure.ConsentRequired,
                )
            }
        }
    }

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) = notificationController.update(this, tunnelStats, proxyTelemetry)

    internal fun refreshForegroundNotification() {
        runtimeEvidenceReporter.runForegroundCall(
            Mode.VPN,
            DeviceRuntimeForegroundCallKind.Refresh,
            DeviceRuntimeForegroundServiceType.SpecialUse,
        ) {
            notificationController.startForeground(this)
        }
    }

    override fun requestStopSelf(stopSelfStartId: Int?) {
        requestStopSelfWithFallback(
            stopSelfStartId = stopSelfStartId,
            stopSelfResult = ::stopSelfResult,
            stopSelf = ::stopSelf,
        )
    }

    override suspend fun resolveAppRoutingPlan(
        settings: AppSettings,
        packageRoutingRules: Collection<PackageRoutingRule>,
        installedPackages: Set<String>,
    ): VpnAppRoutingPlan =
        vpnAppExclusionPolicy.appRoutingPlan(
            ownPackage = applicationContext.packageName,
            settings = settings,
            packageRoutingRules = packageRoutingRules,
            installedPackages = installedPackages,
        )

    override fun currentInstalledPackages(): Set<String> = installedPackagesProvider.installedPackages()

    override fun observeInstalledPackages(): Flow<Set<String>> = installedPackagesProvider.observeInstalledPackages()

    override suspend fun createTunnelBuilder(
        dns: String,
        ipv6: Boolean,
        appRoutingPlan: VpnAppRoutingPlan,
        interfaceSettings: AppSettings,
        httpProxyPort: Int?,
        networkParameters: VpnTunnelNetworkParameters,
        profileInterface: VpnProfileInterface?,
    ): VpnTunnelBuilder =
        AndroidVpnTunnelBuilder(
            builder =
                createBuilder(
                    dns,
                    ipv6,
                    appRoutingPlan,
                    interfaceSettings,
                    httpProxyPort,
                    networkParameters,
                    profileInterface,
                ),
        )

    private var preparedDirectDnsUnderlayToken: Long = 0L

    @android.annotation.SuppressLint("MissingPermission")
    override fun prepareDirectDnsUnderlay(
        candidates: List<String>,
        leaseGeneration: Long?,
    ): Long {
        refreshHardKillSwitchState()
        return underlyingNetworkBinder.preparePolicy(candidates, leaseGeneration).also { token ->
            preparedDirectDnsUnderlayToken = token
        }
    }

    override fun finishDirectDnsUnderlay(
        token: Long,
        action: DirectDnsUnderlayAction,
    ): Boolean =
        when (action) {
            DirectDnsUnderlayAction.Commit -> {
                underlyingNetworkBinder.commitPreparedLease(token)
            }

            DirectDnsUnderlayAction.Abort -> {
                underlyingNetworkBinder.abortPreparedLease(token)
                false
            }

            DirectDnsUnderlayAction.FailClosed -> {
                underlyingNetworkBinder.failClosedPreparedLease(token)
                false
            }
        }

    /** Resolve bootstrap hostnames on the explicitly selected underlying network. */
    @Keep
    fun resolveHost(host: String): Array<String> = underlyingNetworkBinder.resolveHost(host)

    internal suspend fun createBuilder(
        dns: String,
        ipv6: Boolean,
        appRoutingPlan: VpnAppRoutingPlan,
        interfaceSettings: AppSettings,
        httpProxyPort: Int? = null,
        networkParameters: VpnTunnelNetworkParameters = currentTunnelNetworkParameters(),
        profileInterface: VpnProfileInterface? = null,
    ): Builder {
        Logger.v { "DNS configured" }
        val builder = Builder()
        underlyingNetworkBinder.applyToBuilder(builder, preparedDirectDnsUnderlayToken)
        builder.setSession("RIPDPI")
        builder.setMtu(networkParameters.tunnelMtu)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        builder.applyTunnelRoutePlan(profileInterface?.routePlan(dns) ?: vpnTunnelRoutePlan(ipv6))

        val dnsServers = profileInterface?.dnsServers?.takeIf { it.isNotEmpty() } ?: listOf(dns)
        dnsServers.filter(String::isNotBlank).forEach(builder::addDnsServer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(networkParameters.metered)
            if (httpProxyPort != null) {
                builder.setHttpProxy(buildHttpProxyInfo(httpProxyPort))
            }
        }

        // Android forbids mixing addAllowedApplication and addDisallowedApplication on the same
        // Builder, so the policy returns exactly one shape. The caller passes the same immutable
        // plan to this builder and the native UID guard so they cannot observe different generations.
        when (val plan = appRoutingPlan) {
            is VpnAppRoutingPlan.Disallow -> {
                plan.packages.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        // App not installed, skip silently
                    }
                }
            }

            is VpnAppRoutingPlan.AllowOnly -> {
                plan.packages.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        // App not installed, skip silently
                    }
                }
            }
        }

        runtimeCommands.applyDhtMitigation(builder, interfaceSettings)
        refreshHardKillSwitchState()
        return builder
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun currentTunnelNetworkParameters(): VpnTunnelNetworkParameters {
        val network = connectivityManager.activeNetwork
        val linkProperties = network?.let(connectivityManager::getLinkProperties)
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        return VpnTunnelNetworkPolicy.parameters(linkProperties, capabilities)
    }

    internal fun isUserStopAllowed(action: String): Boolean = refreshHardKillSwitchState().allowsServiceStop(action)

    internal fun refreshHardKillSwitchState(): AndroidHardKillSwitchSnapshot {
        val snapshot = AndroidHardKillSwitchStateReader.read(this)
        hardKillSwitchStateStore.update(snapshot)
        runtimeEvidenceReporter.recordVpnPolicy(snapshot)
        return snapshot
    }

    /** Native session commands retain access only to their owning service instance. */
    private inner class RuntimeCommands {
        suspend fun releaseActiveSession(guard: RuntimeStopGuard): RuntimeStopOutcome =
            when {
                !guard.isCurrent() -> {
                    RuntimeStopOutcome.Superseded
                }

                !activeSessionAttached -> {
                    RuntimeStopOutcome.FullyReleased
                }

                !activeOwnership.attached -> {
                    activeOwnership.release()
                }

                else -> {
                    val result = sessionLifecycle.releaseRetainingShell(guard)
                    val outcome = if (result == RuntimeStopOutcome.FullyReleased) activeOwnership.release() else result
                    outcome
                }
            }

        suspend fun dispatchAfterRecovery(
            intent: Intent?,
            startId: Int,
        ): Int {
            val action = intent?.action
            if (action == null || action == android.net.VpnService.SERVICE_INTERFACE) {
                return dispatchStickyRecovery(startId)
            }
            return when {
                intent.hasStaleServiceCommand(serviceIntentArbiter) -> {
                    START_STICKY
                }

                action == TimedPauseController.PauseRestoreAction ||
                    (isUserServiceStopAction(action) && !activeSessionAttached) -> {
                    discardIdleStart(startId)
                }

                isServiceRecoveryStartAction(action) &&
                    intent.capturedRuntimeActivation(pauseAuthority, Mode.VPN) == null -> {
                    discardIdleStart(startId)
                }

                (action == com.poyka.ripdpi.data.startAction || action == transportActivationStartAction) &&
                    intent.capturedRuntimeActivation(pauseAuthority, Mode.VPN) == null -> {
                    discardIdleStart(startId)
                }

                else -> {
                    dispatchActiveCommand(intent, startId)
                }
            }
        }

        private suspend fun dispatchStickyRecovery(startId: Int): Int {
            if (activeSessionAttached) return START_STICKY
            val recovery =
                createStickyRecoveryCommand(
                    this@RipDpiVpnService,
                    pauseAuthority,
                    serviceIntentArbiter,
                    Mode.VPN,
                )
            return if (recovery == null) discardIdleStart(startId) else dispatchActiveCommand(recovery, startId)
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
            val startAction = intent?.action
            val recoveryGeneration =
                if (isRecoveryReceiptStartAction(startAction)) {
                    recoveryReceiptCollector.beginStart(
                        action = startAction,
                        serviceInstanceId = recoveryServiceInstanceId,
                    )
                } else {
                    null
                }
            activeRecoveryGeneration = recoveryGeneration ?: activeRecoveryGeneration
            runtimeEvidenceReporter.recordLifecycle(Mode.VPN, DeviceRuntimeLifecyclePhase.StartCommand)
            runtimeEvidenceReporter.runForegroundCall(
                Mode.VPN,
                DeviceRuntimeForegroundCallKind.Initial,
                DeviceRuntimeForegroundServiceType.SpecialUse,
            ) {
                notificationController.startForeground(this@RipDpiVpnService)
            }
            val policy = refreshHardKillSwitchState()
            if (recoveryGeneration != null) {
                val userUnlocked =
                    runCatching {
                        getSystemService(android.os.UserManager::class.java)?.isUserUnlocked
                    }.getOrNull()
                recoveryReceiptCollector.recordForegroundService(
                    generation = recoveryGeneration,
                    userUnlocked = userUnlocked,
                    policy = policy,
                )
                recoveryUserUnlockReceiver.observeIfLocked(userUnlocked)
            }
            // A null action is Android re-delivering a START_STICKY intent after the
            // process was killed (LMK / memory limiter). Publish Reconnecting ONLY from
            // a Halted baseline — i.e. a genuinely fresh process whose store re-init'd to
            // Halted. Guarding on Halted is load-bearing: a null re-delivery to a process
            // whose service is still Running must not demote it to Reconnecting (which
            // would also wipe serviceStartedAt), because the runtime start that follows
            // is then rejected as already-running and would never restore Running —
            // leaving the status stuck. Reconnecting is overwritten by Running on connect
            // or Halted if the resume fails.
            val sessionStateStore = sessionLifecycle.stateStore
            if (isServiceRecoveryStartAction(intent?.action) &&
                sessionStateStore.status.value.first == AppStatus.Halted
            ) {
                sessionStateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
            }
            val transportFailoverCommand = intent.decodeTransportFailoverCommand()
            return shellDelegate.onStartCommand(
                action = intent?.action,
                startId = startId,
                transportFailoverRequestId = transportFailoverCommand.requestId,
                transportFailoverTarget = transportFailoverCommand.target,
                explicitUserIntentGeneration = intent.explicitUserIntentGeneration(),
                durableReference = intent.durableAuthorityReference(),
                activation = intent.capturedRuntimeActivation(pauseAuthority, Mode.VPN),
                stopSnapshot = intent.capturedRuntimeStop(),
                vpnStartGeneration = intent.vpnStartGeneration(),
            )
        }

        fun applyDhtMitigation(
            builder: Builder,
            settings: AppSettings,
        ) {
            val supportsRouteExclusion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val plan =
                vpnDhtMitigationPolicy.buildPlan(
                    settings = settings,
                    supportsRouteExclusion = supportsRouteExclusion,
                )

            if (supportsRouteExclusion) {
                plan.excludedRoutes.forEach { route ->
                    runCatching {
                        builder.excludeRoute(
                            IpPrefix(java.net.InetAddress.getByName(route.address), route.prefixLength),
                        )
                    }.onFailure { error ->
                        Logger.w(error) {
                            "Failed to exclude DHT trigger route ${route.address}/${route.prefixLength}"
                        }
                    }
                }
            }

            plan.warningMessage?.let { warning ->
                Logger.w { warning }
            }
        }
    }

    private class AndroidVpnTunnelBuilder(
        private val builder: Builder,
    ) : VpnTunnelBuilder {
        override fun establish(): VpnTunnelSession? = builder.establish()?.let(::ParcelFileDescriptorVpnTunnelSession)
    }

    companion object {
        private const val TunnelIpv4PrefixLen = 32
        private const val TunnelIpv6PrefixLen = 128
        private const val TUNNEL_IPV4_ADDRESS = "10.10.10.10"
        private const val TUNNEL_IPV6_ADDRESS = "fd00::1"

        /** Loopback hosts/addresses excluded from the advertised HTTP proxy. */
        internal val httpProxyExclusionList: List<String> = listOf("localhost", "127.0.0.1", "::1")

        /**
         * Builds the [android.net.ProxyInfo] to advertise via
         * [android.net.VpnService.Builder.setHttpProxy] on Android Q+.
         * Extracted for unit-testability (no [android.net.VpnService] context needed).
         */
        @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
        internal fun buildHttpProxyInfo(port: Int): android.net.ProxyInfo =
            android.net.ProxyInfo.buildDirectProxy("127.0.0.1", port, httpProxyExclusionList)

        internal fun vpnTunnelRoutePlan(ipv6Enabled: Boolean): VpnTunnelRoutePlan =
            VpnTunnelRoutePlan(
                addresses =
                    buildList {
                        add(VpnTunnelRouteEntry(TUNNEL_IPV4_ADDRESS, TunnelIpv4PrefixLen))
                        if (ipv6Enabled) {
                            add(VpnTunnelRouteEntry(TUNNEL_IPV6_ADDRESS, TunnelIpv6PrefixLen))
                        }
                    },
                routes =
                    buildList {
                        add(VpnTunnelRouteEntry("0.0.0.0", 0))
                        if (ipv6Enabled) {
                            add(VpnTunnelRouteEntry("::", 0))
                        }
                    },
            )
    }
}

internal data class DecodedTransportFailoverCommand(
    val requestId: Long?,
    val target: TransportFailoverTarget?,
)

internal fun Intent?.decodeTransportFailoverCommand(): DecodedTransportFailoverCommand {
    val requestId =
        runCatching {
            this
                ?.takeIf { it.hasExtra(transportFailoverRequestIdExtra) }
                ?.getLongExtra(transportFailoverRequestIdExtra, 0L)
                ?.takeIf { it > 0L }
        }.getOrNull()
    val transportKind =
        runCatching { this?.getStringExtra(transportFailoverTargetKindExtra) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    val profileId =
        runCatching { this?.getStringExtra(transportFailoverTargetProfileIdExtra) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    val target =
        if (transportKind != null && profileId != null) {
            TransportFailoverTarget(transportKind, profileId)
        } else {
            null
        }
    return DecodedTransportFailoverCommand(requestId, target)
}

internal data class VpnTunnelRouteEntry(
    val address: String,
    val prefixLength: Int,
)

internal data class VpnTunnelRoutePlan(
    val addresses: List<VpnTunnelRouteEntry>,
    val routes: List<VpnTunnelRouteEntry>,
)

private fun android.net.VpnService.Builder.applyTunnelRoutePlan(plan: VpnTunnelRoutePlan) {
    plan.addresses.forEach { address ->
        addAddress(address.address, address.prefixLength)
    }
    plan.routes.forEach { route ->
        addRoute(route.address, route.prefixLength)
    }
}
