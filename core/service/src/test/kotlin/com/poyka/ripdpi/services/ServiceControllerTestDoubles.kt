package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class TestServiceClock(
    var now: Long = 1_000L,
) : ServiceClock {
    override fun nowMillis(): Long = now
}

internal class TestServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventState = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventState.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    val eventHistory = mutableListOf<ServiceEvent>()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        val previousStatus = statusState.value.first
        statusState.value = status to mode
        val currentTelemetry = telemetryState.value
        telemetryState.value =
            currentTelemetry.copy(
                mode = mode,
                status = status,
                serviceStartedAt =
                    when {
                        status == AppStatus.Running && previousStatus != AppStatus.Running -> System.currentTimeMillis()
                        status == AppStatus.Running -> currentTelemetry.serviceStartedAt
                        else -> null
                    },
                restartCount =
                    when {
                        status == AppStatus.Running && previousStatus != AppStatus.Running -> {
                            currentTelemetry.restartCount +
                                1
                        }

                        else -> {
                            currentTelemetry.restartCount
                        }
                    },
            )
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        val event = ServiceEvent.Failed(sender, reason)
        eventHistory += event
        eventState.tryEmit(event)
        telemetryState.value =
            telemetryState.value.copy(
                lastFailureSender = sender,
                lastFailureAt = System.currentTimeMillis(),
            )
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        val current = telemetryState.value
        telemetryState.value =
            snapshot.copy(
                serviceStartedAt = snapshot.serviceStartedAt ?: current.serviceStartedAt,
                restartCount = maxOf(snapshot.restartCount, current.restartCount),
                lastFailureSender = snapshot.lastFailureSender ?: current.lastFailureSender,
                lastFailureAt = snapshot.lastFailureAt ?: current.lastFailureAt,
            )
    }
}

internal class TestNetworkFingerprintProvider(
    var fingerprint: NetworkFingerprint? = null,
) : NetworkFingerprintProvider {
    override fun capture(): NetworkFingerprint? = fingerprint
}

internal class TestTelemetryFingerprintHasher(
    var hashValue: String? = null,
) : TelemetryFingerprintHasher {
    override fun hash(fingerprint: NetworkFingerprint?): String? = hashValue ?: fingerprint?.scopeKey()
}

internal class TestPolicyHandoverEventStore : PolicyHandoverEventStore {
    private val eventsState = MutableSharedFlow<PolicyHandoverEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PolicyHandoverEvent> = eventsState.asSharedFlow()

    val published = mutableListOf<PolicyHandoverEvent>()

    override fun publish(event: PolicyHandoverEvent) {
        published += event
        eventsState.tryEmit(event)
    }
}

internal class TestResolverOverrideStore(
    initial: TemporaryResolverOverride? = null,
) : com.poyka.ripdpi.data.ResolverOverrideStore {
    private val state = MutableStateFlow(initial)
    override val override: StateFlow<TemporaryResolverOverride?> = state.asStateFlow()

    override fun setTemporaryOverride(override: TemporaryResolverOverride) {
        state.value = override
    }

    override fun clear() {
        state.value = null
    }
}

internal class TestRememberedNetworkPolicyStore : RememberedNetworkPolicyStore {
    val failures = mutableListOf<RememberedNetworkPolicyEntity>()
    var validatedMatch: RememberedNetworkPolicyEntity? = null

    override fun observePolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> = emptyFlow()

    override suspend fun findValidatedMatch(
        fingerprintHash: String,
        mode: Mode,
    ): RememberedNetworkPolicyEntity? = validatedMatch

    override suspend fun upsertObservedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: RememberedNetworkPolicySource,
        observedAt: Long?,
    ): RememberedNetworkPolicyEntity = sampleRememberedPolicyEntity()

    override suspend fun rememberValidatedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: RememberedNetworkPolicySource,
        validatedAt: Long?,
    ): RememberedNetworkPolicyEntity = sampleRememberedPolicyEntity()

    override suspend fun recordApplied(
        policy: RememberedNetworkPolicyEntity,
        appliedAt: Long?,
    ): RememberedNetworkPolicyEntity = policy

    override suspend fun recordSuccess(
        policy: RememberedNetworkPolicyEntity,
        validated: Boolean,
        strategySignatureJson: String?,
        completedAt: Long?,
    ): RememberedNetworkPolicyEntity = policy

    override suspend fun recordFailure(
        policy: RememberedNetworkPolicyEntity,
        failedAt: Long?,
        allowSuppression: Boolean,
    ): RememberedNetworkPolicyEntity {
        failures += policy
        return policy
    }

    override suspend fun clearAll() = Unit
}

internal class TestNetworkDnsPathPreferenceStore : NetworkDnsPathPreferenceStore {
    private val preferences = linkedMapOf<String, com.poyka.ripdpi.data.EncryptedDnsPathCandidate>()

    override suspend fun getPreferredPath(fingerprintHash: String): com.poyka.ripdpi.data.EncryptedDnsPathCandidate? =
        preferences[fingerprintHash]

    override suspend fun clearAll() {
        preferences.clear()
    }

    override suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: com.poyka.ripdpi.data.EncryptedDnsPathCandidate,
        recordedAt: Long?,
    ): NetworkDnsPathPreferenceEntity {
        val fingerprintHash = fingerprint.scopeKey()
        preferences[fingerprintHash] = path
        return NetworkDnsPathPreferenceEntity(
            fingerprintHash = fingerprintHash,
            summaryJson = Json.encodeToString(fingerprint.summary()),
            pathJson = Json.encodeToString(path),
            updatedAt = recordedAt ?: 0L,
        )
    }

    fun setPreferredPath(
        fingerprintHash: String,
        path: com.poyka.ripdpi.data.EncryptedDnsPathCandidate,
    ) {
        preferences[fingerprintHash] = path
    }
}

internal class TestAppSettingsRepository(
    initial: AppSettings = AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

internal class TestConnectionPolicyResolver(
    initialResolution: ConnectionPolicyResolution = sampleResolution(),
) : ConnectionPolicyResolver {
    data class ResolveCall(
        val mode: Mode,
        val resolverOverride: TemporaryResolverOverride?,
        val fingerprint: NetworkFingerprint?,
        val handoverClassification: String?,
    )

    private val queuedResolutions = ArrayDeque<ConnectionPolicyResolution>()
    private var fallbackResolution: ConnectionPolicyResolution = initialResolution

    val calls = mutableListOf<ResolveCall>()

    fun enqueue(vararg resolutions: ConnectionPolicyResolution) {
        queuedResolutions.addAll(resolutions.toList())
        if (resolutions.isNotEmpty()) {
            fallbackResolution = resolutions.last()
        }
    }

    override suspend fun resolve(
        mode: Mode,
        resolverOverride: TemporaryResolverOverride?,
        fingerprint: NetworkFingerprint?,
        handoverClassification: String?,
    ): ConnectionPolicyResolution {
        calls += ResolveCall(mode, resolverOverride, fingerprint, handoverClassification)
        queuedResolutions.removeFirstOrNull()?.let { return it }
        return if (resolverOverride != null) {
            fallbackResolution.copy(
                activeDns = resolverOverride.toActiveDnsSettings(),
                resolverFallbackReason = resolverOverride.reason,
            )
        } else {
            fallbackResolution
        }
    }
}

internal class TestNetworkHandoverMonitor : NetworkHandoverMonitor {
    private val eventsState = MutableSharedFlow<NetworkHandoverEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<NetworkHandoverEvent> = eventsState.asSharedFlow()

    suspend fun emit(event: NetworkHandoverEvent) {
        eventsState.emit(event)
    }
}

internal class TestNativeNetworkSnapshotProvider(
    var snapshot: NativeNetworkSnapshot = NativeNetworkSnapshot(transport = "wifi"),
    var captureFailure: Throwable? = null,
) : NativeNetworkSnapshotProvider {
    override fun capture(): NativeNetworkSnapshot {
        captureFailure?.let { throw it }
        return snapshot
    }
}

internal class TestProxyRuntime(
    internal val events: MutableList<String> = mutableListOf(),
) : RipDpiProxyRuntime {
    private var exitCode = CompletableDeferred<Int>()
    private val ready = CompletableDeferred<Unit>()

    var startFailure: Throwable? = null
    var awaitReadyFailure: Exception? = null
    var stopFailure: Throwable? = null
    var telemetryFailure: Throwable? = null
    var telemetry: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy")
    var lastPreferences: RipDpiProxyPreferences? = null
        private set
    var stopCount: Int = 0
        private set
    var updatedSnapshots: Int = 0
        private set

    override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        lastPreferences = preferences
        events += "proxy:start"
        startFailure?.let {
            ready.completeExceptionally(it)
            throw it
        }
        ready.complete(Unit)
        return exitCode.await()
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        awaitReadyFailure?.let { throw it }
        ready.await()
    }

    override suspend fun stopProxy() {
        stopCount += 1
        events += "proxy:stop"
        stopFailure?.let { throw it }
        if (!exitCode.isCompleted) {
            exitCode.complete(0)
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        telemetryFailure?.let { throw it }
        return telemetry
    }

    override suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot) {
        updatedSnapshots += 1
    }

    fun complete(code: Int) {
        if (!exitCode.isCompleted) {
            exitCode.complete(code)
        }
    }
}

internal class TestRipDpiProxyFactory(
    private val runtimeFactory: () -> TestProxyRuntime = { TestProxyRuntime() },
) : RipDpiProxyFactory {
    val runtimes = mutableListOf<TestProxyRuntime>()

    val lastRuntime: TestProxyRuntime
        get() = runtimes.last()

    override fun create(): RipDpiProxyRuntime =
        runtimeFactory().also { runtime ->
            runtimes += runtime
        }
}

internal class TestTun2SocksBridge(
    internal val events: MutableList<String> = mutableListOf(),
) : Tun2SocksBridge {
    var startedConfig: Tun2SocksConfig? = null
        private set
    var startedTunFd: Int? = null
        private set
    var stopCount: Int = 0
        private set
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var telemetryFailure: Throwable? = null
    var telemetry: NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "tunnel",
            state = "running",
            health = "healthy",
        )
    var statsValue: TunnelStats = TunnelStats()

    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    ) {
        events += "tunnel:start"
        startedConfig = config
        startedTunFd = tunFd
        startFailure?.let { throw it }
    }

    override suspend fun stop() {
        stopCount += 1
        events += "tunnel:stop"
        stopFailure?.let { throw it }
    }

    override suspend fun stats(): TunnelStats = statsValue

    override suspend fun telemetry(): NativeRuntimeSnapshot {
        telemetryFailure?.let { throw it }
        return telemetry.copy(tunnelStats = statsValue)
    }
}

internal class TestTun2SocksBridgeFactory(
    val bridge: TestTun2SocksBridge = TestTun2SocksBridge(),
) : Tun2SocksBridgeFactory {
    override fun create(): Tun2SocksBridge = bridge
}

internal class TestVpnTunnelSession(
    override val tunFd: Int = 7,
    private val events: MutableList<String> = mutableListOf(),
) : VpnTunnelSession {
    var closed: Boolean = false
        private set

    override fun close() {
        closed = true
        events += "vpn:session-close"
    }
}

internal class TestVpnTunnelSessionProvider(
    private val events: MutableList<String> = mutableListOf(),
    var session: TestVpnTunnelSession = TestVpnTunnelSession(events = events),
) : VpnTunnelSessionProvider {
    var establishFailure: Throwable? = null
    var lastDns: String? = null
        private set
    var lastIpv6: Boolean? = null
        private set

    override fun establish(
        host: VpnTunnelBuilderHost,
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelSession {
        lastDns = dns
        lastIpv6 = ipv6
        events += "vpn:establish"
        establishFailure?.let { throw it }
        return session
    }
}

internal class TestProxyServiceHost(
    override val serviceScope: CoroutineScope,
) : ServiceCoordinatorHost {
    val notifications = mutableListOf<NativeRuntimeSnapshot>()
    val stopRequests = mutableListOf<Int?>()

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) {
        notifications += proxyTelemetry
    }

    override fun requestStopSelf(stopSelfStartId: Int?) {
        stopRequests += stopSelfStartId
    }
}

internal class TestVpnServiceHost(
    override val serviceScope: CoroutineScope,
) : VpnCoordinatorHost {
    val notifications = mutableListOf<Pair<TunnelStats, NativeRuntimeSnapshot>>()
    val stopRequests = mutableListOf<Int?>()
    var underlyingNetworkSyncs: Int = 0
    var builderSession: VpnTunnelSession? = TestVpnTunnelSession()

    override fun updateNotification(
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    ) {
        notifications += tunnelStats to proxyTelemetry
    }

    override fun requestStopSelf(stopSelfStartId: Int?) {
        stopRequests += stopSelfStartId
    }

    override fun syncUnderlyingNetworksFromActiveNetwork() {
        underlyingNetworkSyncs += 1
    }

    override fun createTunnelBuilder(
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelBuilder =
        object : VpnTunnelBuilder {
            override fun establish(): VpnTunnelSession? = builderSession
        }
}

internal fun sampleFingerprint(
    dnsServers: List<String> = listOf("1.1.1.1"),
    transport: String = "wifi",
): NetworkFingerprint =
    NetworkFingerprint(
        transport = transport,
        networkValidated = true,
        captivePortalDetected = false,
        privateDnsMode = "system",
        dnsServers = dnsServers,
        wifi =
            WifiNetworkIdentityTuple(
                ssid = "home",
                bssid = "00:11:22:33:44:55",
                gateway = "192.168.1.1",
            ),
    )

internal fun sampleRememberedPolicyJson(mode: Mode = Mode.Proxy): RememberedNetworkPolicyJson =
    RememberedNetworkPolicyJson(
        fingerprintHash = "fingerprint",
        mode = mode.preferenceValue,
        summary = sampleFingerprint().summary(),
        proxyConfigJson = "{}",
        winningTcpStrategyFamily = "tcp-family",
    )

internal fun sampleRememberedPolicyEntity(mode: Mode = Mode.Proxy): RememberedNetworkPolicyEntity =
    RememberedNetworkPolicyEntity(
        fingerprintHash = "fingerprint",
        mode = mode.preferenceValue,
        summaryJson = Json.encodeToString(sampleFingerprint().summary()),
        proxyConfigJson = "{}",
        source = RememberedNetworkPolicySource.MANUAL_SESSION.encodeStorageValue(),
        status = "validated",
        firstObservedAt = 1L,
        updatedAt = 1L,
    )

internal fun sampleResolution(
    mode: Mode = Mode.Proxy,
    settings: AppSettings = AppSettingsSerializer.defaultValue,
    activeDns: ActiveDnsSettings = settings.activeDnsSettings(),
    policySignature: String = "policy-signature",
    appliedPolicy: RememberedNetworkPolicyJson? = sampleRememberedPolicyJson(mode),
    matchedPolicy: RememberedNetworkPolicyEntity? = null,
    networkScopeKey: String? = sampleFingerprint().scopeKey(),
    resolverFallbackReason: String? = null,
): ConnectionPolicyResolution =
    ConnectionPolicyResolution(
        settings = settings,
        proxyPreferences = RipDpiProxyUIPreferences(),
        activeDns = activeDns,
        matchedNetworkPolicy = matchedPolicy,
        appliedPolicy = appliedPolicy,
        networkScopeKey = networkScopeKey,
        fingerprintHash = networkScopeKey,
        policySignature = policySignature,
        resolverFallbackReason = resolverFallbackReason,
    )
