package com.poyka.ripdpi.failover

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.seed.SimpleFlavorSessionWatcher
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Health values the native runtime emits that signal an unusable egress. */
private val FAILING_HEALTH_VALUES = setOf("degraded", "failed")

/**
 * Debounce window: the active egress must be continuously failing for this
 * duration before a switch is attempted. Guards against transient blips.
 */
internal const val FAILOVER_DEBOUNCE_MS = 20_000L

/**
 * Minimum interval between two consecutive transport switches. Prevents
 * rapid oscillation (flapping) when all candidates are degraded.
 */
internal const val FAILOVER_MIN_INTERVAL_MS = 30_000L

/**
 * Opaque clock interface so tests can inject a fake without calling
 * [System.currentTimeMillis] directly.
 *
 * // cancel-safe: non-suspending, no shared mutable state
 */
interface FailoverClock {
    fun nowMillis(): Long
}

/** Production clock backed by [System.currentTimeMillis]. */
object SystemFailoverClock : FailoverClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * A transport candidate managed by [FailoverCoordinator].
 *
 * Priority is a natural number — lower value = higher priority.
 * REALITY(0) > Hysteria2(1) > AWG(2).
 */
sealed interface FailoverCandidate {
    val priority: Int

    /** Relay-backed candidate (VLESS+Reality or Hysteria2). */
    data class Relay(
        override val priority: Int,
        val profileId: String,
        val relayKind: String,
    ) : FailoverCandidate

    /** AmneziaWG egress candidate. */
    data class Awg(
        override val priority: Int,
        val awgProfileId: String,
    ) : FailoverCandidate
}

/**
 * Cross-subsystem failover coordinator for the `simple` flavor.
 *
 * Ordered candidate list: REALITY(0) > Hysteria2(1) > AWG(2).
 * Stays inert when fewer than 2 candidates are configured.
 *
 * Lifecycle:
 * - [startObserving] — begin watching telemetry (call when a VPN session starts).
 * - [stopObserving] — stop watching (call when the session ends).
 * - [setAutoFailoverEnabled] — suspend automatic switching (manual override).
 *
 * This is a [Singleton] whose coroutine scope is supplied by the caller. The
 * coordinator only acts while a session is running; between sessions it is idle.
 *
 * Cancel-safety: every `suspend fun` is annotated with a `// cancel-safe:` or
 * `// NOT cancel-safe:` comment explaining the behaviour under cancellation.
 */
@Singleton
class FailoverCoordinator
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val serviceController: ServiceController,
        private val relayProfileStore: RelayProfileStore,
        private val awgProfileRepository: AwgProfileRepository,
        private val settingsRepository: AppSettingsRepository,
        private val clock: FailoverClock,
    ) : SimpleFlavorSessionWatcher,
        ActiveTransportProvider {
        // ── Public state ────────────────────────────────────────────────────

        private val _activeCandidate = MutableStateFlow<FailoverCandidate?>(null)

        /** The currently active transport candidate, or `null` when no session runs. */
        val activeCandidate: StateFlow<FailoverCandidate?> = _activeCandidate.asStateFlow()

        private val _activeKind = MutableStateFlow<String?>(null)

        /**
         * Raw protocol kind of the currently active transport (e.g. `"vless_reality"`,
         * `"hysteria2"`, `"amneziawg"`), or `null` when no session is running.
         * Safe to display on the UI and to include verbatim in the diagnostic archive.
         */
        override val activeKind: StateFlow<String?> = _activeKind.asStateFlow()

        // ── Internal state ──────────────────────────────────────────────────

        private val autoFailoverEnabled = MutableStateFlow(true)

        private var observeJob: Job? = null

        /** Timestamp of the last switch; 0 = no switch has happened yet. */
        private var lastSwitchAt: Long = 0L

        /** Timestamp when the active egress first entered a failing health state. */
        private var failingsSince: Long? = null

        /**
         * Ordered candidate list built from what is actually seeded.
         * Fewer than 2 entries → coordinator stays inert.
         */
        private var candidates: List<FailoverCandidate> = emptyList()

        /** Index into [candidates] pointing to the currently active candidate. */
        private var activeCandidateIndex: Int = 0

        /** `true` after we have cycled through all candidates with none healthy. */
        private var backedOff: Boolean = false

        /** Switches performed since the last healthy observation; drives back-off. */
        private var switchesInCycle: Int = 0

        /**
         * `true` once candidate state has been initialised for the current candidate set.
         * Lets [startObserving] preserve the in-memory index and back-off budget across a
         * self-induced session restart (stop+start) instead of resetting them, which would
         * otherwise make failover loop forever and never back off.
         */
        private var initialized: Boolean = false

        /**
         * `true` while a [performSwitch]-issued restart is in flight. Distinguishes a
         * self-induced stop+start (which must PRESERVE the back-off budget) from a genuine
         * user-initiated session end (which must RESET it). Set in [performSwitch] before
         * the restart, consumed by [startObserving], and the only thing that lets
         * [stopObserving] tell the two cases apart — both arrive as [AppStatus.Halted].
         */
        private var selfInducedRestart: Boolean = false

        // ── Public API ──────────────────────────────────────────────────────

        /**
         * Enable or disable automatic switching.
         * When disabled the coordinator observes health but never issues a switch.
         *
         * // cancel-safe: writes a [MutableStateFlow] value, no suspension points
         */
        fun setAutoFailoverEnabled(enabled: Boolean) {
            autoFailoverEnabled.value = enabled
        }

        /**
         * Start observing telemetry for the duration of a VPN session.
         *
         * Builds the candidate list from seeded profiles, then launches a
         * coroutine that watches [ServiceStateStore.telemetry] and applies
         * debounced failover logic. Re-entrant: a second call replaces any
         * previous observe job.
         *
         * // cancel-safe: launches a child Job; cancellation terminates the job cleanly
         */
        fun startObserving(scope: CoroutineScope) {
            stopObserving()
            scope
                .launch {
                    val rebuilt = buildCandidates()
                    if (rebuilt.size < 2) {
                        candidates = rebuilt
                        initialized = false
                        Logger.i { "FailoverCoordinator: fewer than 2 candidates — staying inert" }
                        return@launch
                    }
                    // Only a self-induced restart (same candidate set, marker set by
                    // performSwitch) preserves the budget. A genuine fresh user start —
                    // even with an unchanged candidate set — re-initialises.
                    val resumed = initialized && selfInducedRestart && rebuilt == candidates
                    selfInducedRestart = false
                    candidates = rebuilt
                    if (!resumed) {
                        // Fresh session or the candidate set changed: initialise from the
                        // persisted transport. A self-induced restart (same set, already
                        // initialised) instead preserves the index and back-off budget.
                        activeCandidateIndex = resumeIndex()
                        switchesInCycle = 0
                        backedOff = false
                        lastSwitchAt = 0L
                        initialized = true
                    }
                    // The debounce window always restarts for a new session.
                    failingsSince = null
                    setActiveCandidate(candidates[activeCandidateIndex])
                    Logger.i {
                        "FailoverCoordinator: watching ${candidates.size} candidates, " +
                            "active=${candidates[activeCandidateIndex]} (resumed=$resumed)"
                    }
                    observeLoop()
                }.also { job ->
                    observeJob = job
                }
        }

        /**
         * Stop observing and clear the active candidate. Safe to call from any context.
         *
         * On a genuine session end (not a [performSwitch] self-restart) this also clears
         * the back-off budget so the next fresh start re-evaluates from a clean slate;
         * a self-induced restart preserves it via [selfInducedRestart].
         *
         * // cancel-safe: cancels the child job only
         */
        fun stopObserving() {
            observeJob?.cancel()
            observeJob = null
            setActiveCandidate(null)
            failingsSince = null
            if (!selfInducedRestart) {
                initialized = false
                backedOff = false
                switchesInCycle = 0
                lastSwitchAt = 0L
                activeCandidateIndex = 0
            }
        }

        /** Updates [_activeCandidate] and the derived [_activeKind] together. */
        private fun setActiveCandidate(candidate: FailoverCandidate?) {
            _activeCandidate.value = candidate
            _activeKind.value = candidate?.toKindString()
        }

        /**
         * [SimpleFlavorSessionWatcher] implementation.
         *
         * Launches a collector on [ServiceStateStore.status]. When the pair is
         * (Running, VPN) → [startObserving]; otherwise → [stopObserving].
         *
         * // cancel-safe: launches a child Job; cancellation terminates it cleanly
         */
        override fun bind(scope: CoroutineScope) {
            scope.launch {
                serviceStateStore.status.collect { (status, mode) ->
                    if (status == AppStatus.Running && mode == Mode.VPN) {
                        startObserving(scope)
                    } else {
                        stopObserving()
                    }
                }
            }
        }

        // ── Internal logic ──────────────────────────────────────────────────

        /**
         * Main observe loop. Collects [ServiceStateStore.telemetry] filtered to
         * [AppStatus.Running] emissions and delegates each update to [onTelemetryUpdate].
         *
         * No `drop(1)` here: unlike SelectorReloadCoordinator (which skips a persisted
         * selection seed), the telemetry StateFlow seed is idle/Halted. The `.filter`
         * below already discards non-Running snapshots, so the first real Running
         * emission — which carries the initial health reading — must be evaluated for
         * the debounce timer to start correctly.
         *
         * // NOT cancel-safe: contains suspending `collect`. Cancellation via
         * [stopObserving] or scope cancellation cleanly terminates the loop; no
         * partial switch is left in progress because state writes in [performSwitch]
         * occur before the suspending `delay` call.
         */
        private suspend fun observeLoop() {
            serviceStateStore.telemetry
                .filter { it.status == AppStatus.Running }
                .collect { snapshot ->
                    onTelemetryUpdate(snapshot)
                }
        }

        /**
         * Called on every [AppStatus.Running] telemetry emission.
         *
         * Implements debounce + anti-flap + back-off:
         *  1. Extract active-egress health (relay or AWG, depending on current candidate).
         *  2. If healthy: reset the debounce timer and clear back-off.
         *  3. If failing for ≥ [FAILOVER_DEBOUNCE_MS] and ≥ [FAILOVER_MIN_INTERVAL_MS]
         *     since the last switch: call [performSwitch].
         *
         * // NOT cancel-safe: delegates to [performSwitch] which contains suspension points.
         * Synchronous logic before the [performSwitch] tail-call is safe under cancellation.
         */
        private suspend fun onTelemetryUpdate(snapshot: ServiceTelemetrySnapshot) {
            if (!autoFailoverEnabled.value) return
            if (backedOff) return

            val activeHealth = activeEgressHealth(snapshot)
            val now = clock.nowMillis()

            if (activeHealth !in FAILING_HEALTH_VALUES) {
                // Healthy or idle — reset debounce, back-off, and the switch budget.
                failingsSince = null
                backedOff = false
                switchesInCycle = 0
                return
            }

            val since = failingsSince
            if (since == null) {
                // First emission showing failure — start the debounce window.
                failingsSince = now
                return
            }

            if (now - since < FAILOVER_DEBOUNCE_MS) {
                // Still within the debounce window — do not switch yet.
                return
            }

            // Sustained failure beyond debounce window; apply min-interval guard.
            if (lastSwitchAt > 0L && now - lastSwitchAt < FAILOVER_MIN_INTERVAL_MS) {
                // Too soon since last switch — anti-flap guard.
                return
            }

            performSwitch(now)
        }

        /**
         * Advances to the next candidate in priority order and restarts the VPN session.
         *
         * If advancing would wrap back to index 0 we have exhausted all candidates;
         * set [backedOff] and stop switching until a healthy emission resets state.
         *
         * // NOT cancel-safe: contains [delay] and suspending [settingsRepository.update].
         * The stop→delay→start restart runs inside [NonCancellable] so it completes
         * atomically: the self-induced [AppStatus.Halted] makes [bind] cancel [observeJob]
         * (which is running this very function), but the restart must still reach
         * [serviceController.start] or the session is stranded in [AppStatus.Halted] with
         * no recovery path. The cooperative cancellation is observed after the restart,
         * cleanly terminating the old observe loop while [bind]'s next (Running, VPN)
         * emission relaunches [startObserving].
         */
        private suspend fun performSwitch(now: Long) {
            if (switchesInCycle >= candidates.size - 1) {
                // Every candidate has been tried this failing cycle with none recovering.
                Logger.w { "FailoverCoordinator: all candidates exhausted, backing off" }
                backedOff = true
                return
            }
            val nextIndex = (activeCandidateIndex + 1) % candidates.size

            val nextCandidate = candidates[nextIndex]
            Logger.i {
                "FailoverCoordinator: switching ${candidates[activeCandidateIndex]} → $nextCandidate " +
                    "(failDuration=${now - (failingsSince ?: now)}ms)"
            }

            // Write next candidate's config before touching the session so the service
            // reads the correct config on restart.
            writeConfig(nextCandidate)

            // Update bookkeeping before the suspension points below so a cancellation
            // cannot leave the index and timestamp inconsistent.
            activeCandidateIndex = nextIndex
            lastSwitchAt = now
            failingsSince = null
            switchesInCycle++
            setActiveCandidate(nextCandidate)

            // Mark the restart as self-induced BEFORE stopping so the bind collector, which
            // sees the resulting Halted and calls stopObserving, preserves the budget instead
            // of treating it as a user-initiated session end.
            selfInducedRestart = true

            // Session restart: stop then start. VPN consent is already granted;
            // start(Mode.VPN) reuses it without prompting. Wrapped in NonCancellable so the
            // self-induced Halted (which makes bind cancel this job) cannot abort the restart
            // mid-way and strand the session in Halted.
            val result =
                withContext(NonCancellable) {
                    serviceController.stop()
                    // Brief yield so the OS processes the stop intent before the start intent.
                    delay(300L)
                    serviceController.start(Mode.VPN)
                }
            if (result is ServiceStartResult.Rejected) {
                // No (Running, VPN) emission will follow, so bind never relaunches
                // startObserving to consume the marker — clear it here so a later genuine
                // user start is treated as fresh and resets the back-off budget.
                selfInducedRestart = false
                Logger.w { "FailoverCoordinator: restart rejected — ${result.reason}" }
            }
        }

        /**
         * Writes the configuration for [candidate] to [AppSettings] so that the session
         * that starts after [performSwitch] picks up the correct transport.
         *
         * For relay candidates: updates the relay kind and profile-id settings fields that
         * [UpstreamRelayRuntimeConfigResolver] reads on session start. The credentials are
         * already in the keystore from the initial seed; only the settings pointer changes.
         *
         * For AWG candidates: disables relay in settings so [awgConfigOrNull] wins in
         * [SharedProxyRuntimeStack]. The AWG profile is already in [AwgProfileRepository].
         *
         * // NOT cancel-safe: contains suspending [settingsRepository.update]. If cancelled
         * after the settings write but before the session restart, the settings remain at the
         * new candidate's values — a subsequent session start picks up the correct transport.
         */
        private suspend fun writeConfig(candidate: FailoverCandidate) {
            when (candidate) {
                is FailoverCandidate.Relay -> {
                    settingsRepository.update {
                        setRelayEnabled(true)
                        setRelayKind(candidate.relayKind)
                        setRelayProfileId(candidate.profileId)
                    }
                }

                is FailoverCandidate.Awg -> {
                    // Disable the relay so the connection policy resolver does not start it.
                    // AWG is picked up via AwgProfileRepository; the active AWG profile id
                    // in RipDpiProxyUIPreferences.awg is set by the config resolver on start.
                    settingsRepository.update {
                        setRelayEnabled(false)
                    }
                    Logger.i { "FailoverCoordinator: switching to AWG profile ${candidate.awgProfileId}" }
                }
            }
        }

        /**
         * Returns the health string of the currently active egress from [snapshot].
         *
         * Relay candidate → [ServiceTelemetrySnapshot.relayTelemetry].health
         * AWG candidate   → [ServiceTelemetrySnapshot.awgTelemetry].health
         * Unknown         → "idle" (non-failing; coordinator stays quiet)
         *
         * // cancel-safe: no suspension points
         */
        private fun activeEgressHealth(snapshot: ServiceTelemetrySnapshot): String =
            when (candidates.getOrNull(activeCandidateIndex)) {
                is FailoverCandidate.Relay -> snapshot.relayTelemetry.health
                is FailoverCandidate.Awg -> snapshot.awgTelemetry.health
                null -> NativeRuntimeSnapshot.idle(source = "unknown").health
            }

        /**
         * Returns the index in [candidates] that matches the currently persisted transport.
         *
         * Reads [AppSettingsRepository.snapshot] once. If relay is disabled the active
         * egress is AWG → find the first [FailoverCandidate.Awg]. If relay is enabled,
         * match [settings.relayKind] to the relay candidate's [FailoverCandidate.Relay.relayKind].
         * Falls back to 0 when nothing matches (first-launch, settings cleared, etc.).
         *
         * // NOT cancel-safe: contains suspending [settingsRepository.snapshot].
         * Cancellation leaves [activeCandidateIndex] at 0, which is a safe default.
         */
        private suspend fun resumeIndex(): Int {
            // Resume on the persisted relay transport. AWG-as-egress is tracked in memory
            // across self-restarts (see startObserving); inferring it from `!relayEnabled`
            // is wrong because relay-disabled is also the default-install state. On a cold
            // start with relay disabled, fall back to the highest-priority candidate and
            // let health re-evaluation drive any further failover.
            val settings = settingsRepository.snapshot()
            if (settings.relayEnabled) {
                val idx =
                    candidates.indexOfFirst {
                        it is FailoverCandidate.Relay && it.relayKind == settings.relayKind
                    }
                if (idx >= 0) return idx
            }
            return 0
        }

        /**
         * Builds the ordered candidate list from what is actually persisted in the stores.
         *
         * Priority order:
         *  0 — VLESS+Reality relay profile ([RelayKindVlessReality])
         *  1 — Hysteria2 relay profile ([RelayKindHysteria2])
         *  2 — First AWG profile in [AwgProfileRepository]
         *
         * A candidate is only added when its backing data exists. The list is sorted by
         * [FailoverCandidate.priority] so priority 0 is always index 0.
         *
         * // NOT cancel-safe: contains suspending store reads. Cancellation leaves
         * [candidates] empty → coordinator stays inert, which is safe.
         */
        private suspend fun buildCandidates(): List<FailoverCandidate> {
            val result = mutableListOf<FailoverCandidate>()

            val relayProfiles = relayProfileStore.list()

            relayProfiles.firstOrNull { it.kind == RelayKindVlessReality }?.let { profile ->
                result.add(FailoverCandidate.Relay(priority = 0, profileId = profile.id, relayKind = profile.kind))
            }

            relayProfiles.firstOrNull { it.kind == RelayKindHysteria2 }?.let { profile ->
                result.add(FailoverCandidate.Relay(priority = 1, profileId = profile.id, relayKind = profile.kind))
            }

            // One-shot read: take the first emission from the profiles flow.
            val awgProfiles = awgProfileRepository.observeProfiles().first()
            awgProfiles.firstOrNull()?.let { savedProfile ->
                result.add(FailoverCandidate.Awg(priority = 2, awgProfileId = savedProfile.id))
            }

            return result.sortedBy { it.priority }
        }
    }

/**
 * Maps a [FailoverCandidate] to its raw protocol kind string.
 *
 * Relay candidates use their [FailoverCandidate.Relay.relayKind] directly — these are
 * the same wire values emitted by the native runtime (e.g. `"vless_reality"`,
 * `"hysteria2"`). AWG uses the constant `"amneziawg"`. This string is safe to display
 * on the UI and to include verbatim in the diagnostic archive (it is a protocol kind,
 * not a server address or user identifier).
 */
internal fun FailoverCandidate.toKindString(): String =
    when (this) {
        is FailoverCandidate.Relay -> relayKind
        is FailoverCandidate.Awg -> "amneziawg"
    }

@Module
@InstallIn(SingletonComponent::class)
object FailoverCoordinatorModule {
    @Provides
    @Singleton
    fun provideFailoverClock(): FailoverClock = SystemFailoverClock
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FailoverCoordinatorBindsModule {
    @Binds
    abstract fun bindSimpleFlavorSessionWatcher(coordinator: FailoverCoordinator): SimpleFlavorSessionWatcher

    @Binds
    abstract fun bindActiveTransportProvider(coordinator: FailoverCoordinator): ActiveTransportProvider
}
