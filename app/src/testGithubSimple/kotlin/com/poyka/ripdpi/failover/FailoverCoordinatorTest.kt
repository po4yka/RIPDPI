package com.poyka.ripdpi.failover

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessFlowVision
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.XudpTelemetrySnapshot
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.seed.SIMPLE_SEED_AWG_PROFILE_ID
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.StartupFallbackController
import com.poyka.ripdpi.services.StartupFallbackDispatchResult
import com.poyka.ripdpi.services.StartupFallbackLease
import com.poyka.ripdpi.services.TransportFailoverApplyTracker
import com.poyka.ripdpi.services.TransportFailoverTarget
import com.poyka.ripdpi.services.TransportKindAmneziaWg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ── Fakes ────────────────────────────────────────────────────────────────────

private class FakeServiceStateStore(
    initialStatus: AppStatus = AppStatus.Running,
) : ServiceStateStore {
    private val _telemetry =
        MutableStateFlow(
            ServiceTelemetrySnapshot(status = initialStatus, mode = Mode.VPN),
        )
    private val _status = MutableStateFlow(initialStatus to Mode.VPN)
    private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)

    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = _telemetry.asStateFlow()
    override val status: StateFlow<Pair<AppStatus, Mode>> = _status.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = _events.asSharedFlow()

    fun emitTelemetry(snapshot: ServiceTelemetrySnapshot) {
        _telemetry.value = snapshot
    }

    fun emitFailure(reason: FailureReason) {
        val (status, mode) = _status.value
        check(
            _events.tryEmit(
                ServiceEvent.Failed(
                    sender = Sender.VPN,
                    reason = reason,
                    statusAtFailure = status,
                    modeAtFailure = mode,
                ),
            ),
        )
    }

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        _status.value = status to mode
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) = Unit

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        _telemetry.value = snapshot
    }
}

private class FakeServiceController(
    private val stateStore: FakeServiceStateStore? = null,
) : ServiceController,
    StartupFallbackController {
    val startCalls = mutableListOf<Mode>()
    val transportRestartCalls = mutableListOf<Mode>()
    val transportRestartRequestIds = mutableListOf<Long>()
    val transportRestartTargets = mutableListOf<TransportFailoverTarget>()
    val stopCalls = mutableListOf<Unit>()
    val actualStopCalls = mutableListOf<Unit>()
    var beforeTransportRestartResult: () -> Unit = {}
    var transportRestartResult: ServiceStartResult = ServiceStartResult.Accepted(Mode.VPN)
    var autoConfirmTransportRestart: Boolean = true
    var claimTransportRestartWithoutConfirmation: Boolean = false
    var transportFailoverApplyTracker: TransportFailoverApplyTracker? = null

    override fun start(mode: Mode): ServiceStartResult {
        startCalls += mode
        return ServiceStartResult.Accepted(mode)
    }

    override fun stop() {
        actualStopCalls += Unit
        stateStore?.setStatus(AppStatus.Halted, Mode.VPN)
    }

    override fun restartVpnForTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
    ): ServiceStartResult {
        transportRestartCalls += Mode.VPN
        transportRestartRequestIds += requestId
        transportRestartTargets += expectedTarget
        // Keep the legacy counters populated so the existing switch-budget tests
        // remain focused on how many replacements were requested.
        stopCalls += Unit
        startCalls += Mode.VPN
        beforeTransportRestartResult()
        return transportRestartResult.also { result ->
            if (autoConfirmTransportRestart && result is ServiceStartResult.Accepted) {
                transportFailoverApplyTracker?.let { tracker ->
                    check(tracker.claimApplying(requestId))
                    check(tracker.recordApplied(requestId))
                    tracker.releaseRuntimeOwnership(requestId)
                }
            } else if (claimTransportRestartWithoutConfirmation && result is ServiceStartResult.Accepted) {
                check(transportFailoverApplyTracker?.claimApplying(requestId) == true)
            }
        }
    }

    override fun captureStartupFallbackLease(): StartupFallbackLease = FakeStartupFallbackLease

    override fun startVpnForStartupFallback(lease: StartupFallbackLease): StartupFallbackDispatchResult =
        StartupFallbackDispatchResult.Dispatched(start(Mode.VPN))
}

private data object FakeStartupFallbackLease : StartupFallbackLease

private class FakeAppSettingsRepository(
    udpAssociateEnabled: Boolean? = false,
) : AppSettingsRepository {
    var beforeUpdate: suspend () -> Unit = {}

    private val settingsState =
        MutableStateFlow(
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .apply { udpAssociateEnabled?.let(::setUdpAssociateEnabled) }
                .build(),
        )

    override val settings: Flow<AppSettings> = settingsState.asStateFlow()

    override suspend fun snapshot(): AppSettings = settingsState.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        beforeUpdate()
        settingsState.value =
            settingsState.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        settingsState.value = settings
    }

    fun relayEnabled(): Boolean = settingsState.value.relayEnabled

    fun relayKind(): String = settingsState.value.relayKind

    fun relayProfileId(): String = settingsState.value.relayProfileId

    fun simpleFailoverAwgProfileId(): String = settingsState.value.simpleFailoverAwgProfileId
}

private class FakeAwgProfileDao(
    rows: List<AwgProfileEntity> = emptyList(),
) : AwgProfileDao {
    private val rowsState = MutableStateFlow(rows.toMutableList())

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = rowsState.asStateFlow()

    override suspend fun allProfiles(): List<AwgProfileEntity> = rowsState.value

    override suspend fun getProfile(id: String): AwgProfileEntity? = rowsState.value.firstOrNull { it.id == id }

    override suspend fun upsertProfile(profile: AwgProfileEntity) {
        val list = rowsState.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        rowsState.value = list
    }

    override suspend fun deleteProfile(profile: AwgProfileEntity) {
        rowsState.value = rowsState.value.filter { it.id != profile.id }.toMutableList()
    }

    override suspend fun deleteAll() {
        rowsState.value = mutableListOf()
    }
}

private class FakeAwgCredentialStore : AwgCredentialStore {
    override suspend fun load(profileId: String): AwgSecrets = AwgSecrets()

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) = Unit

    override suspend fun clear(profileId: String) = Unit
}

private class FakeFailoverClock(
    var now: Long = 0L,
) : FailoverClock {
    override fun nowMillis(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Monotonically-incrementing counter stamped into [ServiceTelemetrySnapshot.updatedAt].
 *
 * MutableStateFlow deduplicates by structural equality. Without a unique counter,
 * consecutive emissions with the same health string (e.g. repeated "failed") would
 * be silently dropped and the collector would never see them.
 */
private var telemetrySeq = 0L

private fun runningTelemetry(
    relayHealth: String = "healthy",
    awgHealth: String = "idle",
    relayListenerAddress: String? = "127.0.0.1:1080",
    relayProtocolKind: String? = RelayKindVlessReality,
    proxyTotalErrors: Long = 0,
    proxyLastFailureClass: String? = null,
    xudpConsecutiveFailures: Long = 0,
    networkScopeKey: String? = null,
    networkHandoverState: String? = null,
    failureClass: FailureClass? = null,
): ServiceTelemetrySnapshot =
    ServiceTelemetrySnapshot(
        status = AppStatus.Running,
        mode = Mode.VPN,
        proxyTelemetry =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                health = "healthy",
                totalErrors = proxyTotalErrors,
                lastFailureClass = proxyLastFailureClass,
            ),
        relayTelemetry =
            NativeRuntimeSnapshot(
                source = "relay",
                state = "running",
                health = relayHealth,
                listenerAddress = relayListenerAddress,
                protocolKind = relayProtocolKind,
                xudpTelemetry =
                    XudpTelemetrySnapshot(
                        consecutiveUdpFailures = xudpConsecutiveFailures,
                    ),
            ),
        awgTelemetry = NativeRuntimeSnapshot(source = "awg", state = "running", health = awgHealth),
        networkHandoverState = networkHandoverState,
        runtimeFieldTelemetry =
            RuntimeFieldTelemetry(
                failureClass = failureClass,
                telemetryNetworkFingerprintHash = networkScopeKey,
            ),
        updatedAt = ++telemetrySeq,
    )

/**
 * AWG request JSON the repository can decode. Required fields (profileId, privateKey)
 * are empty strings — the real store strips them on save and re-stamps on load.
 */
private const val MINIMAL_AWG_REQUEST_JSON =
    """{"profileId":"","privateKey":"","peerPublicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","endpointHost":"10.0.0.1","endpointPort":51820,"interfaceAddressV4":"10.8.0.2/32"}"""

private fun buildCoordinator(
    stateStore: FakeServiceStateStore = FakeServiceStateStore(),
    controller: FakeServiceController = FakeServiceController(stateStore),
    relayProfiles: List<RelayProfileRecord> =
        listOf(
            RelayProfileRecord(id = "reality-1", kind = RelayKindVlessReality),
            RelayProfileRecord(id = "hysteria-1", kind = RelayKindHysteria2, udpEnabled = true),
        ),
    awgProfiles: List<AwgProfileEntity> = emptyList(),
    clock: FakeFailoverClock = FakeFailoverClock(now = 0L),
    settings: FakeAppSettingsRepository = FakeAppSettingsRepository(),
    egressProbe: FailoverEgressProbe =
        FailoverEgressProbe { _, _ -> FailoverEgressProbeResult(succeeded = false) },
    egressHealthMemory: SimpleEgressHealthMemory = RecordingSimpleEgressHealthMemory(),
): CoordinatorFixture {
    val awgRepo = AwgProfileRepository(FakeAwgProfileDao(awgProfiles), FakeAwgCredentialStore())
    val awgSelection = SimpleAwgEgressSelection(awgRepo, settings)
    val transportFailoverApplyTracker = TransportFailoverApplyTracker()
    controller.transportFailoverApplyTracker = transportFailoverApplyTracker
    val coordinator =
        FailoverCoordinator(
            serviceStateStore = stateStore,
            serviceController = controller,
            startupFallbackController = controller,
            relayCatalog = SimpleFailoverRelayCatalog { relayProfiles },
            settingsRepository = settings,
            awgEgressSelection = awgSelection,
            egressProbe = egressProbe,
            egressHealthCache = egressHealthMemory,
            transportFailoverApplyTracker = transportFailoverApplyTracker,
            clock = clock,
        )
    return CoordinatorFixture(coordinator, controller, clock, awgSelection, transportFailoverApplyTracker)
}

private class RecordingSimpleEgressHealthMemory : SimpleEgressHealthMemory {
    data class Failure(
        val networkScopeKey: String?,
        val proof: EgressProof,
        val relayKind: String,
        val profileId: String,
    )

    val failures = mutableListOf<Failure>()

    override fun isCoolingDown(
        networkScopeKey: String?,
        proof: EgressProof,
        candidate: com.poyka.ripdpi.services.InitialRelayCandidate,
    ): Boolean = false

    override fun readWinner(
        networkScopeKey: String?,
        signature: String,
        proof: EgressProof,
    ): String? = null

    override fun writeWinner(
        networkScopeKey: String?,
        signature: String,
        proof: EgressProof,
        profileId: String,
    ) = Unit

    override fun recordConfirmedFailure(
        networkScopeKey: String?,
        proof: EgressProof,
        relayKind: String,
        profileId: String,
    ) {
        failures += Failure(networkScopeKey, proof, relayKind, profileId)
    }
}

private data class CoordinatorFixture(
    val coordinator: FailoverCoordinator,
    val controller: FakeServiceController,
    val clock: FakeFailoverClock,
    val awgSelection: SimpleAwgEgressSelection,
    val transportFailoverApplyTracker: TransportFailoverApplyTracker,
)

// ── Tests ─────────────────────────────────────────────────────────────────────

/**
 * Test strategy:
 *
 * The coordinator's observe coroutine is launched on a scope backed by
 * [UnconfinedTestDispatcher] (sharing the test's [TestCoroutineScheduler]).
 * This makes [StateFlow] emissions delivered synchronously to the collector —
 * no per-emit [advanceUntilIdle] needed for flow delivery. [advanceUntilIdle]
 * is only needed after a switch to drain any remaining child work.
 *
 * [FakeFailoverClock] is the sole clock for debounce/min-interval math.
 * [ServiceTelemetrySnapshot.updatedAt] increments on every [runningTelemetry]
 * call so [MutableStateFlow] never deduplicates consecutive same-health values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailoverCoordinatorTest {
    @Test
    fun `production bindings use the health probing coordinator`() {
        val bindings =
            FailoverCoordinatorBindsModule::class.java.declaredMethods.associate { method ->
                method.returnType to method.parameterTypes.single()
            }

        assertEquals(
            FailoverCoordinator::class.java,
            bindings[com.poyka.ripdpi.seed.SimpleFlavorSessionWatcher::class.java],
        )
        assertEquals(FailoverCoordinator::class.java, bindings[ActiveTransportProvider::class.java])
        assertEquals(
            FailoverCoordinator::class.java,
            bindings[com.poyka.ripdpi.services.ExplicitUserStartPreparer::class.java],
        )
    }

    @Test
    fun `explicit VPN start restores embedded Reality after automatic fallback`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            settings.update {
                setEnableCmdSettings(true)
                setRelayEnabled(false)
                setRelayKind(RelayKindHysteria2)
                setRelayProfileId("simple-seed-Hysteria2")
                setSimpleFailoverAwgProfileId(SIMPLE_SEED_AWG_PROFILE_ID)
            }
            val (coordinator, _, _) = buildCoordinator(settings = settings)

            coordinator.prepare(Mode.VPN)

            assertFalse(settings.snapshot().enableCmdSettings)
            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindVlessReality, settings.relayKind())
            assertEquals("simple-seed-VlessReality", settings.relayProfileId())
            assertEquals("", settings.simpleFailoverAwgProfileId())
            assertNull(coordinator.activeCandidate.value)
        }

    @Test
    fun `UDP blocked session falls back to TCP xHTTP before UDP transports`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository(udpAssociateEnabled = true)
            val primaryId = "simple-seed-VlessReality"
            val xhttpId = "simple-seed-Vless"
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(primaryId)
            }
            val probedRequirements = mutableListOf<com.poyka.ripdpi.services.EgressRequirements>()
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "UDP fallback",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = settings,
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = primaryId,
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                                vlessTransport = RelayVlessTransportRealityTcp,
                                vlessFlow = RelayVlessFlowVision,
                            ),
                            RelayProfileRecord(
                                id = xhttpId,
                                kind = RelayKindVless,
                                vlessTransport = RelayVlessTransportXhttp,
                            ),
                            RelayProfileRecord(
                                id = "simple-seed-Hysteria2",
                                kind = RelayKindHysteria2,
                                udpEnabled = true,
                            ),
                        ),
                    awgProfiles = listOf(awg),
                    egressProbe =
                        FailoverEgressProbe { _, requirements ->
                            probedRequirements += requirements
                            FailoverEgressProbeResult(succeeded = !requirements.udpAssociate)
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            val fallback = coordinator.activeCandidate.value
            check(fallback is FailoverCandidate.Relay)
            assertEquals(RelayKindVless, fallback.relayKind)
            assertEquals(xhttpId, fallback.profileId)
            assertEquals(1, controller.transportRestartCalls.size)

            clock.advance(1_000L)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "failed",
                    relayProtocolKind = RelayKindVless,
                ),
            )
            advanceUntilIdle()

            assertTrue(probedRequirements.any { it.udpAssociate })
            assertFalse(probedRequirements.last().udpAssociate)
            assertEquals("Healthy TCP reserve must stop the failover chain", 1, controller.transportRestartCalls.size)

            coordinator.stopObserving()
        }

    /**
     * Sustained relay failure >= FAILOVER_DEBOUNCE_MS triggers exactly ONE switch.
     *
     * Timing (FakeFailoverClock, debounce = 20 000 ms):
     *   t=7000   failed #1 → failingsSince=7000
     *   t=14000  failed #2 → elapsed=7000 < 20000, no switch
     *   t=21000  failed #3 → elapsed=14000 < 20000, no switch
     *   t=28000  failed #4 → elapsed=21000 >= 20000 → SWITCH
     */
    @Test
    fun sustainedFailureTriggersSingleSwitch() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update { setEnableCmdSettings(true) }
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            // UnconfinedTestDispatcher: startObserving runs eagerly through buildCandidates()
            // and reaches collect before returning.

            // Healthy baseline clears any seed state.
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed")) // failingsSince=7000

            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed")) // elapsed=7000

            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed")) // elapsed=14000

            clock.advance(7_000L) // t=28000; elapsed=21000 >= 20000 → SWITCH
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()

            assertEquals("Expected exactly one stop", 1, controller.stopCalls.size)
            assertEquals("Expected exactly one start", 1, controller.startCalls.size)
            assertEquals(Mode.VPN, controller.startCalls.first())
            assertFalse(settings.snapshot().enableCmdSettings)

            coordinator.stopObserving()
        }

    @Test
    fun `transport remains current until restart application is accepted`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val controller =
                FakeServiceController(stateStore).apply {
                    autoConfirmTransportRestart = false
                }
            lateinit var coordinator: FailoverCoordinator
            controller.beforeTransportRestartResult = {
                val active = coordinator.activeCandidate.value
                check(active is FailoverCandidate.Relay)
                assertEquals(RelayKindVlessReality, active.relayKind)
                assertEquals("reality-1", active.profileId)
            }
            val fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                )
            coordinator = fixture.coordinator
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            val pending = coordinator.activeCandidate.value
            check(pending is FailoverCandidate.Relay)
            assertEquals(RelayKindVlessReality, pending.relayKind)

            val requestId = controller.transportRestartRequestIds.single()
            assertTrue(fixture.transportFailoverApplyTracker.claimApplying(requestId))
            assertTrue(fixture.transportFailoverApplyTracker.recordApplied(requestId))
            advanceUntilIdle()

            val active = coordinator.activeCandidate.value
            check(active is FailoverCandidate.Relay)
            assertEquals(RelayKindHysteria2, active.relayKind)
            assertEquals("hysteria-1", active.profileId)

            coordinator.stopObserving()
        }

    @Test
    fun `rejected transport application rolls back and remains retryable`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId("reality-1")
            }
            val controller =
                FakeServiceController(stateStore).apply {
                    transportRestartResult =
                        ServiceStartResult.Rejected(
                            mode = Mode.VPN,
                            reason = ServiceStartRejectionReason.ForegroundServiceBlocked("blocked"),
                        )
                }
            val coordinator =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                    settings = settings,
                ).coordinator
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            val rejectedActive = coordinator.activeCandidate.value
            check(rejectedActive is FailoverCandidate.Relay)
            assertEquals(RelayKindVlessReality, rejectedActive.relayKind)
            assertEquals("reality-1", rejectedActive.profileId)
            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindVlessReality, settings.relayKind())
            assertEquals("reality-1", settings.relayProfileId())

            controller.transportRestartResult = ServiceStartResult.Accepted(Mode.VPN)
            repeat(4) {
                clock.advance(8_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            val retriedActive = coordinator.activeCandidate.value
            check(retriedActive is FailoverCandidate.Relay)
            assertEquals(RelayKindHysteria2, retriedActive.relayKind)
            assertEquals(2, controller.transportRestartCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun `failed AWG application restores persisted relay selection`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindHysteria2)
                setRelayProfileId("hysteria-1")
            }
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Fallback AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val controller =
                FakeServiceController(stateStore).apply {
                    autoConfirmTransportRestart = false
                }
            lateinit var fixture: CoordinatorFixture
            controller.beforeTransportRestartResult = {
                fixture.transportFailoverApplyTracker.recordRollbackSafeFailure(
                    controller.transportRestartRequestIds.single(),
                )
            }
            fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                    settings = settings,
                    awgProfiles = listOf(awg),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            val active = fixture.coordinator.activeCandidate.value
            check(active is FailoverCandidate.Relay)
            assertEquals(RelayKindHysteria2, active.relayKind)
            assertEquals("hysteria-1", active.profileId)
            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindHysteria2, settings.relayKind())
            assertEquals("hysteria-1", settings.relayProfileId())
            assertEquals("", settings.simpleFailoverAwgProfileId())
            assertNull(fixture.awgSelection.selectedAwgEgress())

            fixture.coordinator.stopObserving()
        }

    @Test
    fun `partial AWG persistence failure restores relay and clears selector`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindHysteria2)
                setRelayProfileId("hysteria-1")
            }
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Fallback AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = settings,
                    awgProfiles = listOf(awg),
                )
            val observerFailure = CompletableDeferred<Throwable>()
            val observeJob = SupervisorJob()
            val observeScope =
                CoroutineScope(
                    observeJob +
                        UnconfinedTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, failure -> observerFailure.complete(failure) },
                )
            var failNextUpdate = true
            settings.beforeUpdate = {
                if (failNextUpdate) {
                    failNextUpdate = false
                    error("simulated settings write failure")
                }
            }

            fixture.coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindHysteria2, settings.relayKind())
            assertEquals("hysteria-1", settings.relayProfileId())
            assertEquals("", settings.simpleFailoverAwgProfileId())
            assertNull(fixture.awgSelection.selectedAwgEgress())
            assertEquals("simulated settings write failure", observerFailure.await().message)

            fixture.coordinator.stopObserving()
            observeJob.cancel()
        }

    @Test
    fun `timed out transport application restores persisted relay selection`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId("reality-1")
            }
            val controller =
                FakeServiceController(stateStore).apply {
                    autoConfirmTransportRestart = false
                }
            val fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            val active = fixture.coordinator.activeCandidate.value
            check(active is FailoverCandidate.Relay)
            assertEquals(RelayKindVlessReality, active.relayKind)
            assertEquals("reality-1", active.profileId)
            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindVlessReality, settings.relayKind())
            assertEquals("reality-1", settings.relayProfileId())
            val requestId = controller.transportRestartRequestIds.single()
            assertFalse(fixture.transportFailoverApplyTracker.recordApplied(requestId))

            fixture.coordinator.stopObserving()
        }

    @Test
    fun `timed out runtime-owned application preserves in-flight relay selection`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId("reality-1")
            }
            val controller =
                FakeServiceController(stateStore).apply {
                    autoConfirmTransportRestart = false
                    claimTransportRestartWithoutConfirmation = true
                }
            val fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindHysteria2, settings.relayKind())
            assertEquals("hysteria-1", settings.relayProfileId())
            val requestId = controller.transportRestartRequestIds.single()
            assertTrue(runCatching { fixture.transportFailoverApplyTracker.begin() }.isFailure)

            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertEquals(1, controller.transportRestartCalls.size)
            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindHysteria2, settings.relayKind())
            assertEquals("hysteria-1", settings.relayProfileId())

            fixture.coordinator.stopObserving()
        }

    @Test
    fun `explicit prepare supersedes rollback from a cancelled transport switch`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindHysteria2)
                setRelayProfileId("hysteria-1")
            }
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Fallback AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val controller =
                FakeServiceController(stateStore).apply {
                    autoConfirmTransportRestart = false
                }
            val fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                    settings = settings,
                    awgProfiles = listOf(awg),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            runCurrent()
            assertEquals(1, controller.transportRestartRequestIds.size)

            fixture.coordinator.prepare(Mode.VPN)
            runCurrent()

            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindVlessReality, settings.relayKind())
            assertEquals("simple-seed-VlessReality", settings.relayProfileId())
            assertEquals("", settings.simpleFailoverAwgProfileId())
        }

    @Test
    fun `replacement observer waits for cancelled switch rollback`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId("reality-1")
            }
            val controller =
                FakeServiceController(stateStore).apply {
                    autoConfirmTransportRestart = false
                }
            val fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            runCurrent()
            assertEquals(1, controller.transportRestartCalls.size)

            val rollbackStarted = CompletableDeferred<Unit>()
            val releaseRollback = CompletableDeferred<Unit>()
            settings.beforeUpdate = {
                rollbackStarted.complete(Unit)
                releaseRollback.await()
            }
            fixture.coordinator.stopObserving()
            fixture.coordinator.startObserving(observeScope)
            runCurrent()

            rollbackStarted.await()
            fixture.coordinator.stopObserving()
            fixture.coordinator.startObserving(observeScope)
            runCurrent()
            assertNull(fixture.coordinator.activeCandidate.value)
            assertFalse(releaseRollback.isCompleted)

            releaseRollback.complete(Unit)
            advanceUntilIdle()

            val active = fixture.coordinator.activeCandidate.value
            check(active is FailoverCandidate.Relay)
            assertEquals(RelayKindVlessReality, active.relayKind)
            assertEquals("reality-1", active.profileId)
            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindVlessReality, settings.relayKind())
            assertEquals("reality-1", settings.relayProfileId())
            assertEquals(1, controller.transportRestartCalls.size)

            fixture.coordinator.stopObserving()
        }

    @Test
    fun `explicit prepare serializes with forward fallback persistence`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            val fallbackWriteStarted = CompletableDeferred<Unit>()
            val releaseFallbackWrite = CompletableDeferred<Unit>()
            var intercepted = false
            settings.beforeUpdate = {
                if (!intercepted) {
                    intercepted = true
                    fallbackWriteStarted.complete(Unit)
                    releaseFallbackWrite.await()
                }
            }
            val fixture =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = FakeServiceController(stateStore),
                    clock = clock,
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            fallbackWriteStarted.await()
            val prepare = async { fixture.coordinator.prepare(Mode.VPN) }
            runCurrent()

            releaseFallbackWrite.complete(Unit)
            prepare.await()
            advanceUntilIdle()

            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindVlessReality, settings.relayKind())
            assertEquals("simple-seed-VlessReality", settings.relayProfileId())
            assertEquals("", settings.simpleFailoverAwgProfileId())
        }

    @Test
    fun enrichedFailureHealthTriggersSwitch() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val (coordinator, controller, _) = buildCoordinator(stateStore = stateStore, clock = clock)
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy (pool busy=0 idle=1)"))

            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed (pool busy=0 idle=0)"))
            clock.advance(21_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed (pool busy=0 idle=0)"))
            advanceUntilIdle()

            assertEquals(1, controller.stopCalls.size)
            assertEquals(1, controller.startCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun successfulEgressConfirmationSuppressesPassiveFailureSwitch() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            var probeCalls = 0
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = FakeAppSettingsRepository(udpAssociateEnabled = null),
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = "reality-1",
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                            ),
                            RelayProfileRecord(
                                id = "hysteria-1",
                                kind = RelayKindHysteria2,
                                udpEnabled = true,
                            ),
                        ),
                    egressProbe =
                        FailoverEgressProbe { endpoint, requirements ->
                            probeCalls++
                            assertEquals(FailoverProxyEndpoint("127.0.0.1", 1080), endpoint)
                            assertTrue(requirements.udpAssociate)
                            FailoverEgressProbeResult(succeeded = true)
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()

            assertEquals(2, probeCalls)
            assertEquals(0, controller.stopCalls.size)
            assertEquals(0, controller.startCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun stopObservingCancelsSuspendedEgressConfirmationWithoutRestart() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val secondProbeStarted = CompletableDeferred<Unit>()
            var probeCalls = 0
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            probeCalls++
                            if (probeCalls == 1) {
                                FailoverEgressProbeResult(succeeded = false, failure = "udp_read_timeout")
                            } else {
                                secondProbeStarted.complete(Unit)
                                awaitCancellation()
                            }
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            secondProbeStarted.await()

            coordinator.stopObserving()
            advanceUntilIdle()

            assertEquals(2, probeCalls)
            assertEquals(0, controller.stopCalls.size)
            assertEquals(0, controller.startCalls.size)
            assertNull(coordinator.activeCandidate.value)
        }

    @Test
    fun freshProxyFailureTriggersConfirmedRelayFailoverWhenRelayHealthStaysRunning() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            var probeCalls = 0
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            probeCalls++
                            FailoverEgressProbeResult(
                                succeeded = false,
                                failure = "udp_read_timeout",
                            )
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "running"))

            clock.advance(1_000L)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    proxyTotalErrors = 1,
                    proxyLastFailureClass = "silent_drop",
                ),
            )
            clock.advance(21_000L)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    proxyTotalErrors = 1,
                    proxyLastFailureClass = "silent_drop",
                ),
            )
            advanceUntilIdle()

            assertEquals(2, probeCalls)
            assertEquals(1, controller.stopCalls.size)
            assertEquals(1, controller.startCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun consecutiveXudpFailuresTriggerConfirmedFailoverForUdpSession() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            var probeCalls = 0
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = FakeAppSettingsRepository(udpAssociateEnabled = null),
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = "reality-1",
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                            ),
                            RelayProfileRecord(
                                id = "hysteria-1",
                                kind = RelayKindHysteria2,
                                udpEnabled = true,
                            ),
                        ),
                    egressProbe =
                        FailoverEgressProbe { _, requirements ->
                            probeCalls++
                            assertTrue(requirements.udpAssociate)
                            FailoverEgressProbeResult(
                                succeeded = false,
                                failure = "udp_read_timeout",
                            )
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "running"))

            clock.advance(1_000L)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 3,
                ),
            )
            clock.advance(21_000L)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 3,
                ),
            )
            advanceUntilIdle()

            assertEquals(2, probeCalls)
            assertEquals(1, controller.transportRestartCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun confirmedXudpFailureRecordsNetworkScopedUdpPenalty() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val memory = RecordingSimpleEgressHealthMemory()
            val coordinator =
                buildCoordinator(
                    stateStore = stateStore,
                    settings = FakeAppSettingsRepository(udpAssociateEnabled = null),
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = "reality-1",
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                            ),
                            RelayProfileRecord(
                                id = "hysteria-1",
                                kind = RelayKindHysteria2,
                                udpEnabled = true,
                            ),
                        ),
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            FailoverEgressProbeResult(succeeded = false, failure = "udp_read_timeout")
                        },
                    egressHealthMemory = memory,
                ).coordinator
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 3,
                    networkScopeKey = "network-hash-a",
                ),
            )

            assertEquals(
                listOf(
                    RecordingSimpleEgressHealthMemory.Failure(
                        networkScopeKey = "network-hash-a",
                        proof = EgressProof.TcpUdp,
                        relayKind = RelayKindVlessReality,
                        profileId = "reality-1",
                    ),
                ),
                memory.failures,
            )
            coordinator.stopObserving()
        }

    @Test
    fun singleRealityCandidateStillRecordsConfirmedXudpFailure() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val memory = RecordingSimpleEgressHealthMemory()
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    settings = FakeAppSettingsRepository(udpAssociateEnabled = null),
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = "reality-only",
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                            ),
                        ),
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            FailoverEgressProbeResult(succeeded = false, failure = "udp_read_timeout")
                        },
                    egressHealthMemory = memory,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 3,
                    networkScopeKey = "network-hash-a",
                ),
            )

            assertEquals(1, memory.failures.size)
            assertTrue(controller.transportRestartCalls.isEmpty())
            coordinator.stopObserving()
        }

    @Test
    fun networkHandoverDoesNotPenalizeReality() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val memory = RecordingSimpleEgressHealthMemory()
            val coordinator =
                buildCoordinator(
                    stateStore = stateStore,
                    settings = FakeAppSettingsRepository(udpAssociateEnabled = null),
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = "reality-1",
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                            ),
                            RelayProfileRecord(
                                id = "hysteria-1",
                                kind = RelayKindHysteria2,
                                udpEnabled = true,
                            ),
                        ),
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            FailoverEgressProbeResult(succeeded = false, failure = "udp_read_timeout")
                        },
                    egressHealthMemory = memory,
                ).coordinator
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 3,
                    networkScopeKey = "network-hash-a",
                    networkHandoverState = "restarting",
                    failureClass = FailureClass.NetworkHandover,
                ),
            )

            assertTrue(memory.failures.isEmpty())
            coordinator.stopObserving()
        }

    @Test
    fun confirmedXudpFailureAfterRevalidatedHandoverIsPenalized() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val memory = RecordingSimpleEgressHealthMemory()
            val coordinator =
                buildCoordinator(
                    stateStore = stateStore,
                    settings = FakeAppSettingsRepository(udpAssociateEnabled = null),
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = "reality-1",
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                            ),
                            RelayProfileRecord(
                                id = "hysteria-1",
                                kind = RelayKindHysteria2,
                                udpEnabled = true,
                            ),
                        ),
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            FailoverEgressProbeResult(succeeded = false, failure = "udp_read_timeout")
                        },
                    egressHealthMemory = memory,
                ).coordinator
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 3,
                    networkScopeKey = "network-hash-b",
                    networkHandoverState = NetworkHandoverStates.Revalidated,
                    failureClass = FailureClass.NetworkHandover,
                ),
            )

            assertEquals(1, memory.failures.size)
            coordinator.stopObserving()
        }

    @Test
    fun tcpOnlySessionIgnoresXudpFailureStreak() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            var probeCalls = 0
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = FakeAppSettingsRepository(udpAssociateEnabled = false),
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            probeCalls++
                            FailoverEgressProbeResult(succeeded = false)
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            clock.advance(1_000L)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 10,
                ),
            )
            clock.advance(21_000L)
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    xudpConsecutiveFailures = 10,
                ),
            )
            advanceUntilIdle()

            assertEquals(0, probeCalls)
            assertEquals(0, controller.transportRestartCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun successfulProbeClearsProxyFailureLatchWithoutProbeStorm() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            var probeCalls = 0
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            probeCalls++
                            FailoverEgressProbeResult(succeeded = true)
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "running"))
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    proxyTotalErrors = 1,
                    proxyLastFailureClass = "silent_drop",
                ),
            )
            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    proxyTotalErrors = 1,
                    proxyLastFailureClass = "silent_drop",
                ),
            )

            assertEquals(1, probeCalls)
            assertEquals(0, controller.stopCalls.size)

            stateStore.emitTelemetry(
                runningTelemetry(
                    relayHealth = "running",
                    proxyTotalErrors = 2,
                    proxyLastFailureClass = "silent_drop",
                ),
            )
            assertEquals(2, probeCalls)
            assertEquals(0, controller.startCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun proxyErrorCounterResetEstablishesNewBaseline() =
        runTest {
            val stateStore = FakeServiceStateStore()
            var probeCalls = 0
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    egressProbe =
                        FailoverEgressProbe { _, _ ->
                            probeCalls++
                            FailoverEgressProbeResult(succeeded = true)
                        },
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(proxyTotalErrors = 5))
            stateStore.emitTelemetry(
                runningTelemetry(
                    proxyTotalErrors = 6,
                    proxyLastFailureClass = "silent_drop",
                ),
            )
            assertEquals(1, probeCalls)

            stateStore.emitTelemetry(
                runningTelemetry(
                    proxyTotalErrors = 0,
                    proxyLastFailureClass = "silent_drop",
                ),
            )
            assertEquals(1, probeCalls)
            assertEquals(0, controller.stopCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun transportSwitchRequestsInSessionRestartWithoutStoppingService() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val controller = FakeServiceController()
            val clock = FakeFailoverClock(now = 0L)
            val coordinator =
                buildCoordinator(
                    stateStore = stateStore,
                    controller = controller,
                    clock = clock,
                ).coordinator
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))

            assertEquals(listOf(Mode.VPN), controller.transportRestartCalls)
            assertEquals(0, controller.actualStopCalls.size)
            assertEquals(AppStatus.Running to Mode.VPN, stateStore.status.value)
            assertTrue(coordinator.shouldSkipInitialRelayRace())
            assertFalse(coordinator.shouldSkipInitialRelayRace())
            coordinator.stopObserving()
        }

    /**
     * A single blip (one "failed" then recovery) must NOT trigger a switch.
     */
    @Test
    fun transientBlipDoesNotSwitch() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val (coordinator, controller, _) = buildCoordinator(stateStore = stateStore, clock = clock)
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            // Single failure at 6s — debounce window (20s) not exceeded.
            clock.advance(6_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))

            // Recover at 8s — resets debounce.
            clock.advance(2_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            advanceUntilIdle()

            assertEquals("No stop expected for transient blip", 0, controller.stopCalls.size)
            assertEquals("No start expected for transient blip", 0, controller.startCalls.size)

            coordinator.stopObserving()
        }

    /**
     * A second switch is blocked by the anti-flap guard even when the debounce
     * window is exceeded, as long as FAILOVER_MIN_INTERVAL_MS has not elapsed.
     *
     * Timing:
     *   t=28000  SWITCH 1 fires; lastSwitchAt=28000
     *   t=29000  failed #1 in burst 2 → failingsSince=29000
     *   t=50000  failed #2 in burst 2 → elapsed=21000 >= 20000 (debounce exceeded)
     *            BUT 50000-28000=22000 < 30000 (min-interval) → BLOCKED
     */
    @Test
    fun minIntervalPreventsFlapping() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val (coordinator, controller, _) = buildCoordinator(stateStore = stateStore, clock = clock)
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            // Trigger switch 1: 4 x 7s; #4 at t=28s crosses 20s debounce.
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed")) // failingsSince=7000

            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))

            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))

            clock.advance(7_000L) // t=28000 → SWITCH 1; lastSwitchAt=28000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()

            assertEquals("First switch expected", 1, controller.stopCalls.size)

            // Reset debounce.
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            // Second burst: first failure at t=29s sets failingsSince=29000.
            clock.advance(1_000L) // t=29000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed")) // failingsSince=29000

            // t=50s: elapsed=21000 >= 20000 BUT 50-28=22s < 30s min-interval → BLOCKED.
            clock.advance(21_000L) // t=50000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()

            assertEquals("Second switch must be blocked by min-interval guard", 1, controller.stopCalls.size)

            coordinator.stopObserving()
        }

    /**
     * When setAutoFailoverEnabled(false), sustained failures produce zero switches.
     */
    @Test
    fun manualOverrideSuspendsSwitching() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val (coordinator, controller, _) = buildCoordinator(stateStore = stateStore, clock = clock)
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.setAutoFailoverEnabled(false)
            coordinator.startObserving(observeScope)

            repeat(5) {
                clock.advance(5_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertEquals("No switch when auto-failover disabled", 0, controller.stopCalls.size)

            coordinator.stopObserving()
        }

    /**
     * A single compatible candidate is exposed as active but cannot switch.
     */
    @Test
    fun fewerThanTwoCandidatesNeverSwitches() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    relayProfiles = listOf(RelayProfileRecord(id = "reality-1", kind = RelayKindVlessReality)),
                    awgProfiles = emptyList(),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            repeat(5) {
                clock.advance(5_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertNotNull("single compatible candidate must remain usable", coordinator.activeCandidate.value)
            assertEquals("No switch for <2 candidates", 0, controller.stopCalls.size)

            coordinator.stopObserving()
        }

    @Test
    fun `default UDP requirement keeps TCP primary ahead of UDP reserves`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository(udpAssociateEnabled = null)
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "UDP fallback",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = settings,
                    awgProfiles = listOf(awg),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            val initial = coordinator.activeCandidate.value
            check(initial is FailoverCandidate.Relay)
            assertEquals(RelayKindVlessReality, initial.relayKind)

            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()

            val fallback = coordinator.activeCandidate.value
            check(fallback is FailoverCandidate.Relay)
            assertEquals(RelayKindHysteria2, fallback.relayKind)
            assertEquals(1, controller.startCalls.size)
            coordinator.stopObserving()
        }

    @Test
    fun `XUDP-enabled Reality fails over through Hysteria to AWG`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository(udpAssociateEnabled = null)
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "XUDP fallback",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = settings,
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(
                                id = "reality-xudp",
                                kind = RelayKindVlessReality,
                                udpEnabled = true,
                            ),
                            RelayProfileRecord(id = "hysteria-xudp", kind = RelayKindHysteria2, udpEnabled = true),
                        ),
                    awgProfiles = listOf(awg),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            val initial = coordinator.activeCandidate.value
            check(initial is FailoverCandidate.Relay)
            assertEquals(RelayKindVlessReality, initial.relayKind)

            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()
            val afterReality = coordinator.activeCandidate.value
            check(afterReality is FailoverCandidate.Relay)
            assertEquals(RelayKindHysteria2, afterReality.relayKind)

            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(31_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()

            assertTrue(coordinator.activeCandidate.value is FailoverCandidate.Awg)
            assertEquals(2, controller.startCalls.size)
            assertEquals(
                TransportFailoverTarget(TransportKindAmneziaWg, SIMPLE_SEED_AWG_PROFILE_ID),
                controller.transportRestartTargets.last(),
            )
            coordinator.stopObserving()
        }

    @Test
    fun `single AWG is a valid UDP fallback candidate`() =
        runTest {
            val settings = FakeAppSettingsRepository(udpAssociateEnabled = null)
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Only AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    relayProfiles = emptyList(),
                    awgProfiles = listOf(awg),
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            assertTrue(coordinator.activeCandidate.value is FailoverCandidate.Awg)
            assertTrue(controller.startCalls.isEmpty())
            coordinator.stopObserving()
        }

    @Test
    fun `single AWG starts during capability failure recovery`() =
        runTest {
            val stateStore = FakeServiceStateStore(initialStatus = AppStatus.Reconnecting)
            val settings = FakeAppSettingsRepository(udpAssociateEnabled = null)
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Only startup fallback",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId("incompatible-reality")
            }
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    relayProfiles = emptyList(),
                    awgProfiles = listOf(awg),
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            coordinator.bind(observeScope)

            stateStore.emitFailure(FailureReason.InitialTransportSelectionFailed("capability mismatch"))
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), controller.startCalls)
            assertFalse(settings.relayEnabled())
            assertEquals(awg.id, settings.simpleFailoverAwgProfileId())
        }

    @Test
    fun `UDP requirement retains a TCP-only degraded candidate`() =
        runTest {
            val settings = FakeAppSettingsRepository(udpAssociateEnabled = null)
            val (coordinator, controller, _) =
                buildCoordinator(
                    relayProfiles = listOf(RelayProfileRecord(id = "reality-only", kind = RelayKindVlessReality)),
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            val active = coordinator.activeCandidate.value
            check(active is FailoverCandidate.Relay)
            assertEquals("reality-only", active.profileId)
            assertFalse(active.supportsUdpAssociation)
            assertTrue(controller.startCalls.isEmpty())
            coordinator.stopObserving()
        }

    /**
     * After exhausting all candidates under continuous failure, the coordinator backs off
     * and stops issuing further switches.
     *
     * NOTE: a healthy emission resets switchesInCycle and clears backedOff, so we must
     * NOT emit healthy between switches — the budget would be reset and switch 3 would fire.
     * Instead we drive all failure continuously and let debounce + min-interval govern pacing.
     *
     * Three candidates: Reality(0) > Hysteria2(1) > AWG(2).
     * switchesInCycle budget: backs off when switchesInCycle >= candidates.size - 1 = 2.
     *
     * Timing (FakeFailoverClock, debounce=20 000 ms, min-interval=30 000 ms):
     *   All emissions: runningTelemetry(relayHealth="failed", awgHealth="failed")
     *   t=1000   failed #1 → failingsSince=1000
     *   t=8000   failed #2 → elapsed=7 000 < 20 000
     *   t=15000  failed #3 → elapsed=14 000 < 20 000
     *   t=22000  failed #4 → elapsed=21 000 >= 20 000, no prior switch → SWITCH 1 (Reality→Hysteria2)
     *            lastSwitchAt=22 000, switchesInCycle=1, failingsSince=null
     *   t=23000  failed  → failingsSince=23 000
     *   t=30000  failed  → elapsed=7 000 < 20 000
     *   t=37000  failed  → elapsed=14 000 < 20 000
     *   t=44000  failed  → elapsed=21 000 >= 20 000, BUT 44 000-22 000=22 000 < 30 000 → BLOCKED
     *   t=54000  failed  → elapsed=31 000 >= 20 000, 54 000-22 000=32 000 >= 30 000 → SWITCH 2 (Hysteria2→AWG)
     *            lastSwitchAt=54 000, switchesInCycle=2, failingsSince=null
     *   t=55000  failed  → failingsSince=55 000
     *   t=62000  failed  → elapsed=7 000 < 20 000
     *   t=69000  failed  → elapsed=14 000 < 20 000
     *   t=76000  failed  → elapsed=21 000 >= 20 000, BUT 76 000-54 000=22 000 < 30 000 → BLOCKED
     *   t=86000  failed  → elapsed=31 000 >= 20 000, 86 000-54 000=32 000 >= 30 000
     *            → enters performSwitch → switchesInCycle=2 >= 2 → BACKOFF (no switch 3)
     */
    @Test
    fun exhaustingAllCandidatesBacksOff() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val awgEntity =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Test AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    awgProfiles = listOf(awgEntity),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            // Continuous failure throughout — no healthy emission (that would reset budget).
            fun bothFailed() = runningTelemetry(relayHealth = "failed", awgHealth = "failed")

            // ── Switch 1: Reality → Hysteria2 ─────────────────────────────────
            // t=1000: failingsSince=1000
            clock.advance(1_000L)
            stateStore.emitTelemetry(bothFailed())
            // t=8000, t=15000: debounce not yet exceeded
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            // t=22000: elapsed=21s >= 20s, no prior switch → SWITCH 1
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("Switch 1 expected", 1, controller.stopCalls.size)

            // ── Switch 2: Hysteria2 → AWG ──────────────────────────────────────
            // t=23000: failingsSince=23000
            clock.advance(1_000L)
            stateStore.emitTelemetry(bothFailed())
            // t=30000, t=37000, t=44000: debounce exceeded but min-interval (22s < 30s) blocks
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("Switch 2 must be blocked by min-interval at t=44s", 1, controller.stopCalls.size)
            // t=54000: elapsed=31s >= 20s AND 54-22=32s >= 30s → SWITCH 2
            clock.advance(10_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("Switch 2 expected", 2, controller.stopCalls.size)

            // Verify we are now on AWG.
            val afterSwitch2 = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null after switch 2", afterSwitch2)
            check(afterSwitch2 is FailoverCandidate.Awg) {
                "Expected AWG candidate after switch 2, got $afterSwitch2"
            }

            // ── Switch 3 attempt: backed off because switchesInCycle=2 >= candidates.size-1=2 ──
            // t=55000: failingsSince=55000
            clock.advance(1_000L)
            stateStore.emitTelemetry(bothFailed())
            // t=62000, t=69000, t=76000: debounce exceeded but min-interval blocks
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("Switch 3 must be blocked by min-interval at t=76s", 2, controller.stopCalls.size)
            // t=86000: elapsed=31s >= 20s AND 86-54=32s >= 30s → enters performSwitch → BACKOFF
            clock.advance(10_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("No switch 3 — coordinator must back off", 2, controller.stopCalls.size)

            coordinator.stopObserving()
        }

    /**
     * activeCandidate is non-null after startObserving with >= 2 candidates.
     */
    @Test
    fun activeCandidateExposedAfterStart() =
        runTest {
            val (coordinator, _, _) = buildCoordinator()
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            val candidate = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null with >=2 candidates", candidate)
            check(candidate is FailoverCandidate.Relay)
            assertEquals(RelayKindVlessReality, candidate.relayKind)

            coordinator.stopObserving()
        }

    /**
     * stopObserving resets activeCandidate to null.
     */
    @Test
    fun stopObservingResetsActiveCandidate() =
        runTest {
            val (coordinator, _, _) = buildCoordinator()
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            assertNotNull(coordinator.activeCandidate.value)

            coordinator.stopObserving()

            assertNull("activeCandidate must be null after stopObserving", coordinator.activeCandidate.value)
        }

    /**
     * When settings already point at Hysteria2, startObserving resumes at index 1
     * (not 0). A subsequent sustained failure switches to AWG (index 2), not back
     * to Reality (which would loop REALITY→Hysteria2→REALITY forever).
     *
     * Candidate list: Reality(0) > Hysteria2(1) > AWG(2).
     * Settings: relayEnabled=true, relayKind=hysteria2 → resumeIndex()=1.
     * Healthy emit before failure burst: resets debounce only, does NOT affect
     * switchesInCycle (still 0 → switch to AWG proceeds normally).
     * Switch: Hysteria2 → AWG (nextIndex=2, not 0 wrap → switchesInCycle=1 < 2).
     */
    @Test
    fun resumesAtConfiguredTransport() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            val awgEntity =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Resume AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            // Seed settings to Hysteria2 so coordinator resumes at index 1.
            settings.update {
                setEnableCmdSettings(true)
                setRelayEnabled(true)
                setRelayKind(RelayKindHysteria2)
            }
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    awgProfiles = listOf(awgEntity),
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            // Verify resume landed at Hysteria2 (index 1), not Reality (index 0).
            val active = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null", active)
            check(active is FailoverCandidate.Relay)
            assertEquals("Must resume at Hysteria2", RelayKindHysteria2, active.relayKind)

            // Healthy baseline then sustained failure → switch from Hysteria2 to AWG.
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            // Switch must go to AWG (index 2), not loop back to Reality (index 0).
            assertEquals("Expected one switch", 1, controller.stopCalls.size)
            val afterSwitch = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null after switch", afterSwitch)
            check(afterSwitch is FailoverCandidate.Awg) {
                "Expected AWG candidate after switch from Hysteria2, got $afterSwitch"
            }
            assertEquals("AWG profile id must match", SIMPLE_SEED_AWG_PROFILE_ID, afterSwitch.awgProfileId)
            assertFalse(settings.snapshot().enableCmdSettings)

            coordinator.stopObserving()
        }

    @Test
    fun `duplicate reality and xhttp candidates resume and fail over by profile id`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            val fallbackId = "simple-seed-VlessReality-2"
            val xhttpId = "simple-seed-Vless"
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(fallbackId)
            }
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = settings,
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(id = "simple-seed-VlessReality", kind = RelayKindVlessReality),
                            RelayProfileRecord(id = fallbackId, kind = RelayKindVlessReality),
                            RelayProfileRecord(
                                id = xhttpId,
                                kind = RelayKindVless,
                                vlessTransport = RelayVlessTransportXhttp,
                            ),
                            RelayProfileRecord(id = "simple-seed-Hysteria2", kind = RelayKindHysteria2),
                        ),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            val resumed = coordinator.activeCandidate.value
            check(resumed is FailoverCandidate.Relay)
            assertEquals(fallbackId, resumed.profileId)

            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertEquals(1, controller.stopCalls.size)
            val switched = coordinator.activeCandidate.value
            check(switched is FailoverCandidate.Relay)
            assertEquals(RelayKindVless, switched.relayKind)
            assertEquals(xhttpId, switched.profileId)
            assertEquals(xhttpId, settings.relayProfileId())
            assertEquals(
                ActiveTransportDescriptor(
                    protocolKind = RelayKindVless,
                    vlessTransport = RelayVlessTransportXhttp,
                ),
                coordinator.activeTransport.value,
            )

            coordinator.stopObserving()
        }

    @Test
    fun genericVlessDescriptorIsNotMarkedAsXhttp() {
        val descriptor =
            FailoverCandidate
                .Relay(
                    priority = 0,
                    profileId = "generic-vless",
                    relayKind = RelayKindVless,
                    vlessTransport = RelayVlessTransportRealityTcp,
                ).toActiveTransportDescriptor()

        assertEquals(RelayKindVless, descriptor.protocolKind)
        assertEquals(RelayVlessTransportRealityTcp, descriptor.vlessTransport)
        assertFalse(descriptor.vlessTransport == RelayVlessTransportXhttp)
    }

    /**
     * An in-session restart preserves activeCandidateIndex, switchesInCycle, and
     * lastSwitchAt because observation never leaves the Running VPN session.
     *
     * Sequence:
     *   1. startObserving → Reality active (index 0, switchesInCycle=0)
     *   2. sustained failure → SWITCH 1: Reality→Hysteria2 (switchesInCycle=1)
     *   3. in-session restart keeps index 1, switchesInCycle=1, lastSwitchAt preserved
     *   4. continuous failure (no healthy) → need debounce AND min-interval
     *      → SWITCH 2: Hysteria2→AWG (switchesInCycle=2)
     *   5. continuous failure → switchesInCycle=2 >= candidates.size-1=2 → BACKOFF
     *      total 2 stops, not 3
     *
     * Timing (debounce=20 000 ms, min-interval=30 000 ms):
     *   t=22 000  SWITCH 1; lastSwitchAt=22 000, switchesInCycle=1
     *   t=23 500  failed → failingsSince=23 500
     *   t=54 500  failed → elapsed=31 000 >= 20 000, 54 500-22 000=32 500 >= 30 000 → SWITCH 2
     *   t=86 500  failed → switchesInCycle=2 >= 2 → BACKOFF, no switch 3
     */
    @Test
    fun budgetSurvivesInSessionRestart() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val awgEntity =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Budget AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    awgProfiles = listOf(awgEntity),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            // ── Switch 1: Reality → Hysteria2 ─────────────────────────────────
            // t=1000: failingsSince=1000
            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            // t=8000, t=15000: debounce not exceeded
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            // t=22000: elapsed=21s >= 20s → SWITCH 1
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()
            assertEquals("Switch 1 expected", 1, controller.stopCalls.size)

            // The service recomposes in-session; the observer and budget stay active.
            clock.advance(500L)

            // activeCandidate remains Hysteria2 (index preserved, NOT reset to Reality).
            val afterRestart = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null after self-restart", afterRestart)
            check(afterRestart is FailoverCandidate.Relay) {
                "Expected Relay candidate after self-restart, got $afterRestart"
            }
            assertEquals(
                "Index must be preserved at Hysteria2 after self-restart",
                RelayKindHysteria2,
                afterRestart.relayKind,
            )

            // ── Switch 2: Hysteria2 → AWG (budget preserved, no healthy) ──────
            // t=23500: failingsSince=23500
            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            // t=30500, t=37500, t=44500: debounce exceeded but min-interval blocks (22-32s < 30s)
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            advanceUntilIdle()
            assertEquals("Switch 2 blocked by min-interval", 1, controller.stopCalls.size)
            // t=54500: 54500-22000=32500 >= 30000 → SWITCH 2
            clock.advance(10_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            advanceUntilIdle()
            assertEquals("Switch 2 expected", 2, controller.stopCalls.size)

            val afterSwitch2 = coordinator.activeCandidate.value
            assertNotNull("activeCandidate non-null after switch 2", afterSwitch2)
            check(afterSwitch2 is FailoverCandidate.Awg) {
                "Expected AWG after switch 2, got $afterSwitch2"
            }

            // ── Switch 3 attempt: switchesInCycle=2 >= candidates.size-1=2 → BACKOFF ──
            // t=55500: failingsSince=55500
            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            advanceUntilIdle()
            assertEquals("Switch 3 blocked by min-interval", 2, controller.stopCalls.size)
            // t=86500: 86500-54500=32000 >= 30000 → enters performSwitch → BACKOFF
            clock.advance(10_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed", awgHealth = "failed"))
            advanceUntilIdle()
            assertEquals("No switch 3 — budget exhausted, coordinator backed off", 2, controller.stopCalls.size)

            coordinator.stopObserving()
        }

    /**
     * [FailoverCoordinator.bind] drives observation from service status transitions.
     *
     * Before any status: activeCandidate is null.
     * After (Running, VPN): startObserving fires → activeCandidate non-null (Reality).
     * After (Halted, VPN): stopObserving fires → activeCandidate null again.
     */
    @Test
    fun `bind drives observation from service status`() =
        runTest {
            val stateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted)
            val (coordinator, _, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(id = "reality-1", kind = RelayKindVlessReality),
                            RelayProfileRecord(id = "hysteria-1", kind = RelayKindHysteria2),
                        ),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.bind(observeScope)
            advanceUntilIdle()

            assertNull("activeCandidate must be null before Running,VPN", coordinator.activeCandidate.value)

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            advanceUntilIdle()

            val candidate = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null after Running,VPN", candidate)
            check(candidate is FailoverCandidate.Relay)
            assertEquals("Must start at Reality (highest priority)", RelayKindVlessReality, candidate.relayKind)

            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertNull("activeCandidate must be null after Halted,VPN", coordinator.activeCandidate.value)
        }

    /**
     * [FailoverCoordinator.bind] must not start observing for non-VPN running status.
     *
     * (Running, PROXY) must leave activeCandidate null — failover is VPN-session-only.
     */
    @Test
    fun `bind ignores non-VPN running`() =
        runTest {
            val stateStore = FakeServiceStateStore(initialStatus = AppStatus.Halted)
            val (coordinator, _, _) = buildCoordinator(stateStore = stateStore)
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.bind(observeScope)
            advanceUntilIdle()

            stateStore.setStatus(AppStatus.Running, Mode.Proxy)
            advanceUntilIdle()

            assertNull(
                "activeCandidate must stay null for Running,Proxy",
                coordinator.activeCandidate.value,
            )
        }

    @Test
    fun `startup transport failure advances persisted candidate before retry`() =
        runTest {
            val stateStore = FakeServiceStateStore(initialStatus = AppStatus.Reconnecting)
            val settings = FakeAppSettingsRepository()
            val awgEntity =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Startup failure AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            settings.update {
                setRelayEnabled(false)
                setSimpleFailoverAwgProfileId(awgEntity.id)
            }
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    settings = settings,
                    awgProfiles = listOf(awgEntity),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.bind(observeScope)
            stateStore.emitFailure(FailureReason.NativeError("transport readiness timed out"))
            assertEquals("Retry must wait until failed startup is fully halted", 0, controller.startCalls.size)

            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), controller.startCalls)
            assertEquals(0, controller.actualStopCalls.size)
            assertEquals(0, controller.transportRestartCalls.size)
            assertTrue("Startup recovery must select a relay candidate", settings.relayEnabled())
            assertEquals("reality-1", settings.relayProfileId())
            assertEquals(
                "Startup retry must bypass a second initial race",
                true,
                coordinator.shouldSkipInitialRelayRace(),
            )
            assertEquals("Initial-race bypass must be one-shot", false, coordinator.shouldSkipInitialRelayRace())
        }

    @Test
    fun `initial relay readiness failure advances to TCP xHTTP before UDP fallbacks`() =
        runTest {
            val stateStore = FakeServiceStateStore(initialStatus = AppStatus.Reconnecting)
            val settings = FakeAppSettingsRepository(udpAssociateEnabled = null)
            val awg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "AWG after Hysteria",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId("reality-1")
            }
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    settings = settings,
                    relayProfiles =
                        listOf(
                            RelayProfileRecord(id = "reality-1", kind = RelayKindVlessReality),
                            RelayProfileRecord(
                                id = "simple-seed-Vless",
                                kind = RelayKindVless,
                                vlessTransport = RelayVlessTransportXhttp,
                            ),
                            RelayProfileRecord(
                                id = "simple-seed-Hysteria2",
                                kind = RelayKindHysteria2,
                                udpEnabled = true,
                            ),
                        ),
                    awgProfiles = listOf(awg),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            coordinator.bind(observeScope)

            stateStore.emitFailure(FailureReason.InitialTransportSelectionFailed("preflight failed"))
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), controller.startCalls)
            assertTrue(settings.relayEnabled())
            assertEquals(RelayKindVless, settings.relayKind())
            assertEquals("simple-seed-Vless", settings.relayProfileId())
            assertEquals("", settings.simpleFailoverAwgProfileId())
        }

    /**
     * Switching to a relay candidate writes relay settings via [writeConfig].
     *
     * After a sustained failure that triggers one Reality→Hysteria2 switch,
     * [AppSettingsRepository] must reflect relayEnabled=true and relayKind=hysteria2.
     * This proves [writeConfig] actually persists settings, not just the in-memory StateFlow.
     *
     * Timing matches [sustainedFailureTriggersSingleSwitch]:
     *   4 x 7 s advances; switch fires at t=28 000 ms.
     */
    @Test
    fun `relay switch writes relay settings`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            val (coordinator, _, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            // 4 × 7 s — matches sustainedFailureTriggersSingleSwitch timing.
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertTrue("relayEnabled must be true after relay switch", settings.relayEnabled())
            assertEquals(
                "relay switch must clear any durable AWG failover selector",
                "",
                settings.simpleFailoverAwgProfileId(),
            )
            assertEquals(
                "relayKind must be Hysteria2 after switch from Reality",
                RelayKindHysteria2,
                settings.relayKind(),
            )

            coordinator.stopObserving()
        }

    /**
     * Switching to an AWG candidate disables relay in settings and selects an AWG
     * activation request via [SimpleAwgEgressSelection].
     *
     * After driving failover all the way to the AWG candidate (copying the
     * timing from [budgetSurvivesSelfRestart] up to switch 2), settings must
     * reflect relayEnabled=false, and the service resolver bridge must expose the
     * rehydrated AWG request that [SharedProxyRuntimeStack] consumes via awgConfigOrNull.
     *
     * Timing (debounce=20 000 ms, min-interval=30 000 ms):
     *   t=22 000  SWITCH 1: Reality→Hysteria2
     *   t=54 000  SWITCH 2: Hysteria2→AWG  → relayEnabled=false
     */
    @Test
    fun `awg switch disables relay and selects awg egress`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val settings = FakeAppSettingsRepository()
            val awgEntity =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Settings AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, _, _, awgSelection) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    awgProfiles = listOf(awgEntity),
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            fun bothFailed() = runningTelemetry(relayHealth = "failed", awgHealth = "failed")

            // ── Switch 1: Reality → Hysteria2 at t=22 000 ────────────────────
            clock.advance(1_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()

            // ── Switch 2: Hysteria2 → AWG at t=54 000 ─────────────────────────
            // t=23 000: failingsSince=23 000
            clock.advance(1_000L)
            stateStore.emitTelemetry(bothFailed())
            // t=30 000, 37 000, 44 000: debounce exceeded but min-interval blocks
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            // t=54 000: 54-22=32s >= 30s min-interval → SWITCH 2
            clock.advance(10_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()

            val active = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null after switch 2", active)
            check(active is FailoverCandidate.Awg) {
                "Expected AWG candidate after switch 2, got $active"
            }

            assertFalse("relayEnabled must be false after AWG switch", settings.relayEnabled())
            assertEquals(
                "AWG switch must persist the AWG failover selector",
                SIMPLE_SEED_AWG_PROFILE_ID,
                settings.simpleFailoverAwgProfileId(),
            )
            val selectedAwg = awgSelection.selectedAwgEgress()
            assertNotNull("AWG switch must expose a selected AWG egress", selectedAwg)
            assertEquals(
                "AWG profile id must be rehydrated from repository",
                SIMPLE_SEED_AWG_PROFILE_ID,
                selectedAwg?.profileId,
            )

            coordinator.stopObserving()
        }

    @Test
    fun `resolved startup fallback request is exposed to connection policy`() =
        runTest {
            val fixture = buildCoordinator()
            val request =
                AwgActivationRequest(
                    profileId = "awg-startup",
                    privateKey = "private",
                    peerPublicKey = "peer",
                    endpointHost = "198.51.100.10",
                    endpointPort = 51820,
                    interfaceAddressV4 = "10.8.0.2/32",
                )

            fixture.awgSelection.select(request)

            assertEquals(request, fixture.awgSelection.selectedAwgEgress())
        }

    @Test
    fun `simple selection suppresses standalone AWG while primary relay is active`() =
        runTest {
            val fixture = buildCoordinator()

            assertTrue(fixture.awgSelection.suppressesLowerPrioritySelections)
            assertNull(fixture.awgSelection.selectedAwgEgress())
        }

    @Test
    fun `automatic AWG fallback selects seeded profile instead of last modified profile`() =
        runTest {
            val lastModified =
                AwgProfileEntity(
                    id = "awg-user-last-modified",
                    name = "User AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 2L,
                )
            val seeded =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Bundled AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val fixture =
                buildCoordinator(
                    relayProfiles = emptyList(),
                    awgProfiles = listOf(lastModified, seeded),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)

            assertEquals(SIMPLE_SEED_AWG_PROFILE_ID, fixture.awgSelection.firstAvailable()?.profileId)
            val active = fixture.coordinator.activeCandidate.value
            check(active is FailoverCandidate.Awg)
            assertEquals(SIMPLE_SEED_AWG_PROFILE_ID, active.awgProfileId)
            fixture.coordinator.stopObserving()
        }

    @Test
    fun `automatic AWG fallback does not substitute a user profile when seed is missing`() =
        runTest {
            val userProfile =
                AwgProfileEntity(
                    id = "awg-user-only",
                    name = "User AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 2L,
                )
            val fixture =
                buildCoordinator(
                    relayProfiles = emptyList(),
                    awgProfiles = listOf(userProfile),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            fixture.coordinator.startObserving(observeScope)

            assertNull(fixture.awgSelection.firstAvailable())
            assertNull(fixture.coordinator.activeCandidate.value)
            fixture.coordinator.stopObserving()
        }

    @Test
    fun `simple AWG fallback has runtime override priority`() =
        runTest {
            val fixture = buildCoordinator()

            assertEquals(0, fixture.awgSelection.selectionPriority)
        }

    /**
     * Cold-start regression: relayEnabled=false alone is ambiguous because a default install also
     * has relay disabled. When the explicit AWG selector is present, the coordinator must resume on
     * the AWG candidate and [SimpleAwgEgressSelection] must rehydrate the request from the durable
     * profile store instead of relying on the process-local cache.
     */
    @Test
    fun `persisted awg selector resumes awg after cold start`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(false)
                setSimpleFailoverAwgProfileId("awg-cold")
            }
            val awgEntity =
                AwgProfileEntity(
                    id = "awg-cold",
                    name = "Cold AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val seededAwg =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Bundled AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 2L,
                )
            val (coordinator, controller, _, awgSelection) =
                buildCoordinator(
                    stateStore = stateStore,
                    awgProfiles = listOf(seededAwg, awgEntity),
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            val active = coordinator.activeCandidate.value
            assertNotNull("activeCandidate must be non-null", active)
            check(active is FailoverCandidate.Awg) {
                "Expected persisted AWG selector to resume AWG candidate, got $active"
            }
            assertEquals("awg-cold", active.awgProfileId)

            val selectedAwg = awgSelection.selectedAwgEgress()
            assertNotNull("durable AWG selector must rehydrate a request", selectedAwg)
            assertEquals("awg-cold", selectedAwg?.profileId)

            stateStore.emitTelemetry(runningTelemetry(awgHealth = "failed"))
            advanceUntilIdle()
            assertTrue(controller.transportRestartCalls.isEmpty())

            coordinator.stopObserving()
        }

    @Test
    fun `explicit diagnostic AWG startup failure remains manual when seed is missing`() =
        runTest {
            val stateStore = FakeServiceStateStore(initialStatus = AppStatus.Reconnecting)
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(false)
                setSimpleFailoverAwgProfileId("awg-diagnostic")
            }
            val diagnosticAwg =
                AwgProfileEntity(
                    id = "awg-diagnostic",
                    name = "Diagnostic AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    awgProfiles = listOf(diagnosticAwg),
                    settings = settings,
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.bind(observeScope)
            stateStore.emitFailure(FailureReason.NativeError("diagnostic startup failed"))
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertTrue(controller.startCalls.isEmpty())
            assertEquals("awg-diagnostic", settings.simpleFailoverAwgProfileId())
        }

    /** A genuine user stop resets the budget retained by in-session transport failover. */
    @Test
    fun `genuine user restart resets back-off budget`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val awgEntity =
                AwgProfileEntity(
                    id = "awg-reset",
                    name = "Reset AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    awgProfiles = listOf(awgEntity),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            // ── Switch 1: Reality → Hysteria2 at t=22 000 ────────────────────
            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(7_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()
            assertEquals("Switch 1 expected", 1, controller.stopCalls.size)

            // ── Genuine user stop at t=23 000 ────────────────────────────────
            clock.advance(1_000L) // t=23 000
            coordinator.stopObserving()

            // ── Fresh startObserving: budget reset ───────────────────────────
            coordinator.startObserving(observeScope)
            advanceUntilIdle()

            // resumeIndex() reads settings: relayEnabled=true, relayKind=Hysteria2
            // (written by writeConfig for switch 1). So index resumes at Hysteria2 (1).
            // That is fine — the test only verifies the budget (switchesInCycle) was reset,
            // not the starting index. The budget reset is proven by the switch firing below.

            // ── Fresh failure burst: switch must fire (budget was reset) ──────
            // lastSwitchAt=0 (reset) → min-interval guard skipped.
            // Need debounce (20 s) only.
            // Current telemetry StateFlow holds last failed value → sets failingsSince=23 000.
            // Advance 4×7s to t=51 000: elapsed=28 000 >= 20 000 → SWITCH (budget fresh).
            clock.advance(7_000L) // t=30 000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(7_000L) // t=37 000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(7_000L) // t=44 000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(7_000L) // t=51 000: elapsed from 23 000 = 28 000 >= 20 000 → SWITCH
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()

            assertTrue(
                "Budget must be reset: switch count must exceed 1 after genuine restart",
                controller.stopCalls.size > 1,
            )

            coordinator.stopObserving()
        }

    /**
     * A HEALTHY telemetry emission while the coordinator is backed off must clear back-off
     * and resume failover WITHOUT a session restart — the in-session recovery path (the other
     * is [backedOff resets on stop and restart]).
     *
     * Two candidates Reality(0) > Hysteria2(1): back-off engages on the 2nd switch attempt
     * (switchesInCycle >= candidates.size-1 = 1).
     *
     * Timing (debounce=20 000 ms, min-interval=30 000 ms):
     *   t=1 000   failed → failingsSince=1 000
     *   t=22 000  failed → elapsed 21s ≥ 20s, no prior switch → SWITCH 1; lastSwitchAt=22 000, switchesInCycle=1
     *   t=23 000  failed → failingsSince=23 000
     *   t=44 000  failed → 44-22=22s < 30s min-interval → blocked
     *   t=53 000  failed → 53-22=31s ≥ 30s → performSwitch sees switchesInCycle 1 ≥ 1 → BACKOFF (no stop)
     *   t=53 500  HEALTHY → backedOff=false, switchesInCycle=0, failingsSince=null
     *   t=54 000  failed → failingsSince=54 000
     *   t=75 000  failed → elapsed 21s ≥ 20s, 75-22=53s ≥ 30s → SWITCH 2 (recovery proven)
     */
    @Test
    fun `healthyEmissionWhileBackedOffClearsBackOff`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            // Default 2 relay candidates (Reality, Hysteria2), no AWG → back-off after 1 switch.
            val (coordinator, controller, _) = buildCoordinator(stateStore = stateStore, clock = clock)
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            // ── Drive to back-off: one real switch, then the budget is exhausted ──
            clock.advance(1_000L)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L) // t=22 000 → SWITCH 1 (Reality→Hysteria2)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()
            assertEquals("Switch 1 expected", 1, controller.stopCalls.size)

            clock.advance(1_000L) // t=23 000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L) // t=44 000 → blocked by min-interval
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()
            assertEquals("Switch blocked by min-interval", 1, controller.stopCalls.size)
            clock.advance(9_000L) // t=53 000 → performSwitch → BACKOFF (switchesInCycle 1 >= 1)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()
            assertEquals("No switch — backed off", 1, controller.stopCalls.size)

            // ── Healthy emission while backed off clears back-off ──
            clock.advance(500L) // t=53 500
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            // ── Fresh failure burst → switch fires, proving in-session recovery ──
            clock.advance(500L) // t=54 000
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            clock.advance(21_000L) // t=75 000 → SWITCH 2
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            advanceUntilIdle()
            assertEquals(
                "Switch must fire: healthy emission while backed off cleared back-off",
                2,
                controller.stopCalls.size,
            )

            coordinator.stopObserving()
        }

    /**
     * After back-off, a genuine stop+startObserving resets the budget so that
     * a subsequent sustained failure burst can trigger a fresh switch.
     *
     * This test exercises the stop+restart recovery path specifically (it never emits a
     * healthy snapshot while backed off). A genuine stopObserving + startObserving goes
     * through the "fresh session" branch that resets switchesInCycle=0, backedOff=false,
     * and lastSwitchAt=0. (A healthy emission while backed off is the OTHER recovery path —
     * see [healthyEmissionWhileBackedOffClearsBackOff].)
     *
     * Sequence:
     *   1. Drive to backedOff (exhaust all 3 candidates).
     *   2. Genuine stopObserving resets the budget.
     *   3. startObserving opens a fresh cycle (switchesInCycle=0, lastSwitchAt=0).
     *   4. Sustained failure burst triggers a switch, proving back-off was cleared.
     *
     * Timing (debounce=20 000 ms, min-interval=30 000 ms):
     *   t=22 000  SWITCH 1 (Reality→Hysteria2)
     *   t=54 500  SWITCH 2 (Hysteria2→AWG)
     *   t=87 000  Back-off attempt (switchesInCycle=2 >= 2) → backedOff=true, no stop
     *   t=87 500  Genuine stopObserving → budget reset
     *   t=87 500  startObserving fresh → switchesInCycle=0, lastSwitchAt=0
     *   t=108 500 Fresh burst → SWITCH fires
     */
    @Test
    fun `backedOff resets on stop and restart`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val awgEntity =
                AwgProfileEntity(
                    id = SIMPLE_SEED_AWG_PROFILE_ID,
                    name = "Backoff Reset AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            val (coordinator, controller, _) =
                buildCoordinator(
                    stateStore = stateStore,
                    clock = clock,
                    awgProfiles = listOf(awgEntity),
                )
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            coordinator.startObserving(observeScope)

            fun bothFailed() = runningTelemetry(relayHealth = "failed", awgHealth = "failed")

            // ── Switch 1: Reality → Hysteria2 at t=22 000 ─────────────────────
            clock.advance(1_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L)
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("Switch 1 expected", 1, controller.stopCalls.size)

            // In-session restart preserves budget; a fresh failure starts the next debounce.
            clock.advance(500L) // t=22 500
            stateStore.emitTelemetry(bothFailed())

            // ── Switch 2: Hysteria2 → AWG at t=54 500 ─────────────────────────
            // failingsSince was set by the t=22 500 failure.
            // Advance to t=54 500: elapsed = 54 500-22 500 = 32 000 >= 20 000
            // AND 54 500-22 000 = 32 500 >= 30 000 → SWITCH 2.
            clock.advance(32_000L) // t=54 500
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("Switch 2 expected", 2, controller.stopCalls.size)

            // The next in-session candidate starts a fresh debounce window.
            clock.advance(500L) // t=55 000
            stateStore.emitTelemetry(bothFailed())

            // ── Back-off at t=87 000 ───────────────────────────────────────────
            // switchesInCycle=2 >= candidates.size-1=2 → performSwitch sets backedOff=true.
            // elapsed from failingsSince (t=55 000 immediate) = 32 000 >= 20 000
            // AND 87 000-54 500=32 500 >= 30 000 → enters performSwitch → BACKOFF.
            clock.advance(32_000L) // t=87 000
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()
            assertEquals("No switch 3 — coordinator must back off", 2, controller.stopCalls.size)

            // ── Genuine stop at t=87 500 ──────────────────────────────────────
            // A genuine session stop resets the budget.
            clock.advance(500L) // t=87 500
            coordinator.stopObserving()

            // ── Fresh startObserving: budget reset ────────────────────────────
            coordinator.startObserving(observeScope)
            advanceUntilIdle()

            // ── Fresh failure burst → SWITCH must fire ────────────────────────
            // lastSwitchAt=0 (reset) → min-interval guard skipped.
            // Current StateFlow still has bothFailed() → failingsSince=87 500 set immediately.
            // 4×7 000 ms → t=115 500; elapsed = 115 500-87 500 = 28 000 >= 20 000 → SWITCH.
            clock.advance(7_000L) // t=94 500
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L) // t=101 500
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L) // t=108 500
            stateStore.emitTelemetry(bothFailed())
            clock.advance(7_000L) // t=115 500: elapsed=28 000 >= 20 000 → SWITCH
            stateStore.emitTelemetry(bothFailed())
            advanceUntilIdle()

            assertTrue(
                "Switch must fire after stop+restart clears backedOff",
                controller.stopCalls.size > 2,
            )

            coordinator.stopObserving()
        }

    /**
     * Manual override (setAutoFailoverEnabled) gates switching symmetrically:
     * disabled → no switch; re-enabled → sustained failure triggers a switch.
     *
     * This is distinct from [manualOverrideSuspendsSwitching] which only tests
     * the disabled direction. Here we also verify the re-enable path.
     *
     * Timing:
     *   Phase 1 (disabled): 4 × 7 s → no switch expected.
     *   Re-enable + healthy reset.
     *   Phase 2 (enabled): advance clock to min-interval boundary + 4 × 7 s → switch fires.
     */
    @Test
    fun `manual override re-enable resumes switching`() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val (coordinator, controller, _) = buildCoordinator(stateStore = stateStore, clock = clock)
            val observeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            // Disable auto-failover before starting.
            coordinator.setAutoFailoverEnabled(false)
            coordinator.startObserving(observeScope)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            // Phase 1: sustained failure with auto-failover disabled → no switch.
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertEquals("No switch while auto-failover disabled", 0, controller.stopCalls.size)

            // Re-enable and emit healthy to reset the debounce window.
            coordinator.setAutoFailoverEnabled(true)
            stateStore.emitTelemetry(runningTelemetry(relayHealth = "healthy"))

            // Phase 2: sustained failure with auto-failover enabled → switch must fire.
            // lastSwitchAt=0 so min-interval guard is skipped; only debounce matters.
            repeat(4) {
                clock.advance(7_000L)
                stateStore.emitTelemetry(runningTelemetry(relayHealth = "failed"))
            }
            advanceUntilIdle()

            assertEquals("Exactly one switch after re-enabling auto-failover", 1, controller.stopCalls.size)

            coordinator.stopObserving()
        }
}
