package com.poyka.ripdpi.failover

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.seed.SEED_RELAY_PROFILE_ID_PREFIX
import com.poyka.ripdpi.seed.SIMPLE_SEED_AWG_PROFILE_ID
import com.poyka.ripdpi.seed.SimpleFlavorSessionWatcher
import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.ExplicitUserStartPreparer
import com.poyka.ripdpi.services.InitialRelayCandidate
import com.poyka.ripdpi.services.InitialRelayTransportClass
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.StartupFallbackController
import com.poyka.ripdpi.services.StartupFallbackDispatchResult
import com.poyka.ripdpi.services.StartupFallbackLease
import com.poyka.ripdpi.services.TransportFailoverApplyOutcome
import com.poyka.ripdpi.services.TransportFailoverApplyTracker
import com.poyka.ripdpi.services.TransportFailoverTarget
import com.poyka.ripdpi.services.TransportKindAmneziaWg
import com.poyka.ripdpi.services.relayProfileSupportsUdpAssociation
import com.poyka.ripdpi.services.relayTransportCapabilities
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Health values the native runtime emits that signal an unusable egress. */
private val FAILING_HEALTH_VALUES = setOf("degraded", "failed")

private fun FailureReason.isRecoverableTransportFailure(): Boolean =
    when (this) {
        is FailureReason.NativeError,
        FailureReason.TunnelEstablishmentFailed,
        is FailureReason.WarpEndpointUnavailable,
        is FailureReason.WarpRuntimeFailed,
        is FailureReason.InitialTransportSelectionFailed,
        -> true

        is FailureReason.PermissionLost,
        is FailureReason.RelayConfigRejected,
        is FailureReason.RelayFingerprintPolicyRejected,
        is FailureReason.Unexpected,
        is FailureReason.WarpProvisioningFailed,
        -> false
    }

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

internal interface InitialRaceFailoverCoordinator {
    fun shouldSkipInitialRelayRace(): Boolean

    fun recordInitialRelaySelection(
        profileId: String,
        relayKind: String,
    )
}

/** Production clock backed by [System.currentTimeMillis]. */
object SystemFailoverClock : FailoverClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * A transport candidate managed by [FailoverCoordinator].
 *
 * Priority is a natural number — lower value = higher priority. In TCP-only sessions, seeded
 * relay profiles retain bundle order inside a protocol family: REALITY endpoints, VLESS/XHTTP,
 * Hysteria2, then AWG. UDP sessions retain XUDP-enabled REALITY endpoints, Hysteria2, then AWG.
 */
sealed interface FailoverCandidate {
    val priority: Int

    /** Relay-backed candidate (VLESS+Reality, VLESS/XHTTP, or Hysteria2). */
    data class Relay(
        override val priority: Int,
        val profileId: String,
        val relayKind: String,
        val vlessTransport: String? = null,
        val supportsUdpAssociation: Boolean = false,
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
 * The ordered candidate list is capability-aware. A single compatible candidate remains valid
 * for startup recovery, while telemetry-driven switching requires a second candidate.
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
    internal constructor(
        private val serviceStateStore: ServiceStateStore,
        private val serviceController: ServiceController,
        private val startupFallbackController: StartupFallbackController,
        private val relayCatalog: SimpleFailoverRelayCatalog,
        private val settingsRepository: AppSettingsRepository,
        private val awgEgressSelection: SimpleAwgEgressSelection,
        private val egressProbe: FailoverEgressProbe,
        private val egressHealthCache: SimpleEgressHealthMemory,
        private val transportFailoverApplyTracker: TransportFailoverApplyTracker,
        private val clock: FailoverClock,
    ) : SimpleFlavorSessionWatcher,
        ActiveTransportProvider,
        ExplicitUserStartPreparer,
        InitialRaceFailoverCoordinator {
        // ── Public state ────────────────────────────────────────────────────

        private val _activeCandidate = MutableStateFlow<FailoverCandidate?>(null)

        /** The currently active transport candidate, or `null` when no session runs. */
        val activeCandidate: StateFlow<FailoverCandidate?> = _activeCandidate.asStateFlow()

        private val _activeTransport = MutableStateFlow<ActiveTransportDescriptor?>(null)

        /** Privacy-safe protocol details for the active transport, or `null` when idle. */
        override val activeTransport: StateFlow<ActiveTransportDescriptor?> = _activeTransport.asStateFlow()

        // ── Internal state ──────────────────────────────────────────────────

        private val autoFailoverEnabled = MutableStateFlow(true)

        private var observeJob: Job? = null

        /** Timestamp of the last switch; 0 = no switch has happened yet. */
        private var lastSwitchAt: Long = 0L

        /** Timestamp when the active egress first entered a failing health state. */
        private var failingsSince: Long? = null

        /** Last proxy error counter observed for the current runtime session. */
        private var observedProxyTotalErrors: Long? = null

        /**
         * A fresh proxy failure occurred while a relay candidate was active.
         *
         * Relay listener health alone can stay `running` after an upstream silent drop because
         * the local SOCKS client closes normally. This latch makes the proxy failure a passive
         * trigger; the explicit SOCKS egress probe remains the authority for switching.
         */
        private var suspectedRelayFailure: Boolean = false

        /** Capability contract used to build and actively verify the current candidate set. */
        private var activeRequirements: EgressRequirements = EgressRequirements()

        /**
         * Ordered candidate list built from what is actually seeded.
         * Fewer than 2 entries → coordinator stays inert.
         */
        private var candidates: List<FailoverCandidate> = emptyList()

        /** Index into [candidates] pointing to the currently active candidate. */
        private var activeCandidateIndex: Int = 0

        /** `true` after we have cycled through all candidates with none healthy. */
        private var backedOff: Boolean = false

        /** Blocks new switches while a timed-out runtime still owns the persisted candidate. */
        private var transportReconciliationPending: Boolean = false

        /** Switches performed since the last healthy observation; drives back-off. */
        private var switchesInCycle: Int = 0

        /** One-shot guard so an in-session failover starts the selected candidate directly. */
        private var skipNextInitialRelayRace: Boolean = false

        /** Failed startup replacements attempted since a candidate last reached Running. */
        private var startupFailureSwitchesInCycle: Int = 0

        /** Candidate set associated with [startupFailureSwitchesInCycle]. */
        private var startupFailureCandidates: List<FailoverCandidate> = emptyList()

        private var startupFailureStartedOutsideCandidates: Boolean = false

        private var initialRaceSelection: FailoverCandidate.Relay? = null

        private val startupRecoveryMutex = Mutex()
        private var startupRecoveryEpoch = 0L

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
         * Every explicit VPN attempt starts from the embedded VLESS+Reality primary. Automatic
         * replacements remain scoped to that attempt and cannot become the next manual default.
         *
         * // NOT cancel-safe: persists the primary selection before the service reads settings.
         * Cancellation leaves the fail-closed VLESS primary selected for the next attempt.
         */
        override suspend fun prepare(mode: Mode) {
            if (mode != Mode.VPN) return
            startupRecoveryMutex.withLock {
                startupRecoveryEpoch++
                stopObserving()
                settingsRepository.update {
                    setRelayEnabled(true)
                    setRelayKind(RelayKindVlessReality)
                    setRelayProfileId(SEEDED_VLESS_REALITY_PROFILE_ID)
                    setSimpleFailoverAwgProfileId("")
                }
                awgEgressSelection.clear()
                startupFailureSwitchesInCycle = 0
                startupFailureCandidates = emptyList()
                startupFailureStartedOutsideCandidates = false
                initialRaceSelection = null
                skipNextInitialRelayRace = false
                Logger.i { "FailoverCoordinator: explicit VPN attempt restored embedded VLESS+Reality" }
            }
        }

        override fun shouldSkipInitialRelayRace(): Boolean =
            skipNextInitialRelayRace.also { shouldSkip ->
                if (shouldSkip) skipNextInitialRelayRace = false
            }

        override fun recordInitialRelaySelection(
            profileId: String,
            relayKind: String,
        ) {
            initialRaceSelection =
                FailoverCandidate.Relay(
                    priority = if (relayKind == RelayKindVlessReality) 0 else 1,
                    profileId = profileId,
                    relayKind = relayKind,
                )
            setActiveCandidate(initialRaceSelection)
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
            val previousJob = observeJob
            previousJob?.cancel()
            scope
                .launch {
                    withContext(NonCancellable) { previousJob?.join() }
                    currentCoroutineContext().ensureActive()
                    clearObservationState(clearTransportReconciliation = false)
                    val rebuilt = buildCandidates()
                    val diagnosticAwg = resumeOnlyDiagnosticAwg()
                    if (diagnosticAwg != null) {
                        candidates = emptyList()
                        setActiveCandidate(diagnosticAwg)
                        Logger.i { "FailoverCoordinator: diagnostic AWG resumed without automatic failover" }
                        return@launch
                    }
                    if (rebuilt.isEmpty()) {
                        candidates = emptyList()
                        Logger.w { "FailoverCoordinator: no transport satisfies required egress capabilities" }
                        return@launch
                    }
                    candidates = rebuilt
                    val racedIndex =
                        initialRaceSelection?.let { selection ->
                            rebuilt
                                .indexOfFirst { candidate ->
                                    candidate is FailoverCandidate.Relay &&
                                        candidate.profileId == selection.profileId &&
                                        candidate.relayKind == selection.relayKind
                                }.takeIf { it >= 0 }
                        }
                    activeCandidateIndex = racedIndex ?: resumeIndexOrNull() ?: 0
                    switchesInCycle = 0
                    backedOff = false
                    lastSwitchAt = 0L
                    initialRaceSelection = null
                    // The debounce window always restarts for a new session.
                    failingsSince = null
                    observedProxyTotalErrors = null
                    suspectedRelayFailure = false
                    setActiveCandidate(candidates[activeCandidateIndex])
                    Logger.i {
                        "FailoverCoordinator: watching ${candidates.size} candidates, " +
                            "active=${candidates[activeCandidateIndex].transportKind()}"
                    }
                    observeLoop()
                }.also { job ->
                    observeJob = job
                }
        }

        /**
         * Stop observing and clear the active candidate. Safe to call from any context.
         *
         * A session end also clears the back-off budget so the next fresh start
         * re-evaluates from a clean slate.
         *
         * // cancel-safe: cancels the child job only
         */
        fun stopObserving() {
            val stoppedJob = observeJob
            stoppedJob?.cancel()
            stoppedJob?.invokeOnCompletion {
                if (observeJob === stoppedJob) observeJob = null
            }
            clearObservationState(clearTransportReconciliation = true)
        }

        private fun clearObservationState(clearTransportReconciliation: Boolean) {
            setActiveCandidate(null)
            failingsSince = null
            observedProxyTotalErrors = null
            suspectedRelayFailure = false
            activeRequirements = EgressRequirements()
            awgEgressSelection.clear()
            backedOff = false
            if (clearTransportReconciliation) transportReconciliationPending = false
            switchesInCycle = 0
            lastSwitchAt = 0L
            activeCandidateIndex = 0
        }

        /** Updates [_activeCandidate] and the derived [_activeTransport] together. */
        private fun setActiveCandidate(candidate: FailoverCandidate?) {
            _activeCandidate.value = candidate
            _activeTransport.value = candidate?.toActiveTransportDescriptor()
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
                        startupFailureSwitchesInCycle = 0
                        startupFailureCandidates = emptyList()
                        startupFailureStartedOutsideCandidates = false
                        startObserving(scope)
                    } else {
                        stopObserving()
                    }
                }
            }
            scope.launch {
                serviceStateStore.events
                    .filterIsInstance<ServiceEvent.Failed>()
                    .collect { event ->
                        if (
                            event.sender == Sender.VPN &&
                            event.modeAtFailure == Mode.VPN &&
                            event.statusAtFailure != AppStatus.Running &&
                            event.reason.isRecoverableTransportFailure()
                        ) {
                            recoverFromStartupFailure(
                                initialRaceFailed = event.reason is FailureReason.InitialTransportSelectionFailed,
                            )
                        }
                    }
            }
        }

        /**
         * Advances a persisted candidate whose VPN runtime failed before reaching Running.
         *
         * // NOT cancel-safe: waits for the failed service to halt, persists the replacement,
         * then requests a new service start. A cancellation after persistence is fail-closed:
         * the next Android/user recovery start reads the already-selected replacement.
         */
        private suspend fun recoverFromStartupFailure(initialRaceFailed: Boolean) {
            if (!autoFailoverEnabled.value) return
            if (resumeOnlyDiagnosticAwg() != null) {
                Logger.i { "FailoverCoordinator: diagnostic AWG startup failure remains manual" }
                return
            }
            val pendingEpoch = startupRecoveryMutex.withLock { startupRecoveryEpoch }
            val lease = startupFallbackController.captureStartupFallbackLease()
            val halted =
                withTimeoutOrNull(STARTUP_HALT_WAIT_TIMEOUT_MILLIS) {
                    serviceStateStore.status.first { (status, mode) ->
                        status == AppStatus.Halted && mode == Mode.VPN
                    }
                    true
                } == true
            if (!halted) {
                Logger.w { "FailoverCoordinator: timed out waiting for failed VPN to halt" }
                return
            }
            startupRecoveryMutex.withLock {
                if (startupRecoveryEpoch != pendingEpoch) {
                    Logger.i { "FailoverCoordinator: stale startup recovery was superseded" }
                    return@withLock
                }
                recoverHaltedStartup(initialRaceFailed, lease)
            }
        }

        /**
         * Persists and dispatches the next candidate after the failed service is fully halted.
         *
         * // NOT cancel-safe: candidate persistence precedes dispatch. Cancellation leaves that
         * candidate selected so a later recovery start remains fail-closed on the VPN path.
         */
        private suspend fun recoverHaltedStartup(
            initialRaceFailed: Boolean,
            lease: StartupFallbackLease,
        ) {
            val rebuilt = buildCandidates()
            if (rebuilt.isEmpty()) {
                Logger.w { "FailoverCoordinator: no compatible startup fallback, remaining fail-closed" }
                return
            }
            val settingsBeforeSwitch = settingsRepository.snapshot()
            val persistedIndex = findPersistedCandidateIndex(rebuilt)
            if (rebuilt != startupFailureCandidates) {
                startupFailureCandidates = rebuilt
                startupFailureSwitchesInCycle = 0
                startupFailureStartedOutsideCandidates = persistedIndex == null
            }
            candidates = rebuilt
            activeCandidateIndex = persistedIndex ?: 0
            val availableReplacements = candidates.size - if (startupFailureStartedOutsideCandidates) 0 else 1
            if (startupFailureSwitchesInCycle >= availableReplacements) {
                Logger.w { "FailoverCoordinator: startup candidates exhausted, remaining fail-closed" }
                return
            }

            val previousCandidate = candidates.getOrNull(activeCandidateIndex)
            val nextIndex = if (persistedIndex == null) 0 else (activeCandidateIndex + 1) % candidates.size
            val nextCandidate = candidates[nextIndex]
            Logger.i {
                "FailoverCoordinator: startup transport failed; selecting candidate " +
                    "${nextIndex + 1}/${candidates.size} kind=${nextCandidate.transportKind()}" +
                    if (initialRaceFailed) " after readiness failure" else ""
            }
            writeConfig(nextCandidate)
            activeCandidateIndex = nextIndex
            startupFailureSwitchesInCycle++
            setActiveCandidate(nextCandidate)
            skipNextInitialRelayRace = true
            when (val dispatch = startupFallbackController.startVpnForStartupFallback(lease)) {
                StartupFallbackDispatchResult.Superseded -> {
                    rollbackStartupSwitch(settingsBeforeSwitch, previousCandidate)
                    Logger.i { "FailoverCoordinator: newer user intent superseded startup recovery" }
                }

                is StartupFallbackDispatchResult.Dispatched -> {
                    val result = dispatch.startResult
                    if (result is ServiceStartResult.Rejected) {
                        rollbackStartupSwitch(settingsBeforeSwitch, previousCandidate)
                        Logger.w { "FailoverCoordinator: startup recovery rejected — ${result.reason}" }
                    }
                }
            }
        }

        /**
         * Restores the candidate that owned the failed attempt.
         *
         * // NOT cancel-safe: settings are restored before the in-memory selector. A process
         * restart rehydrates the same persisted candidate if cancellation lands in between.
         */
        private suspend fun rollbackStartupSwitch(
            settings: AppSettings,
            previousCandidate: FailoverCandidate?,
        ) {
            settingsRepository.update {
                setRelayEnabled(settings.relayEnabled)
                setRelayKind(settings.relayKind)
                setRelayProfileId(settings.relayProfileId)
                setSimpleFailoverAwgProfileId(settings.simpleFailoverAwgProfileId)
            }
            if (settings.relayEnabled || settings.simpleFailoverAwgProfileId.isBlank()) {
                awgEgressSelection.clear()
            } else {
                awgEgressSelection.select(settings.simpleFailoverAwgProfileId)
            }
            startupFailureSwitchesInCycle = (startupFailureSwitchesInCycle - 1).coerceAtLeast(0)
            previousCandidate?.let { previous ->
                activeCandidateIndex = candidates.indexOf(previous).takeIf { it >= 0 } ?: 0
            }
            setActiveCandidate(previousCandidate)
            skipNextInitialRelayRace = false
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
         * occur before waiting for the old service to halt.
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

            val activeHealth = activeEgressHealth(snapshot)
            val now = clock.nowMillis()

            if (observeFreshProxyFailure(snapshot)) {
                suspectedRelayFailure = true
            }
            val sustainedXudpFailure = hasSustainedXudpFailures(snapshot)
            if (sustainedXudpFailure) {
                suspectedRelayFailure = true
            }

            if (activeHealth !in FAILING_HEALTH_VALUES && !suspectedRelayFailure) {
                // Healthy or idle — reset debounce, back-off, and the switch budget. This runs
                // even while backedOff so a transport that heals mid-session clears back-off and
                // resumes failover without needing a full session restart.
                failingsSince = null
                backedOff = false
                switchesInCycle = 0
                return
            }

            // Native session errors are only a passive trigger: a target-specific reset or
            // failed DNS-provider attempt does not prove that the relay path is unusable.
            // Confirm the current Android VPN egress before starting or advancing the
            // failover debounce. A successful request also creates a successful relay
            // session, which clears the native consecutive-error signal.
            if (confirmRelayEgress(snapshot)) {
                failingsSince = null
                suspectedRelayFailure = false
                backedOff = false
                switchesInCycle = 0
                return
            }

            if (sustainedXudpFailure && !snapshot.isNetworkHandover()) {
                val activeRelay = candidates.getOrNull(activeCandidateIndex) as? FailoverCandidate.Relay
                if (activeRelay?.relayKind == RelayKindVlessReality) {
                    egressHealthCache.recordConfirmedFailure(
                        networkScopeKey = snapshot.runtimeFieldTelemetry.telemetryNetworkFingerprintHash,
                        proof = EgressProof.TcpUdp,
                        relayKind = activeRelay.relayKind,
                        profileId = activeRelay.profileId,
                    )
                }
            }

            // Still failing. If every candidate was already tried this cycle with none healthy,
            // stay quiet until a healthy emission (above) resets the budget.
            if (backedOff) return

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
         * Returns true only for a new proxy failure observed while a relay is active.
         *
         * Proxy health is intentionally not used because it is sticky after historical errors.
         * Counter resets mark a new runtime baseline and never create a failover signal.
         */
        private fun observeFreshProxyFailure(snapshot: ServiceTelemetrySnapshot): Boolean {
            if (candidates.getOrNull(activeCandidateIndex) !is FailoverCandidate.Relay) {
                observedProxyTotalErrors = null
                return false
            }

            val current = snapshot.proxyTelemetry.totalErrors
            val previous = observedProxyTotalErrors
            observedProxyTotalErrors = current
            if (previous == null || current <= previous) return false

            return !snapshot.proxyTelemetry.lastFailureClass.isNullOrBlank()
        }

        private fun hasSustainedXudpFailures(snapshot: ServiceTelemetrySnapshot): Boolean =
            activeRequirements.udpAssociate &&
                candidates.getOrNull(activeCandidateIndex) is FailoverCandidate.Relay &&
                snapshot.relayTelemetry.protocolKind == RelayKindVlessReality &&
                (snapshot.relayTelemetry.xudpTelemetry?.consecutiveUdpFailures ?: 0L) >=
                XUDP_FAILURE_STREAK_THRESHOLD

        private suspend fun confirmRelayEgress(snapshot: ServiceTelemetrySnapshot): Boolean {
            val active = candidates.getOrNull(activeCandidateIndex) as? FailoverCandidate.Relay ?: return false
            val endpoint = parseFailoverProxyEndpoint(snapshot.relayTelemetry.listenerAddress) ?: return false
            val probeRequirements =
                activeRequirements.copy(
                    udpAssociate = activeRequirements.udpAssociate && active.supportsUdpAssociation,
                )
            val result =
                try {
                    egressProbe.probe(endpoint, probeRequirements)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FailoverEgressProbeResult(succeeded = false, failure = "probe_error")
                }
            if (!result.succeeded) {
                Logger.w {
                    "FailoverCoordinator: egress probe failed kind=" +
                        candidates.getOrNull(activeCandidateIndex)?.transportKind().orEmpty() +
                        " capability=${result.failure.orEmpty()}"
                }
            }
            return result.succeeded
        }

        /**
         * Advances to the next candidate in priority order and restarts the VPN session.
         *
         * If advancing would wrap back to index 0 we have exhausted all candidates;
         * set [backedOff] and stop switching until a healthy emission resets state.
         *
         * // conditionally cancel-safe: suspends while persisting and applying the next candidate.
         * Until the runtime acknowledges application, rejected and rollback-safe failed attempts
         * restore the previous persisted candidate in [NonCancellable]. A runtime-owned timeout
         * preserves the in-flight candidate and blocks further switching until reconciliation.
         */
        private suspend fun performSwitch(now: Long) {
            if (transportReconciliationPending) {
                backedOff = true
                Logger.w { "FailoverCoordinator: waiting for in-flight transport reconciliation" }
                return
            }
            if (switchesInCycle >= candidates.size - 1) {
                // Every candidate has been tried this failing cycle with none recovering.
                Logger.w { "FailoverCoordinator: all candidates exhausted, backing off" }
                backedOff = true
                return
            }
            val nextIndex = (activeCandidateIndex + 1) % candidates.size

            val previousCandidate = candidates[activeCandidateIndex]
            val nextCandidate = candidates[nextIndex]
            val switchEpoch = startupRecoveryMutex.withLock { startupRecoveryEpoch }
            Logger.i {
                "FailoverCoordinator: switching ${previousCandidate.transportKind()} → " +
                    "${nextCandidate.transportKind()} " +
                    "(failDuration=${now - (failingsSince ?: now)}ms)"
            }

            var applyRequestId: Long? = null
            var applied = false
            var rollbackSafe = true
            var configWritten = false
            try {
                val requestId = transportFailoverApplyTracker.tryBegin()
                if (requestId == null) {
                    transportReconciliationPending = true
                    backedOff = true
                    rollbackSafe = false
                    Logger.w { "FailoverCoordinator: runtime still owns a prior transport application" }
                    return
                }
                applyRequestId = requestId
                // Serialize the forward mutation with explicit startup recovery. Otherwise a
                // stale observer can overwrite the freshly restored VLESS primary after
                // prepare() has advanced the recovery epoch.
                configWritten =
                    startupRecoveryMutex.withLock {
                        if (startupRecoveryEpoch == switchEpoch) {
                            configWritten = true
                            writeConfig(nextCandidate)
                            true
                        } else {
                            false
                        }
                    }
                if (!configWritten) {
                    Logger.i { "FailoverCoordinator: stale switch was superseded before persistence" }
                    return
                }

                skipNextInitialRelayRace = true
                val result =
                    serviceController.restartVpnForTransportFailover(
                        requestId = requestId,
                        expectedTarget = nextCandidate.transportFailoverTarget(),
                    )
                if (result is ServiceStartResult.Rejected) {
                    transportFailoverApplyTracker.recordRollbackSafeFailure(requestId)
                    Logger.w { "FailoverCoordinator: restart rejected — ${result.reason}" }
                    return
                }

                val outcome =
                    transportFailoverApplyTracker.awaitOutcome(
                        requestId = requestId,
                        timeoutMillis = TRANSPORT_APPLY_TIMEOUT_MILLIS,
                    )
                applied = outcome == TransportFailoverApplyOutcome.Applied
                rollbackSafe = outcome == TransportFailoverApplyOutcome.RollbackSafeFailure
                transportReconciliationPending = outcome == TransportFailoverApplyOutcome.TimedOutInFlight
                if (transportReconciliationPending) backedOff = true
                if (!applied) {
                    Logger.w {
                        "FailoverCoordinator: transport application was not confirmed for request=" +
                            "$requestId outcome=$outcome"
                    }
                    return
                }

                activeCandidateIndex = nextIndex
                lastSwitchAt = clock.nowMillis()
                resetPendingSwitchSignals()
                switchesInCycle++
                setActiveCandidate(nextCandidate)
            } catch (cancelled: CancellationException) {
                applyRequestId?.let { requestId ->
                    val outcome =
                        withContext(NonCancellable) {
                            transportFailoverApplyTracker.settleCancellation(
                                requestId = requestId,
                                timeoutMillis = TRANSPORT_APPLY_TIMEOUT_MILLIS,
                            )
                        }
                    applied = outcome == TransportFailoverApplyOutcome.Applied
                    rollbackSafe = outcome == TransportFailoverApplyOutcome.RollbackSafeFailure
                    transportReconciliationPending = outcome == TransportFailoverApplyOutcome.TimedOutInFlight
                    if (transportReconciliationPending) backedOff = true
                }
                throw cancelled
            } finally {
                if (!applied && rollbackSafe) {
                    applyRequestId?.let(transportFailoverApplyTracker::cancel)
                    skipNextInitialRelayRace = false
                    if (configWritten) {
                        withContext(NonCancellable) {
                            startupRecoveryMutex.withLock {
                                if (startupRecoveryEpoch == switchEpoch) {
                                    writeConfig(previousCandidate)
                                } else {
                                    Logger.i { "FailoverCoordinator: stale switch rollback was superseded" }
                                }
                            }
                        }
                    }
                    resetPendingSwitchSignals()
                } else if (!applied) {
                    Logger.w {
                        "FailoverCoordinator: preserving in-flight transport config until runtime reconciliation"
                    }
                    resetPendingSwitchSignals()
                }
            }
        }

        private fun resetPendingSwitchSignals() {
            failingsSince = null
            observedProxyTotalErrors = null
            suspectedRelayFailure = false
        }

        private fun FailoverCandidate.transportFailoverTarget(): TransportFailoverTarget =
            TransportFailoverTarget(
                transportKind = transportKind(),
                profileId =
                    when (this) {
                        is FailoverCandidate.Relay -> profileId
                        is FailoverCandidate.Awg -> awgProfileId
                    },
            )

        /**
         * Writes the configuration for [candidate] to [AppSettings] so that the session
         * that starts after [performSwitch] picks up the correct transport.
         *
         * For relay candidates: updates the relay kind and profile-id settings fields that
         * [UpstreamRelayRuntimeConfigResolver] reads on session start. The credentials are
         * already in the keystore from the initial seed; only the settings pointer changes.
         *
         * For AWG candidates: disables relay in settings and records the selected AWG
         * profile in [SimpleAwgEgressSelection] so the next service start can attach the
         * rehydrated [com.poyka.ripdpi.data.awg.AwgActivationRequest] to
         * [com.poyka.ripdpi.core.RipDpiProxyUIPreferences.awg].
         *
         * // NOT cancel-safe: contains suspending [settingsRepository.update]. Callers that need
         * transactional switching must compensate with a [NonCancellable] write of the previous
         * candidate until runtime application has been acknowledged.
         */
        private suspend fun writeConfig(candidate: FailoverCandidate) {
            when (candidate) {
                is FailoverCandidate.Relay -> {
                    awgEgressSelection.clear()
                    settingsRepository.update {
                        setRelayEnabled(true)
                        setRelayKind(candidate.relayKind)
                        setRelayProfileId(candidate.profileId)
                        setSimpleFailoverAwgProfileId("")
                    }
                }

                is FailoverCandidate.Awg -> {
                    // Disable the relay and persist the AWG selector so the connection policy
                    // resolver can rehydrate the egress request after a service/process restart.
                    awgEgressSelection.select(candidate.awgProfileId)
                    settingsRepository.update {
                        setRelayEnabled(false)
                        setSimpleFailoverAwgProfileId(candidate.awgProfileId)
                    }
                    Logger.i { "FailoverCoordinator: switching to amneziawg" }
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
        private fun activeEgressHealth(snapshot: ServiceTelemetrySnapshot): String {
            val health =
                when (candidates.getOrNull(activeCandidateIndex)) {
                    is FailoverCandidate.Relay -> snapshot.relayTelemetry.health
                    is FailoverCandidate.Awg -> snapshot.awgTelemetry.health
                    null -> NativeRuntimeSnapshot.idle(source = "unknown").health
                }
            return health.substringBefore(' ')
        }

        /**
         * Returns the index in [candidates] that matches the currently persisted transport.
         *
         * Reads [AppSettingsRepository.snapshot] once. If relay is enabled, match
         * [settings.relayProfileId] to the exact relay candidate. Kind-only matching remains
         * as a compatibility fallback for legacy settings without a stored profile id.
         * If relay is disabled and the explicit simple-failover AWG selector is set, resume
         * that AWG candidate. Falls back to 0 when nothing matches.
         *
         * // NOT cancel-safe: contains suspending [settingsRepository.snapshot].
         * Cancellation leaves [activeCandidateIndex] at 0, which is a safe default.
         */
        private suspend fun resumeIndexOrNull(): Int? = findPersistedCandidateIndex(candidates)

        private suspend fun findPersistedCandidateIndex(available: List<FailoverCandidate>): Int? {
            // Resume on the persisted transport. AWG uses an explicit selector because
            // relay-disabled is also the default-install state and cannot by itself mean AWG.
            val settings = settingsRepository.snapshot()
            if (settings.relayEnabled) {
                val idx =
                    available.indexOfFirst {
                        it is FailoverCandidate.Relay && it.profileId == settings.relayProfileId
                    }
                if (idx >= 0) return idx
                val legacyIdx =
                    available.indexOfFirst {
                        it is FailoverCandidate.Relay && it.relayKind == settings.relayKind
                    }
                if (legacyIdx >= 0) return legacyIdx
            } else if (settings.simpleFailoverAwgProfileId.isNotBlank()) {
                val idx =
                    available.indexOfFirst {
                        it is FailoverCandidate.Awg && it.awgProfileId == settings.simpleFailoverAwgProfileId
                    }
                if (idx >= 0) return idx
            }
            return null
        }

        /**
         * Builds the ordered candidate list from embedded profiles that are actually persisted.
         *
         * Priority order is VLESS+Reality, VLESS/XHTTP, Hysteria2, then AWG. TCP-only relays stay
         * eligible even when UDP ASSOCIATE is requested: they are a deliberately degraded but
         * transport-diverse reserve for networks where UDP itself is blocked. Health confirmation
         * probes only the capabilities that the active candidate can provide.
         *
         * A candidate is only added when its backing data exists. The list is sorted by
         * [FailoverCandidate.priority] so priority 0 is always index 0.
         *
         * // NOT cancel-safe: contains suspending store reads. Cancellation leaves
         * [candidates] empty → coordinator stays inert, which is safe.
         */
        private suspend fun buildCandidates(): List<FailoverCandidate> {
            val result = mutableListOf<FailoverCandidate>()
            val settings = settingsRepository.snapshot()
            val requirements =
                EgressRequirements(
                    tcpConnect = true,
                    udpAssociate = !settings.hasUdpAssociateEnabled() || settings.udpAssociateEnabled,
                )
            activeRequirements = requirements

            val networkScopeKey =
                serviceStateStore.telemetry.value.runtimeFieldTelemetry.telemetryNetworkFingerprintHash
            val relayProfiles = relayCatalog.loadManagedProfiles()

            relayProfiles
                .filter { profile ->
                    profile.kind in supportedRelayKinds &&
                        relayTransportCapabilities(profile.kind)?.tcpConnect == true
                }.sortedWith(
                    compareBy(
                        { relayKindPriority.getValue(it.kind) },
                        { seededOccurrence(it.id, it.kind) },
                        { it.id },
                    ),
                ).forEach { profile ->
                    val supportsUdpAssociation =
                        relayProfileSupportsUdpAssociation(
                            kindId = profile.kind,
                            udpEnabled = profile.udpEnabled,
                            vlessTransport = profile.vlessTransport,
                            vlessFlow = profile.vlessFlow,
                        )
                    val candidateRequirements =
                        requirements.copy(
                            udpAssociate = requirements.udpAssociate && supportsUdpAssociation,
                        )
                    val initialCandidate =
                        InitialRelayCandidate(
                            transportClass =
                                if (profile.kind == RelayKindHysteria2) {
                                    InitialRelayTransportClass.UdpObfuscation
                                } else {
                                    InitialRelayTransportClass.TlsMimicry
                                },
                            profileId = profile.id,
                            relayKind = profile.kind,
                        )
                    if (
                        !egressHealthCache.isCoolingDown(
                            networkScopeKey,
                            EgressProof.from(candidateRequirements),
                            initialCandidate,
                        )
                    ) {
                        result.add(
                            FailoverCandidate.Relay(
                                priority = result.size,
                                profileId = profile.id,
                                relayKind = profile.kind,
                                vlessTransport = profile.vlessTransport.takeIf { profile.kind == RelayKindVless },
                                supportsUdpAssociation = supportsUdpAssociation,
                            ),
                        )
                    }
                }

            // Automatic failover always targets the bundled AWG profile, never the DAO's
            // newest-updated row. Explicit diagnostic AWG profiles are resume-only and are
            // intentionally excluded from this automatic candidate chain.
            val automaticAwg = awgEgressSelection.firstAvailable()
            automaticAwg?.let { request ->
                result.add(FailoverCandidate.Awg(priority = result.size, awgProfileId = request.profileId))
            }

            return result.sortedBy { it.priority }
        }

        private suspend fun resumeOnlyDiagnosticAwg(): FailoverCandidate.Awg? {
            val selected = awgEgressSelection.selectedAwgEgress() ?: return null
            if (selected.profileId == SIMPLE_SEED_AWG_PROFILE_ID) return null
            return FailoverCandidate.Awg(priority = Int.MAX_VALUE, awgProfileId = selected.profileId)
        }

        private fun seededOccurrence(
            profileId: String,
            relayKind: String,
        ): Int {
            val simpleName = relaySeedSimpleNames[relayKind] ?: return Int.MAX_VALUE
            val base = "$SEED_RELAY_PROFILE_ID_PREFIX$simpleName"
            return when {
                profileId == base -> {
                    0
                }

                profileId.startsWith("$base-") -> {
                    profileId.removePrefix("$base-").toIntOrNull()?.minus(1) ?: Int.MAX_VALUE
                }

                else -> {
                    Int.MAX_VALUE
                }
            }
        }

        private companion object {
            const val XUDP_FAILURE_STREAK_THRESHOLD = 3L
            const val SEEDED_VLESS_REALITY_PROFILE_ID = "${SEED_RELAY_PROFILE_ID_PREFIX}VlessReality"
            const val STARTUP_HALT_WAIT_TIMEOUT_MILLIS = 5_000L
            const val TRANSPORT_APPLY_TIMEOUT_MILLIS = 60_000L
            val relayKindPriority =
                mapOf(
                    RelayKindVlessReality to 0,
                    RelayKindVless to 1,
                    RelayKindHysteria2 to 2,
                )
            val supportedRelayKinds = relayKindPriority.keys
            val relaySeedSimpleNames =
                mapOf(
                    RelayKindVlessReality to "VlessReality",
                    RelayKindVless to "Vless",
                    RelayKindHysteria2 to "Hysteria2",
                )
        }
    }

private fun ServiceTelemetrySnapshot.isNetworkHandover(): Boolean =
    when (networkHandoverState) {
        in ActiveNetworkHandoverStates -> true
        null -> runtimeFieldTelemetry.failureClass == FailureClass.NetworkHandover
        else -> false
    }

private val ActiveNetworkHandoverStates =
    setOf(
        NetworkHandoverStates.Observed,
        NetworkHandoverStates.WaitingForNetwork,
        NetworkHandoverStates.DeferredCaptivePortal,
        NetworkHandoverStates.Restarting,
        NetworkHandoverStates.RetryScheduled,
    )

internal fun parseFailoverProxyEndpoint(listenerAddress: String?): FailoverProxyEndpoint? {
    val raw = listenerAddress?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching {
        val uri = URI("socks://$raw")
        val host = uri.host?.takeIf(String::isNotBlank) ?: return null
        val port = uri.port.takeIf { it in 1..65_535 } ?: return null
        FailoverProxyEndpoint(host = host, port = port)
    }.getOrNull()
}

private fun FailoverCandidate.transportKind(): String =
    when (this) {
        is FailoverCandidate.Relay -> relayKind
        is FailoverCandidate.Awg -> TransportKindAmneziaWg
    }

/**
 * Maps a [FailoverCandidate] to privacy-safe protocol details.
 *
 * Relay candidates use their [FailoverCandidate.Relay.relayKind] directly — these are
 * the same wire values emitted by the native runtime (e.g. `"vless_reality"`,
 * `"hysteria2"`). AWG uses the constant `"amneziawg"`. VLESS candidates retain only
 * their transport identifier so XHTTP can be distinguished from generic VLESS without
 * exposing an endpoint or credential.
 */
internal fun FailoverCandidate.toActiveTransportDescriptor(): ActiveTransportDescriptor =
    when (this) {
        is FailoverCandidate.Relay -> {
            ActiveTransportDescriptor(
                protocolKind = relayKind,
                vlessTransport = vlessTransport,
            )
        }

        is FailoverCandidate.Awg -> {
            ActiveTransportDescriptor(protocolKind = "amneziawg")
        }
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
internal abstract class FailoverCoordinatorBindsModule {
    @Binds
    abstract fun bindSimpleFlavorSessionWatcher(coordinator: FailoverCoordinator): SimpleFlavorSessionWatcher

    @Binds
    abstract fun bindActiveTransportProvider(coordinator: FailoverCoordinator): ActiveTransportProvider

    @Binds
    abstract fun bindExplicitUserStartPreparer(coordinator: FailoverCoordinator): ExplicitUserStartPreparer

    @Binds
    @Singleton
    internal abstract fun bindFailoverEgressProbe(probe: CapabilityAwareFailoverEgressProbe): FailoverEgressProbe

    @Binds
    internal abstract fun bindInitialRaceFailoverCoordinator(
        coordinator: FailoverCoordinator,
    ): InitialRaceFailoverCoordinator
}
