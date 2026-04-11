package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpFactory
import com.poyka.ripdpi.core.RipDpiWarpRuntime
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
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityScope
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
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

internal class TestNetworkDnsBlockedPathStore : com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore {
    private val blocked = mutableMapOf<String, MutableSet<String>>()
    val recorded = mutableListOf<Triple<String, String, String>>()

    override suspend fun getBlockedPathKeys(fingerprintHash: String): Set<String> = blocked[fingerprintHash].orEmpty()

    override suspend fun recordBlockedPath(
        fingerprintHash: String,
        pathKey: String,
        blockReason: String,
    ) {
        blocked.getOrPut(fingerprintHash) { mutableSetOf() }.add(pathKey)
        recorded.add(Triple(fingerprintHash, pathKey, blockReason))
    }

    override suspend fun clearAll() {
        blocked.clear()
        recorded.clear()
    }
}

internal class TestServerCapabilityStore : ServerCapabilityStore {
    private val relayRecords = linkedMapOf<String, ServerCapabilityRecord>()
    private val directPathRecords = linkedMapOf<String, ServerCapabilityRecord>()

    override suspend fun relayCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord> =
        relayRecords.values
            .filter { it.fingerprintHash == fingerprintHash }
            .sortedByDescending(ServerCapabilityRecord::updatedAt)

    override suspend fun directPathCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord> =
        directPathRecords.values
            .filter { it.fingerprintHash == fingerprintHash }
            .sortedByDescending(ServerCapabilityRecord::updatedAt)

    override suspend fun rememberRelayObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        relayProfileId: String?,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord =
        rememberRecord(
            records = relayRecords,
            scope = ServerCapabilityScope.Relay,
            fingerprint = fingerprint,
            authority = authority,
            relayProfileId = relayProfileId,
            observation = observation,
            source = source,
            recordedAt = recordedAt,
        )

    override suspend fun rememberDirectPathObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord =
        rememberRecord(
            records = directPathRecords,
            scope = ServerCapabilityScope.DirectPath,
            fingerprint = fingerprint,
            authority = authority,
            relayProfileId = null,
            observation = observation,
            source = source,
            recordedAt = recordedAt,
        )

    override suspend fun clearAll() {
        relayRecords.clear()
        directPathRecords.clear()
    }

    private fun rememberRecord(
        records: MutableMap<String, ServerCapabilityRecord>,
        scope: ServerCapabilityScope,
        fingerprint: NetworkFingerprint,
        authority: String,
        relayProfileId: String?,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord {
        val normalizedAuthority = authority.trim().lowercase()
        val record =
            ServerCapabilityRecord(
                scope = scope.wireValue,
                fingerprintHash = fingerprint.scopeKey(),
                authority = normalizedAuthority,
                relayProfileId = relayProfileId,
                quicUsable = observation.quicUsable,
                udpUsable = observation.udpUsable,
                authModeAccepted = observation.authModeAccepted,
                multiplexReusable = observation.multiplexReusable,
                shadowTlsCamouflageAccepted = observation.shadowTlsCamouflageAccepted,
                naiveHttpsProxyAccepted = observation.naiveHttpsProxyAccepted,
                fallbackRequired = observation.fallbackRequired,
                repeatedHandshakeFailureClass = observation.repeatedHandshakeFailureClass,
                source = source,
                updatedAt = recordedAt ?: 0L,
            )
        records["${record.fingerprintHash}|${record.authority}|${record.relayProfileId.orEmpty()}"] = record
        return record
    }
}

internal class TestNetworkEdgePreferenceStore : NetworkEdgePreferenceStore {
    private val preferences =
        linkedMapOf<Triple<String, String, String>, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>>()

    override suspend fun getPreferredEdges(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): List<com.poyka.ripdpi.data.PreferredEdgeCandidate> =
        preferences[Triple(fingerprintHash, host.lowercase(), transportKind.lowercase())].orEmpty()

    override suspend fun getPreferredEdgesForRuntime(
        fingerprintHash: String,
    ): Map<String, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>> =
        preferences
            .filterKeys { (storedFingerprintHash, _, _) -> storedFingerprintHash == fingerprintHash }
            .entries
            .groupBy({ it.key.second }, { it.value })
            .mapValues { (_, value) -> value.flatten() }
            .filterValues { it.isNotEmpty() }

    override suspend fun clearAll() {
        preferences.clear()
    }

    override suspend fun rememberPreferredEdges(
        fingerprint: NetworkFingerprint,
        host: String,
        transportKind: String,
        edges: List<com.poyka.ripdpi.data.PreferredEdgeCandidate>,
        recordedAt: Long?,
    ): NetworkEdgePreferenceEntity {
        val key = Triple(fingerprint.scopeKey(), host.lowercase(), transportKind.lowercase())
        preferences[key] = edges
        return NetworkEdgePreferenceEntity(
            fingerprintHash = key.first,
            host = key.second,
            transportKind = key.third,
            summaryJson = Json.encodeToString(fingerprint.summary()),
            edgesJson = Json.encodeToString(edges),
            updatedAt = recordedAt ?: 0L,
        )
    }

    override suspend fun recordEdgeResult(
        fingerprint: NetworkFingerprint,
        host: String,
        transportKind: String,
        ip: String,
        success: Boolean,
        recordedAt: Long?,
        echCapable: Boolean,
        cdnProvider: String?,
    ): NetworkEdgePreferenceEntity {
        val key = Triple(fingerprint.scopeKey(), host.lowercase(), transportKind.lowercase())
        val existing = preferences[key].orEmpty()
        val updated =
            existing
                .toMutableList()
                .apply {
                    val index = indexOfFirst { it.ip == ip }
                    if (index >= 0) {
                        this[index] =
                            this[index].copy(
                                successCount = this[index].successCount + if (success) 1 else 0,
                                failureCount = this[index].failureCount + if (success) 0 else 1,
                                lastValidatedAt = if (success) recordedAt else this[index].lastValidatedAt,
                                lastFailedAt = if (success) this[index].lastFailedAt else recordedAt,
                                echCapable = this[index].echCapable || echCapable,
                                cdnProvider = cdnProvider ?: this[index].cdnProvider,
                            )
                    } else {
                        add(
                            com.poyka.ripdpi.data.PreferredEdgeCandidate(
                                ip = ip,
                                transportKind = key.third,
                                ipVersion = if (ip.contains(':')) "ipv6" else "ipv4",
                                successCount = if (success) 1 else 0,
                                failureCount = if (success) 0 else 1,
                                lastValidatedAt = if (success) recordedAt else null,
                                lastFailedAt = if (success) null else recordedAt,
                                echCapable = echCapable,
                                cdnProvider = cdnProvider,
                            ),
                        )
                    }
                }
        return rememberPreferredEdges(
            fingerprint = fingerprint,
            host = host,
            transportKind = transportKind,
            edges = updated,
            recordedAt = recordedAt,
        )
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

internal class TestRelayRuntime(
    internal val events: MutableList<String> = mutableListOf(),
) : RipDpiRelayRuntime {
    private var exitCode = CompletableDeferred<Int>()
    private val ready = CompletableDeferred<Unit>()

    var startFailure: Throwable? = null
    var awaitReadyFailure: Exception? = null
    var stopFailure: Throwable? = null
    var telemetryFailure: Throwable? = null
    var telemetry: NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "relay",
            state = "running",
            health = "healthy",
        )
    var lastConfig: ResolvedRipDpiRelayConfig? = null
        private set
    var stopCount: Int = 0
        private set

    override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
        lastConfig = config
        events += "relay:start"
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

    override suspend fun stop() {
        stopCount += 1
        events += "relay:stop"
        stopFailure?.let { throw it }
        if (!exitCode.isCompleted) {
            exitCode.complete(0)
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        telemetryFailure?.let { throw it }
        return telemetry
    }

    fun complete(code: Int) {
        if (!exitCode.isCompleted) {
            exitCode.complete(code)
        }
    }
}

internal class TestRipDpiRelayFactory(
    private val runtimeFactory: () -> TestRelayRuntime = { TestRelayRuntime() },
) : RipDpiRelayFactory {
    val runtimes = mutableListOf<TestRelayRuntime>()

    val lastRuntime: TestRelayRuntime
        get() = runtimes.last()

    override fun create(): RipDpiRelayRuntime =
        runtimeFactory().also { runtime ->
            runtimes += runtime
        }
}

internal class TestNaiveProxyRuntimeFactory(
    private val runtimeFactory: () -> TestRelayRuntime = { TestRelayRuntime() },
) : NaiveProxyRuntimeFactory {
    val runtimes = mutableListOf<TestRelayRuntime>()

    override fun create(): RipDpiRelayRuntime =
        runtimeFactory().also { runtime ->
            runtimes += runtime
        }
}

internal class TestPluggableTransportRuntimeFactory(
    private val runtimeFactory: () -> TestRelayRuntime = { TestRelayRuntime() },
) : PluggableTransportRuntimeFactory {
    val runtimes = mutableListOf<TestRelayRuntime>()

    override fun create(): RipDpiRelayRuntime =
        runtimeFactory().also { runtime ->
            runtimes += runtime
        }
}

internal class TestRelayProfileStore : RelayProfileStore {
    val profiles = linkedMapOf<String, RelayProfileRecord>()

    override suspend fun load(profileId: String): RelayProfileRecord? = profiles[profileId]

    override suspend fun save(profile: RelayProfileRecord) {
        profiles[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        profiles.remove(profileId)
    }
}

internal class TestRelayCredentialStore : RelayCredentialStore {
    val credentials = linkedMapOf<String, RelayCredentialRecord>()

    override suspend fun load(profileId: String): RelayCredentialRecord? = credentials[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        this.credentials[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        credentials.remove(profileId)
    }
}

internal class TestWarpRuntime(
    internal val events: MutableList<String> = mutableListOf(),
) : RipDpiWarpRuntime {
    private var exitCode = CompletableDeferred<Int>()
    private val ready = CompletableDeferred<Unit>()

    var startFailure: Throwable? = null
    var awaitReadyFailure: Exception? = null
    var stopFailure: Throwable? = null
    var telemetryFailure: Throwable? = null
    var telemetry: NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "warp",
            state = "running",
            health = "healthy",
        )
    var lastConfig: com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig? = null
        private set
    var stopCount: Int = 0
        private set

    override suspend fun start(config: com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig): Int {
        lastConfig = config
        events += "warp:start"
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

    override suspend fun stop() {
        stopCount += 1
        events += "warp:stop"
        stopFailure?.let { throw it }
        if (!exitCode.isCompleted) {
            exitCode.complete(0)
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        telemetryFailure?.let { throw it }
        return telemetry
    }

    fun complete(code: Int) {
        if (!exitCode.isCompleted) {
            exitCode.complete(code)
        }
    }
}

internal class TestRipDpiWarpFactory(
    private val runtimeFactory: () -> TestWarpRuntime = { TestWarpRuntime() },
) : RipDpiWarpFactory {
    val runtimes = mutableListOf<TestWarpRuntime>()

    val lastRuntime: TestWarpRuntime
        get() = runtimes.last()

    override fun create(): RipDpiWarpRuntime =
        runtimeFactory().also { runtime ->
            runtimes += runtime
        }
}

internal class TestWarpRuntimeConfigResolver : WarpRuntimeConfigResolver {
    var lastConfig: RipDpiWarpConfig? = null
        private set

    override suspend fun resolve(config: RipDpiWarpConfig): com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig {
        lastConfig = config
        return com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig(
            enabled = config.enabled,
            profileId = "warp-test",
            accountKind = "consumer_free",
            deviceId = "device-1",
            accessToken = "token-1",
            clientId = "AQID",
            privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            publicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            peerPublicKey = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
            interfaceAddressV4 = "172.16.0.2/32",
            interfaceAddressV6 = null,
            endpoint =
                com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint(
                    host = "162.159.192.1",
                    ipv4 = "162.159.192.1",
                    ipv6 = null,
                    port = 2408,
                    source = "test",
                ),
            routeMode = config.routeMode,
            routeHosts = config.routeHosts,
            builtInRulesEnabled = config.builtInRulesEnabled,
            endpointSelectionMode = config.endpointSelectionMode,
            manualEndpoint = config.manualEndpoint,
            scannerEnabled = config.scannerEnabled,
            scannerParallelism = config.scannerParallelism,
            scannerMaxRttMs = config.scannerMaxRttMs,
            amnezia = config.amnezia,
            localSocksHost = config.localSocksHost,
            localSocksPort = config.localSocksPort,
        )
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
    proxyPreferences: RipDpiProxyPreferences = RipDpiProxyUIPreferences(),
    policySignature: String = "policy-signature",
    appliedPolicy: RememberedNetworkPolicyJson? = sampleRememberedPolicyJson(mode),
    matchedPolicy: RememberedNetworkPolicyEntity? = null,
    networkScopeKey: String? = sampleFingerprint().scopeKey(),
    resolverFallbackReason: String? = null,
): ConnectionPolicyResolution =
    ConnectionPolicyResolution(
        settings = settings,
        proxyPreferences = proxyPreferences,
        activeDns = activeDns,
        matchedNetworkPolicy = matchedPolicy,
        appliedPolicy = appliedPolicy,
        networkScopeKey = networkScopeKey,
        fingerprintHash = networkScopeKey,
        policySignature = policySignature,
        resolverFallbackReason = resolverFallbackReason,
    )

internal class TestPermissionWatchdog : PermissionWatchdog {
    override val changes: SharedFlow<PermissionChangeEvent> = MutableSharedFlow()
}

internal class TestScreenStateObserver(
    interactive: Boolean = true,
) : ScreenStateObserver {
    override val isInteractive: StateFlow<Boolean> = MutableStateFlow(interactive)
}
