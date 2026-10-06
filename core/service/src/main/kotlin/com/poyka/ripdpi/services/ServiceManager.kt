package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

interface ServiceController :
    ServiceUserCommands,
    ServiceRecoveryCommands,
    ServiceTransportMaintenance {
    fun preflight(mode: Mode): ServiceStartPreflightResult
}

interface ServiceUserCommands {
    suspend fun prepareStart(mode: Mode): com.poyka.ripdpi.data.RuntimeActivationReceipt

    suspend fun prepareStop(): com.poyka.ripdpi.data.RuntimeStopReceipt

    suspend fun prepareStopIfCurrent(
        expected: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): com.poyka.ripdpi.data.RuntimeStopReceipt?

    fun startProfileActivation(
        mode: Mode,
        receipt: com.poyka.ripdpi.data.ProfileActivationReceipt,
    ): ServiceStartResult

    suspend fun start(mode: Mode): ServiceStartResult

    fun startPrepared(
        mode: Mode,
        receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt,
    ): ServiceStartResult

    fun stopPrepared(receipt: com.poyka.ripdpi.data.RuntimeStopReceipt): Boolean

    fun finishOwnedRuntime(receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt): Boolean

    suspend fun stop()
}

interface ServiceRecoveryCommands {
    suspend fun captureRuntimeSnapshot(): com.poyka.ripdpi.data.RuntimeAuthoritySnapshot

    suspend fun authorizeBootPolicyStart(
        mode: Mode,
        expected: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): com.poyka.ripdpi.data.RuntimeActivationReceipt?

    fun startBootPolicy(
        mode: Mode,
        receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt,
    ): ServiceStartResult

    /**
     * Boot/package-replacement recovery start; unlike [ServiceUserCommands.start],
     * this is not a newer explicit user intent.
     */
    fun startForBootRecovery(
        mode: Mode,
        broadcastAction: String,
        reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): ServiceStartResult

    /** UI-visible fallback after process death; distinct from a user start. */
    fun startForProcessDeathRecovery(
        mode: Mode,
        reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): ServiceStartResult

    /** Internal diagnostics resume that must not replace explicit user intent. */
    fun startForDiagnostics(
        mode: Mode,
        reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): ServiceStartResult

    /** Internal diagnostics pause that must not replace explicit user intent. */
    fun stopForDiagnostics(reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot)

    /** Reconcile a user Stop after a diagnostics resume raced it. */
    fun stopForDiagnosticsCompensation(reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot)
}

interface ServiceTransportMaintenance {
    /** Recompose the active VPN transport without accepting a user Stop or releasing the TUN barrier. */
    fun restartVpnForTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
        reference: com.poyka.ripdpi.data.PauseAuthorityRef,
    ): ServiceStartResult

    /** Ask a running VPN service to refresh its cached Android lockdown state. */
    fun refreshHardKillSwitchState() = Unit
}

interface StartupFallbackController {
    /** Capture the user-intent generation that owns a potential startup fallback. */
    fun captureStartupFallbackLease(): StartupFallbackLease

    /** Start a fallback only while no newer explicit Start or Stop supersedes [lease]. */
    suspend fun startVpnForStartupFallback(lease: StartupFallbackLease): StartupFallbackDispatchResult
}

/** Explicit profile activation, distinct from automatic transport failover and ordinary Start. */
interface VpnTransportActivationController {
    fun startVpnTransport(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
    ): ServiceStartResult
}

interface StartupFallbackLease

sealed interface StartupFallbackDispatchResult {
    data object Superseded : StartupFallbackDispatchResult

    data class Dispatched(
        val startResult: ServiceStartResult,
        val continuationLease: StartupFallbackLease,
    ) : StartupFallbackDispatchResult
}

private data class UserIntentStartupFallbackLease(
    val generation: Long,
    val snapshot: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
) : StartupFallbackLease

internal const val hardKillSwitchRefreshBroadcastAction =
    "com.poyka.ripdpi.action.REFRESH_HARD_KILL_SWITCH"

sealed interface ServiceStartResult {
    val mode: Mode

    data class Accepted(
        val receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt,
    ) : ServiceStartResult {
        override val mode: Mode get() = receipt.mode
    }

    data class MaintenanceAccepted(
        override val mode: Mode,
    ) : ServiceStartResult

    data class Rejected(
        override val mode: Mode,
        val reason: ServiceStartRejectionReason,
    ) : ServiceStartResult
}

sealed interface ServiceStartRejectionReason {
    data object PausePending : ServiceStartRejectionReason

    data object Superseded : ServiceStartRejectionReason

    data object NotificationsPermissionMissing : ServiceStartRejectionReason

    data object VpnConsentMissing : ServiceStartRejectionReason

    data object DnsSettingsUpdatePending : ServiceStartRejectionReason

    data class ForegroundServiceBlocked(
        val message: String?,
    ) : ServiceStartRejectionReason
}

interface ForegroundServiceStarter {
    fun startForegroundService(
        context: Context,
        intent: Intent,
    )
}

/** Serializes service start/stop intent decisions that must have a single winner. */
@Singleton
class ServiceIntentArbiter
    @Inject
    constructor(
        private val pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority,
    ) {
        private val lock = ReentrantLock()
        private var explicitUserIntentRecorded = false
        private var userIntentGeneration = 0L
        private val explicitGenerationState = MutableStateFlow(0L)
        val explicitUserIntentGeneration = explicitGenerationState.asStateFlow()
        private var doqSaveInProgress = false
        private val pendingVpnStarts = mutableSetOf<Long>()
        private var vpnStartGeneration = 0L

        fun dispatchExplicit(receipt: com.poyka.ripdpi.data.DurableCommandReceipt): ServiceDispatchLease? =
            lock.withLock {
                if (!pauseAuthority.isCurrent(receipt)) return@withLock null
                explicitUserIntentRecorded = true
                userIntentGeneration = Math.addExact(userIntentGeneration, 1)
                explicitGenerationState.value = userIntentGeneration
                ServiceDispatchLease(receipt, userIntentGeneration)
            }

        fun isCurrent(lease: ServiceDispatchLease): Boolean =
            lock.withLock {
                userIntentGeneration == lease.processGeneration && pauseAuthority.isCurrent(lease.durable)
            }

        fun durableReference() = pauseAuthority.reference()

        fun durableSnapshot() = pauseAuthority.snapshotAuthority()

        fun isDurableCurrent(reference: com.poyka.ripdpi.data.PauseAuthorityRef) =
            pauseAuthority.reference() == reference

        val durableIntentStates get() = pauseAuthority.states

        /** The lease covers the entire suspend DataStore update, while start dispatch remains synchronous. */
        fun tryReserveDoqSave(canSave: () -> Boolean): AutoCloseable? =
            lock.withLock {
                if (doqSaveInProgress || pendingVpnStarts.isNotEmpty() || !canSave()) return@withLock null
                doqSaveInProgress = true
                val closed = AtomicBoolean(false)
                AutoCloseable {
                    if (closed.compareAndSet(false, true)) lock.withLock { doqSaveInProgress = false }
                }
            }

        fun dispatchVpnStart(action: () -> ServiceStartResult): ServiceStartResult =
            lock.withLock {
                if (doqSaveInProgress) {
                    return@withLock ServiceStartResult.Rejected(
                        Mode.VPN,
                        ServiceStartRejectionReason.DnsSettingsUpdatePending,
                    )
                }
                vpnStartGeneration += 1
                val generation = vpnStartGeneration
                pendingVpnStarts += generation
                var accepted = false
                try {
                    action().also { accepted = it is ServiceStartResult.Accepted }
                } finally {
                    if (!accepted) pendingVpnStarts -= generation
                }
            }

        fun captureVpnStartGeneration(): Long = lock.withLock { vpnStartGeneration }

        fun completeVpnStart(generation: Long) {
            lock.withLock { pendingVpnStarts -= generation }
        }

        fun <T> serialize(block: () -> T): T = lock.withLock(block)

        fun <T> userStart(
            action: () -> T,
            isAccepted: (T) -> Boolean,
        ): T =
            lock.withLock {
                val previousRecorded = explicitUserIntentRecorded
                val previousGeneration = userIntentGeneration
                explicitUserIntentRecorded = true
                userIntentGeneration += 1
                var accepted = false
                try {
                    action().also { accepted = isAccepted(it) }
                } finally {
                    if (!accepted) {
                        explicitUserIntentRecorded = previousRecorded
                        userIntentGeneration = previousGeneration
                    }
                    explicitGenerationState.value = userIntentGeneration
                }
            }

        fun userStop(action: () -> Unit) {
            lock.withLock {
                explicitUserIntentRecorded = true
                userIntentGeneration += 1
                explicitGenerationState.value = userIntentGeneration
                action()
            }
        }

        /** Invalidates only the caller's still-current dispatch, preserving later explicit user intents. */
        fun cancelIfCurrent(generation: Long): Boolean =
            lock.withLock {
                if (generation != userIntentGeneration) return@withLock false
                explicitUserIntentRecorded = true
                userIntentGeneration += 1
                explicitGenerationState.value = userIntentGeneration
                true
            }

        fun explicitUserStartGuard(
            generation: Long,
            durable: com.poyka.ripdpi.data.PauseAuthorityRef,
        ): ExplicitUserStartGuard = ExplicitUserStartGuard(this, generation, durable)

        fun captureExplicitUserIntentGeneration(): Long = lock.withLock { userIntentGeneration }

        fun <T> runIfExplicitUserIntentCurrent(
            generation: Long,
            action: () -> T,
        ): T? =
            lock.withLock {
                if (generation == userIntentGeneration) action() else null
            }

        fun <T> recovery(action: () -> T): T? =
            lock.withLock {
                if (explicitUserIntentRecorded) null else action()
            }
    }

/** Records a service-side accepted user Stop before its asynchronous teardown. */
@Singleton
class AcceptedUserStopRecorder
    @Inject
    constructor(
        private val bootSessionStateStore: BootSessionStateStore,
        private val runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
        private val serviceIntentArbiter: ServiceIntentArbiter,
        private val profileRecovery: com.poyka.ripdpi.data.ProfileMutationRecoveryAccess,
        private val pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority,
    ) {
        internal suspend fun record(command: AcceptedServiceStop): com.poyka.ripdpi.data.PauseAuthorityRef? =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                profileRecovery.readRecovered {
                    serviceIntentArbiter.runIfExplicitUserIntentCurrent(command.processGeneration) {
                        val reference =
                            when (command) {
                                is AcceptedServiceStop.Prepared -> {
                                    command.reference.takeIf(
                                        serviceIntentArbiter::isDurableCurrent,
                                    )
                                }

                                is AcceptedServiceStop.Notification -> {
                                    pauseAuthority.reserveStop().authority
                                }
                            } ?: return@runIfExplicitUserIntentCurrent null
                        recordState()
                        reference
                    }
                }
            }

        private fun recordState() {
            bootSessionStateStore.setWasRunningAtUpdate(false)
            runtimeResumeIntentTracker.recordAcceptedStop()
        }
    }

@Singleton
class ContextCompatForegroundServiceStarter
    @Inject
    constructor() : ForegroundServiceStarter {
        override fun startForegroundService(
            context: Context,
            intent: Intent,
        ) {
            ContextCompat.startForegroundService(context, intent)
        }
    }

@Singleton
class DefaultServiceController private constructor(
    private val context: Context,
    private val serviceStateStore: ServiceStateStore,
    private val serviceIntentArbiter: ServiceIntentArbiter,
    private val pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority,
    private val appSettings: com.poyka.ripdpi.data.AppSettingsRepository,
    private val profileRecovery: com.poyka.ripdpi.data.ProfileMutationRecoveryAccess,
    runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
    private val dispatch: ServiceControllerDispatch,
) : ServiceController,
    ServiceUserCommands by PreparedServiceUserCommands(
        dispatch,
        profileRecovery,
        pauseAuthority,
        serviceIntentArbiter,
        runtimeResumeIntentTracker,
    ),
    VpnTransportActivationController,
    StartupFallbackController,
    RunningReconnectDispatch {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        serviceStateStore: ServiceStateStore,
        serviceAutomationController: Optional<ServiceAutomationController>,
        foregroundServiceStarter: ForegroundServiceStarter,
        bootSessionStateStore: BootSessionStateStore,
        runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
        serviceIntentArbiter: ServiceIntentArbiter,
        pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority,
        appSettings: com.poyka.ripdpi.data.AppSettingsRepository,
        profileRecovery: com.poyka.ripdpi.data.ProfileMutationRecoveryAccess,
    ) : this(
        context,
        serviceStateStore,
        serviceIntentArbiter,
        pauseAuthority,
        appSettings,
        profileRecovery,
        runtimeResumeIntentTracker,
        ServiceControllerDispatch(
            context,
            serviceStateStore,
            serviceAutomationController,
            foregroundServiceStarter,
            bootSessionStateStore,
            runtimeResumeIntentTracker,
            serviceIntentArbiter,
            pauseAuthority,
        ),
    )

    override suspend fun captureRuntimeSnapshot(): com.poyka.ripdpi.data.RuntimeAuthoritySnapshot =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            profileRecovery.readRecovered { pauseAuthority.snapshotAuthority() }
        }

    override suspend fun authorizeBootPolicyStart(
        mode: Mode,
        expected: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): com.poyka.ripdpi.data.RuntimeActivationReceipt? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            profileRecovery.readRecovered {
                if (!appSettings.snapshot().startOnBoot || preflight(mode) is ServiceStartPreflightResult.Rejected) {
                    null
                } else {
                    serviceIntentArbiter.recovery { pauseAuthority.authorizeBootPolicyStart(mode, expected) }
                }
            }
        }

    override fun startBootPolicy(
        mode: Mode,
        receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt,
    ): ServiceStartResult =
        dispatch.start(
            mode,
            bootRecoveryStartAction,
            dispatchLease = serviceIntentArbiter.dispatchExplicit(receipt),
            expectedDurableReference = receipt.authority,
        )

    override fun preflight(mode: Mode): ServiceStartPreflightResult =
        if (mode == Mode.VPN && VpnService.prepare(context) != null) {
            ServiceStartPreflightResult.Rejected(ServiceStartRejectionReason.VpnConsentMissing)
        } else {
            ServiceStartPreflightResult.Allowed
        }

    override fun stopIfCurrent(lease: ServiceDispatchLease): Boolean {
        if (!serviceIntentArbiter.isCurrent(lease)) return false
        dispatch.stop(stopAction, lease, lease.durable.authority, pauseAuthority.snapshotAuthority())
        return true
    }

    override fun startIfCurrent(
        mode: Mode,
        lease: ServiceDispatchLease,
    ): ServiceStartResult? {
        if (!serviceIntentArbiter.isCurrent(lease)) return null
        return dispatch.start(mode, startAction, dispatchLease = lease)
    }

    private fun dispatchRecoveryStart(
        mode: Mode,
        action: String,
        expected: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): ServiceStartResult =
        pauseAuthority.authorizeRecovery(mode, expected)?.let { receipt ->
            serviceIntentArbiter.dispatchExplicit(receipt)?.let { lease ->
                dispatch.start(mode, action, dispatchLease = lease, expectedDurableReference = receipt.authority)
            }
        } ?: ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)

    override fun startForBootRecovery(
        mode: Mode,
        broadcastAction: String,
        reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): ServiceStartResult =
        dispatchRecoveryStart(
            mode,
            if (broadcastAction == Intent.ACTION_BOOT_COMPLETED) {
                bootRecoveryStartAction
            } else {
                packageReplacedRecoveryStartAction
            },
            expected = reference,
        )

    override fun startVpnTransport(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
    ): ServiceStartResult {
        val lease =
            serviceIntentArbiter.dispatchExplicit(receipt)
                ?: return ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.Superseded)
        return dispatch.start(Mode.VPN, transportActivationStartAction, requestId, expectedTarget, lease)
    }

    override fun startForProcessDeathRecovery(
        mode: Mode,
        reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): ServiceStartResult = dispatchRecoveryStart(mode, processDeathRecoveryStartAction, reference)

    override fun startForDiagnostics(
        mode: Mode,
        reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): ServiceStartResult = dispatchRecoveryStart(mode, diagnosticsStartAction, reference)

    override fun restartVpnForTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
        reference: com.poyka.ripdpi.data.PauseAuthorityRef,
    ): ServiceStartResult =
        dispatch.start(
            mode = Mode.VPN,
            action = transportFailoverRestartAction,
            transportFailoverRequestId = requestId,
            transportFailoverTarget = expectedTarget,
            expectedDurableReference = reference,
        )

    override fun captureStartupFallbackLease(): StartupFallbackLease =
        serviceIntentArbiter.serialize {
            UserIntentStartupFallbackLease(
                serviceIntentArbiter.captureExplicitUserIntentGeneration(),
                pauseAuthority.snapshotAuthority(),
            )
        }

    override suspend fun startVpnForStartupFallback(lease: StartupFallbackLease): StartupFallbackDispatchResult {
        val captured = lease as? UserIntentStartupFallbackLease ?: return StartupFallbackDispatchResult.Superseded
        val prepared =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                profileRecovery.readRecovered {
                    serviceIntentArbiter.runIfExplicitUserIntentCurrent(captured.generation) {
                        pauseAuthority.authorizeStartupFallback(captured.snapshot)?.let { receipt ->
                            serviceIntentArbiter.dispatchExplicit(receipt)?.let { dispatchLease ->
                                dispatchLease to
                                    UserIntentStartupFallbackLease(
                                        dispatchLease.processGeneration,
                                        pauseAuthority.snapshotAuthority(),
                                    )
                            }
                        }
                    }
                }
            }
        return if (prepared == null) {
            StartupFallbackDispatchResult.Superseded
        } else {
            StartupFallbackDispatchResult.Dispatched(
                dispatch.start(Mode.VPN, startupFallbackStartAction, dispatchLease = prepared.first),
                prepared.second,
            )
        }
    }

    override fun stopForDiagnostics(reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot) {
        if (pauseAuthority.snapshotAuthority() == reference) {
            dispatch.stop(diagnosticsStopAction, null, reference.reference, expectedSnapshot = reference)
        }
    }

    override fun stopForDiagnosticsCompensation(reference: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot) {
        if (pauseAuthority.snapshotAuthority() == reference) {
            dispatch.stop(diagnosticsCompensatingStopAction, null, reference.reference, expectedSnapshot = reference)
        }
    }

    override fun refreshHardKillSwitchState() {
        val (status, mode) = serviceStateStore.status.value
        if (status != AppStatus.Running || mode != Mode.VPN) {
            return
        }
        context.sendBroadcast(
            Intent(hardKillSwitchRefreshBroadcastAction).setPackage(context.packageName),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceControllerModule {
    @Binds
    @Singleton
    abstract fun bindRunningReconnectDispatch(controller: DefaultServiceController): RunningReconnectDispatch

    @Binds
    @Singleton
    abstract fun bindVpnTransportActivationController(
        serviceController: DefaultServiceController,
    ): VpnTransportActivationController

    @Binds
    @Singleton
    abstract fun bindServiceController(serviceController: DefaultServiceController): ServiceController

    @Binds
    @Singleton
    abstract fun bindStartupFallbackController(serviceController: DefaultServiceController): StartupFallbackController
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ForegroundServiceStarterModule {
    @Binds
    @Singleton
    abstract fun bindForegroundServiceStarter(starter: ContextCompatForegroundServiceStarter): ForegroundServiceStarter
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiagnosticsRuntimeCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsRuntimeCoordinator(
        coordinator: DefaultDiagnosticsRuntimeCoordinator,
    ): DiagnosticsRuntimeCoordinator
}

data class ServiceDispatchLease(
    val durable: com.poyka.ripdpi.data.DurableCommandReceipt,
    val processGeneration: Long,
)
