package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.ServiceStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Guarded framework dispatch; Accepted is dispatch acceptance, never runtime readiness. */
interface RunningReconnectDispatch {
    fun preflight(mode: Mode): ServiceStartResult

    fun stopIfCurrent(generation: Long): Boolean

    fun startIfCurrent(
        mode: Mode,
        generation: Long,
    ): ServiceStartResult?
}

enum class RunningReconnectFailure {
    PermissionRequired,
    Lockdown,
    LockdownUnknown,
    StopTimedOut,
    StartRejected,
    StartTimedOut,
    RuntimeFailed,
    Superseded,
}

sealed interface RunningReconnectResult {
    data object Applied : RunningReconnectResult

    data object NotRunning : RunningReconnectResult

    data object AlreadyPending : RunningReconnectResult

    data class Failed(
        val reason: RunningReconnectFailure,
    ) : RunningReconnectResult
}

sealed interface RunningReconnectState {
    data object Idle : RunningReconnectState

    data class Stopping(
        val mode: Mode,
    ) : RunningReconnectState

    data class Starting(
        val mode: Mode,
    ) : RunningReconnectState

    data class Failed(
        val mode: Mode,
        val reason: RunningReconnectFailure,
    ) : RunningReconnectState

    data class Cancelled(
        val mode: Mode,
    ) : RunningReconnectState
}

sealed interface RunningReconnectRequest {
    val mode: Mode

    data class CurrentSaved(
        override val mode: Mode,
    ) : RunningReconnectRequest

    data class ConfirmedRuntime(
        override val mode: Mode,
        val runtimeId: String,
        val revision: Long,
    ) : RunningReconnectRequest {
        override fun toString(): String = "ConfirmedRuntime(mode=$mode, revision=$revision)"
    }
}

interface RunningServiceReconnect {
    val reconnectState: StateFlow<RunningReconnectState>

    fun cancelReconnect()

    suspend fun reconnect(request: RunningReconnectRequest): RunningReconnectResult
}

/** A single explicit intent owns preflight, confirmed teardown, dispatch and positive runtime ACK. */
@Singleton
class RunningServiceReconnectCoordinator
    @Inject
    constructor(
        private val dispatch: RunningReconnectDispatch,
        private val arbiter: ServiceIntentArbiter,
        private val serviceState: ServiceStateStore,
        private val configurations: AppliedRuntimeConfigurationSource,
        private val liveLockdown: LiveVpnLockdownReader,
    ) : RunningServiceReconnect {
        private val mutex = Mutex()
        private val state = MutableStateFlow<RunningReconnectState>(RunningReconnectState.Idle)
        override val reconnectState = state.asStateFlow()
        private val cancellationLock = Any()
        private var ownerJob: kotlinx.coroutines.Job? = null
        private var ownerGeneration: Long? = null

        override fun cancelReconnect() {
            synchronized(cancellationLock) {
                ownerGeneration?.let(arbiter::cancelIfCurrent)
                ownerJob?.cancel()
            }
        }

        @Inject fun observeManualRuntimeAcknowledgments(
            @com.poyka.ripdpi.data.ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
        ) {
            scope.launch {
                configurations.applications.collect { applications ->
                    val outcome = state.value
                    val mode =
                        when (outcome) {
                            is RunningReconnectState.Failed -> outcome.mode
                            is RunningReconnectState.Cancelled -> outcome.mode
                            else -> null
                        }
                    if (mode != null && applications[mode] is RuntimeConfigurationApplication.Applied &&
                        applications[mode].attemptIdentity()?.first !in excludedOutcomeRuntimes
                    ) {
                        state.compareAndSet(outcome, RunningReconnectState.Idle)
                    }
                }
            }
        }

        @Volatile private var excludedOutcomeRuntimes: Set<String> = emptySet()

        override suspend fun reconnect(request: RunningReconnectRequest): RunningReconnectResult =
            if (mutex.tryLock()) reconnectOwned(request) else RunningReconnectResult.AlreadyPending

        private suspend fun reconnectOwned(request: RunningReconnectRequest): RunningReconnectResult {
            val mode = request.mode
            val callerJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            synchronized(cancellationLock) { ownerJob = callerJob }
            excludedOutcomeRuntimes =
                configurations.applications.value.values
                    .mapNotNull { it.attemptIdentity()?.first }
                    .toSet()
            try {
                return when {
                    serviceState.status.value.first != AppStatus.Running -> {
                        RunningReconnectResult.NotRunning
                    }

                    !matchesConfirmation(request) -> {
                        failure(mode, RunningReconnectFailure.Superseded)
                    }

                    else -> {
                        val preflightFailure = preflight(mode, serviceState.status.value.second)
                        if (preflightFailure != null) failure(mode, preflightFailure) else reconnectAccepted(request)
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(cancellationLock) { ownerGeneration }?.let(arbiter::cancelIfCurrent)
                state.value = RunningReconnectState.Cancelled(mode)
                throw cancelled
            } finally {
                if (state.value is RunningReconnectState.Failed) {
                    synchronized(cancellationLock) { ownerGeneration }?.let(arbiter::cancelIfCurrent)
                }
                synchronized(cancellationLock) {
                    ownerJob = null
                    ownerGeneration = null
                }
                mutex.unlock()
            }
        }

        private suspend fun reconnectAccepted(request: RunningReconnectRequest): RunningReconnectResult {
            val mode = request.mode
            val excluded =
                configurations.applications.value.values
                    .mapNotNull { it.attemptIdentity()?.first }
                    .toMutableSet()
            val generation =
                arbiter.userStart(
                    action = { arbiter.captureExplicitUserIntentGeneration() },
                    isAccepted = { true },
                )
            synchronized(cancellationLock) { ownerGeneration = generation }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val stopFailure =
                if (matchesConfirmation(
                        request,
                    )
                ) {
                    stopRuntime(mode, generation)
                } else {
                    RunningReconnectFailure.Superseded
                }
            return if (stopFailure != null) {
                failure(mode, stopFailure)
            } else {
                configurations.applications.value.values
                    .mapNotNullTo(excluded) { it.attemptIdentity()?.first }
                startReplacement(mode, generation, excluded)
            }
        }

        private suspend fun stopRuntime(
            mode: Mode,
            generation: Long,
        ): RunningReconnectFailure? {
            state.value = RunningReconnectState.Stopping(mode)
            return if (!dispatch.stopIfCurrent(generation)) {
                RunningReconnectFailure.Superseded
            } else {
                val halted =
                    withTimeoutOrNull(StopTimeoutMillis) {
                        combine(serviceState.status, arbiter.explicitUserIntentGeneration) { status, current ->
                            status.first to current
                        }.first { (status, current) -> status == AppStatus.Halted || current != generation }
                    }
                when {
                    halted == null -> RunningReconnectFailure.StopTimedOut
                    halted.second != generation -> RunningReconnectFailure.Superseded
                    else -> null
                }
            }
        }

        private suspend fun startReplacement(
            mode: Mode,
            generation: Long,
            excluded: Set<String>,
        ): RunningReconnectResult {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            state.value = RunningReconnectState.Starting(mode)
            return when (dispatch.startIfCurrent(mode, generation)) {
                null -> failure(mode, RunningReconnectFailure.Superseded)
                is ServiceStartResult.Rejected -> failure(mode, RunningReconnectFailure.StartRejected)
                is ServiceStartResult.Accepted -> awaitReplacement(mode, generation, excluded)
            }
        }

        private suspend fun awaitReplacement(
            mode: Mode,
            generation: Long,
            excluded: Set<String>,
        ): RunningReconnectResult {
            val applied =
                withTimeoutOrNull(StartTimeoutMillis) {
                    combine(
                        configurations.applications,
                        arbiter.explicitUserIntentGeneration,
                    ) { applications, current ->
                        applications[mode] to current
                    }.first { (application, current) ->
                        current != generation || application.isReplacementOutcome(excluded)
                    }
                }
            return when {
                applied == null -> {
                    failure(mode, RunningReconnectFailure.StartTimedOut)
                }

                applied.second != generation -> {
                    failure(mode, RunningReconnectFailure.Superseded)
                }

                applied.first !is RuntimeConfigurationApplication.Applied -> {
                    failure(mode, RunningReconnectFailure.RuntimeFailed)
                }

                else -> {
                    state.value = RunningReconnectState.Idle
                    RunningReconnectResult.Applied
                }
            }
        }

        private fun matchesConfirmation(request: RunningReconnectRequest): Boolean =
            when {
                request !is RunningReconnectRequest.ConfirmedRuntime -> {
                    true
                }

                serviceState.status.value.second != request.mode -> {
                    false
                }

                else -> {
                    val application =
                        configurations.applications.value[request.mode] as? RuntimeConfigurationApplication.Applied
                    application?.configuration?.let {
                        it.runtimeId == request.runtimeId && it.revision == request.revision &&
                            configurations.pendingChanges.value[request.mode] ==
                            com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus.SavedChangesPending
                    } == true
                }
            }

        private fun preflight(
            mode: Mode,
            activeMode: Mode,
        ): RunningReconnectFailure? =
            when (val result = dispatch.preflight(mode)) {
                is ServiceStartResult.Rejected -> {
                    when (result.reason) {
                        ServiceStartRejectionReason.VpnConsentMissing -> RunningReconnectFailure.PermissionRequired
                        else -> RunningReconnectFailure.StartRejected
                    }
                }

                is ServiceStartResult.Accepted -> {
                    if (activeMode == Mode.VPN) {
                        when (liveLockdown.read().status) {
                            AndroidHardKillSwitchStatus.ENABLED -> RunningReconnectFailure.Lockdown
                            AndroidHardKillSwitchStatus.UNKNOWN -> RunningReconnectFailure.LockdownUnknown
                            AndroidHardKillSwitchStatus.NOT_ENABLED -> null
                        }
                    } else {
                        null
                    }
                }
            }

        private fun failure(
            mode: Mode,
            reason: RunningReconnectFailure,
        ): RunningReconnectResult.Failed {
            state.value = RunningReconnectState.Failed(mode, reason)
            return RunningReconnectResult.Failed(reason)
        }
    }

private fun RuntimeConfigurationApplication?.isReplacementOutcome(excluded: Set<String>): Boolean {
    val identity = attemptIdentity()
    val completed = this is RuntimeConfigurationApplication.Failed || this is RuntimeConfigurationApplication.Applied
    return completed && identity != null && identity.first !in excluded
}

private fun RuntimeConfigurationApplication?.attemptIdentity(): Pair<String, Long>? =
    when (this) {
        is RuntimeConfigurationApplication.Applied -> configuration.runtimeId to configuration.revision
        is RuntimeConfigurationApplication.Applying -> attempt.runtimeId to attempt.revision
        is RuntimeConfigurationApplication.Failed -> attempt.runtimeId to attempt.revision
        else -> null
    }

private const val StopTimeoutMillis = 10_000L
private const val StartTimeoutMillis = 60_000L

@Module
@InstallIn(SingletonComponent::class)
abstract class RunningServiceReconnectModule {
    @Binds
    abstract fun bindRunningServiceReconnect(coordinator: RunningServiceReconnectCoordinator): RunningServiceReconnect
}
