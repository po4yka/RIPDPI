package com.poyka.ripdpi.failover

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

private class FakeServiceController : ServiceController {
    val startCalls = mutableListOf<Mode>()
    val stopCalls = mutableListOf<Unit>()

    override fun start(mode: Mode): ServiceStartResult {
        startCalls += mode
        return ServiceStartResult.Accepted(mode)
    }

    override fun stop() {
        stopCalls += Unit
    }
}

private class FakeRelayProfileStore(
    profiles: List<RelayProfileRecord> = emptyList(),
) : RelayProfileStore {
    private val store = profiles.associateBy { it.id }.toMutableMap()

    override suspend fun load(profileId: String): RelayProfileRecord? = store[profileId]

    override suspend fun list(): List<RelayProfileRecord> = store.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        store[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        store.remove(profileId)
    }
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val settingsState = MutableStateFlow(AppSettingsSerializer.defaultValue)

    override val settings: Flow<AppSettings> = settingsState.asStateFlow()

    override suspend fun snapshot(): AppSettings = settingsState.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
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
}

private class FakeAwgProfileDao(
    rows: List<AwgProfileEntity> = emptyList(),
) : AwgProfileDao {
    private val rowsState = MutableStateFlow(rows.toMutableList())

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = rowsState.asStateFlow()

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
): ServiceTelemetrySnapshot =
    ServiceTelemetrySnapshot(
        status = AppStatus.Running,
        mode = Mode.VPN,
        relayTelemetry = NativeRuntimeSnapshot(source = "relay", state = "running", health = relayHealth),
        awgTelemetry = NativeRuntimeSnapshot(source = "awg", state = "running", health = awgHealth),
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
    controller: FakeServiceController = FakeServiceController(),
    relayProfiles: List<RelayProfileRecord> =
        listOf(
            RelayProfileRecord(id = "reality-1", kind = RelayKindVlessReality),
            RelayProfileRecord(id = "hysteria-1", kind = RelayKindHysteria2),
        ),
    awgProfiles: List<AwgProfileEntity> = emptyList(),
    clock: FakeFailoverClock = FakeFailoverClock(now = 0L),
    settings: FakeAppSettingsRepository = FakeAppSettingsRepository(),
): Triple<FailoverCoordinator, FakeServiceController, FakeFailoverClock> {
    val awgRepo = AwgProfileRepository(FakeAwgProfileDao(awgProfiles), FakeAwgCredentialStore())
    val coordinator =
        FailoverCoordinator(
            serviceStateStore = stateStore,
            serviceController = controller,
            relayProfileStore = FakeRelayProfileStore(relayProfiles),
            awgProfileRepository = awgRepo,
            settingsRepository = settings,
            clock = clock,
        )
    return Triple(coordinator, controller, clock)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

/**
 * Test strategy:
 *
 * The coordinator's observe coroutine is launched on a scope backed by
 * [UnconfinedTestDispatcher] (sharing the test's [TestCoroutineScheduler]).
 * This makes [StateFlow] emissions delivered synchronously to the collector —
 * no per-emit [advanceUntilIdle] needed for flow delivery. [advanceUntilIdle]
 * is only needed after the triggering emission to let [delay](300) in
 * [performSwitch] complete.
 *
 * [FakeFailoverClock] is the sole clock for debounce/min-interval math.
 * [ServiceTelemetrySnapshot.updatedAt] increments on every [runningTelemetry]
 * call so [MutableStateFlow] never deduplicates consecutive same-health values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailoverCoordinatorTest {
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
            val (coordinator, controller, _) = buildCoordinator(stateStore = stateStore, clock = clock)
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
            advanceUntilIdle() // complete delay(300) in performSwitch

            assertEquals("Expected exactly one stop", 1, controller.stopCalls.size)
            assertEquals("Expected exactly one start", 1, controller.startCalls.size)
            assertEquals(Mode.VPN, controller.startCalls.first())

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
     * Fewer than 2 candidates → coordinator stays inert, activeCandidate remains null.
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

            assertNull("activeCandidate must be null with <2 candidates", coordinator.activeCandidate.value)
            assertEquals("No switch for <2 candidates", 0, controller.stopCalls.size)

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
                    id = "awg-test",
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
                    id = "awg-resume",
                    name = "Resume AWG",
                    requestJson = MINIMAL_AWG_REQUEST_JSON,
                    updatedAt = 1L,
                )
            // Seed settings to Hysteria2 so coordinator resumes at index 1.
            settings.update {
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
            assertEquals("AWG profile id must match", "awg-resume", afterSwitch.awgProfileId)

            coordinator.stopObserving()
        }

    /**
     * A self-induced restart (stopObserving + startObserving with the same candidate set)
     * preserves activeCandidateIndex, switchesInCycle, and lastSwitchAt in memory.
     *
     * Without this: every session restart resets index to 0, so Reality→Hysteria2
     * would loop forever and never advance to AWG or back off.
     *
     * Sequence:
     *   1. startObserving → Reality active (index 0, switchesInCycle=0)
     *   2. sustained failure → SWITCH 1: Reality→Hysteria2 (switchesInCycle=1)
     *   3. self-restart: stopObserving + startObserving (same candidates)
     *      → index preserved at 1 (Hysteria2), switchesInCycle=1, lastSwitchAt preserved
     *   4. continuous failure (no healthy) → need debounce AND min-interval
     *      → SWITCH 2: Hysteria2→AWG (switchesInCycle=2)
     *   5. continuous failure → switchesInCycle=2 >= candidates.size-1=2 → BACKOFF
     *      total 2 stops, not 3
     *
     * Timing (debounce=20 000 ms, min-interval=30 000 ms):
     *   t=22 000  SWITCH 1; lastSwitchAt=22 000, switchesInCycle=1
     *   t=22 500  stopObserving + startObserving (same candidates, preserved)
     *   t=23 500  failed → failingsSince=23 500
     *   t=54 500  failed → elapsed=31 000 >= 20 000, 54 500-22 000=32 500 >= 30 000 → SWITCH 2
     *   t=86 500  failed → switchesInCycle=2 >= 2 → BACKOFF, no switch 3
     */
    @Test
    fun budgetSurvivesSelfRestart() =
        runTest {
            val stateStore = FakeServiceStateStore()
            val clock = FakeFailoverClock(now = 0L)
            val awgEntity =
                AwgProfileEntity(
                    id = "awg-budget",
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

            // Self-induced restart at t≈22500ms.
            clock.advance(500L)
            coordinator.stopObserving()
            coordinator.startObserving(observeScope)

            // activeCandidate must be Hysteria2 (index preserved, NOT reset to Reality).
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
}
