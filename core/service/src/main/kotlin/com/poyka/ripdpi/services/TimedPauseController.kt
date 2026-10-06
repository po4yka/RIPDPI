package com.poyka.ripdpi.services

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseFailure
import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.ServiceStateStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** An alarm is a wake-up signal, never an exemption permitting a new foreground service. */
@Singleton
class TimedPauseController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val authority: PauseIntentAuthority,
        private val recovery: ProfileMutationRecoveryAccess,
        private val stateStore: ServiceStateStore,
        private val lockdown: LiveVpnLockdownReader,
        private val intentArbiter: ServiceIntentArbiter,
    ) {
        private val commands = Mutex()
        private val ownersLock = Any()
        private var owner: PausedServiceHost? = null
        private var ownerObserver: kotlinx.coroutines.Job? = null
        private val cleanupState = kotlinx.coroutines.flow.MutableStateFlow<PauseIntent?>(null)
        val cleanupPending: kotlinx.coroutines.flow.StateFlow<PauseIntent?> = cleanupState

        internal fun attach(host: PausedServiceHost) =
            synchronized(ownersLock) {
                ownerObserver?.cancel()
                owner = host
                ownerObserver =
                    host.scope.launch(Dispatchers.IO) {
                        var previous = authority.snapshot()
                        authority.states
                            .map {
                                it?.copy(
                                    profileUtility =
                                        com.poyka.ripdpi.data.ProfileUtilityState
                                            .empty(),
                                )
                            }.distinctUntilChanged()
                            .collect { state ->
                                val old = previous
                                previous = state?.pause
                                if (old != null && state?.pause == null && state?.generation != old.generation) {
                                    cancelAlarm(old)
                                    if (authority.snapshot() == null &&
                                        authority.reference().generation == state?.generation
                                    ) {
                                        setPauseBootEnabled(false)
                                        host.discardIdleShell(old)
                                    }
                                }
                            }
                    }
            }

        internal fun detach(identity: Any) =
            synchronized(ownersLock) {
                if (owner?.identity === identity) {
                    ownerObserver?.cancel()
                    ownerObserver = null
                    owner = null
                }
            }

        fun canPause(): Boolean {
            val (status, mode) = stateStore.status.value
            return status == AppStatus.Running && authority.isInitialized() && authority.snapshot() == null &&
                cleanupState.value?.generation != authority.reference().generation &&
                liveHost()?.mode == mode &&
                (mode != Mode.VPN || lockdown.read().status == AndroidHardKillSwitchStatus.NOT_ENABLED)
        }

        suspend fun pause(durationMillis: Long) {
            val expected = authority.snapshotAuthority()
            val expectedStatus = stateStore.status.value
            val host = checkNotNull(liveHost())
            withContext(Dispatchers.IO) {
                commands.withLock {
                    val intent =
                        recovery.readRecovered {
                            intentArbiter.serialize {
                                check(
                                    canPause() && liveHost()?.identity === host.identity &&
                                        stateStore.status.value == expectedStatus,
                                ) { "Connection cannot currently pause" }
                                authority.begin(host.mode, durationMillis, expected).also {
                                    intentArbiter.userStop {}
                                }
                            }
                        }
                    if (!authority.isCurrent(intent)) return@withLock
                    setPauseBootEnabled(true)
                    // Persistence is now proved. ViewModel cancellation cannot abandon
                    // partially released native ownership.
                    withContext(NonCancellable) {
                        val release =
                            try {
                                host.release(intent)
                            } catch (_: Exception) {
                                RuntimeStopOutcome.CleanupPending
                            }
                        when (release) {
                            RuntimeStopOutcome.FullyReleased -> {
                                if (authority.transition(intent, PausePhase.Paused, null)) {
                                    schedule(intent)
                                    host.showPaused(checkNotNull(authority.snapshot()))
                                } else {
                                    host.discardIdleShell(intent)
                                }
                            }

                            RuntimeStopOutcome.CleanupPending -> {
                                authority.transition(intent, PausePhase.CleanupPending, null)
                                host.showPaused(checkNotNull(authority.snapshot()))
                            }

                            RuntimeStopOutcome.Superseded -> {
                                Unit
                            }
                        }
                    }
                }
            }
        }

        /** Foreground UI action: may reconstruct a minimal foreground shell under normal Android eligibility. */
        suspend fun resumeNow() {
            val intent = authority.snapshot() ?: return
            resumeBound(intent)
        }

        private suspend fun resumeBound(intent: PauseIntent) {
            if (!authority.isCurrent(intent)) return
            val host = liveHost()
            if (host != null) {
                resume(host, intent, true)
            } else {
                val service =
                    if (Mode.fromString(intent.mode) ==
                        Mode.VPN
                    ) {
                        RipDpiVpnService::class.java
                    } else {
                        RipDpiProxyService::class.java
                    }
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, service).apply {
                            action = PauseRestoreAction
                            data = pauseIdentity(intent)
                        },
                    )
                } catch (_: IllegalStateException) {
                    authority.transition(intent, PausePhase.Deferred, PauseFailure.BackgroundRestricted)
                }
            }
        }

        /** Generation is invalidated before the shell is stopped; stale alarms become harmless. */
        suspend fun stop(): RuntimeStopOutcome = stopBound(null)

        private suspend fun stopBound(expected: PauseIntent?): RuntimeStopOutcome =
            withContext(Dispatchers.IO) {
                val reserved =
                    recovery.readRecovered {
                        val current =
                            authority.snapshot() ?: cleanupState.value?.takeIf {
                                authority.reference().generation == it.generation
                            } ?: return@readRecovered null
                        if (expected != null &&
                            (current.generation != expected.generation || current.token != expected.token)
                        ) {
                            return@readRecovered null
                        }
                        val receipt =
                            if (authority.snapshot() != null) {
                                authority.cancelMatchingPause(current)
                            } else {
                                authority.cancelMatchingCleanup(current.reference)
                            }
                        receipt?.let {
                            val pending =
                                current.copy(
                                    generation = it.authority.generation,
                                    token =
                                        java.util.UUID
                                            .randomUUID()
                                            .toString(),
                                    phase = PausePhase.CleanupPending,
                                    failure = null,
                                )
                            cleanupState.value = pending
                            Triple(current, it, pending)
                        }
                    }
                        ?: return@withContext if (expected ==
                            null
                        ) {
                            RuntimeStopOutcome.FullyReleased
                        } else {
                            RuntimeStopOutcome.Superseded
                        }
                val lease =
                    intentArbiter.dispatchExplicit(reserved.second)
                        ?: return@withContext RuntimeStopOutcome.Superseded
                cancelAlarm(reserved.first)
                if (!intentArbiter.isCurrent(lease)) return@withContext RuntimeStopOutcome.Superseded
                setPauseBootEnabled(false)
                val host = liveHost() ?: return@withContext RuntimeStopOutcome.FullyReleased
                val pending = reserved.third
                host.showPaused(pending)
                val outcome =
                    withContext(NonCancellable) {
                        try {
                            host.stopShell(lease)
                        } catch (_: IllegalStateException) {
                            RuntimeStopOutcome.CleanupPending
                        }
                    }
                if (!intentArbiter.isCurrent(lease)) {
                    cleanupState.compareAndSet(pending, null)
                    return@withContext RuntimeStopOutcome.Superseded
                }
                when (outcome) {
                    RuntimeStopOutcome.CleanupPending -> host.showPaused(pending)
                    RuntimeStopOutcome.FullyReleased -> cleanupState.compareAndSet(pending, null)
                    RuntimeStopOutcome.Superseded -> cleanupState.compareAndSet(pending, null)
                }
                outcome
            }

        /** Process-local evidence of retained resources; it never creates an eligible resume lease. */
        internal fun retainPartialCleanup(host: PausedServiceHost) {
            val current = authority.snapshot()
            val projection =
                current?.copy(phase = PausePhase.CleanupPending, failure = null) ?: PauseIntent(
                    generation = authority.reference().generation,
                    token =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    mode = host.mode.preferenceValue,
                    createdWallMillis = System.currentTimeMillis(),
                    createdElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                    deadlineWallMillis = System.currentTimeMillis(),
                    deadlineElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                    bootCount = 0,
                    phase = PausePhase.CleanupPending,
                    failure = null,
                )
            cleanupState.value = projection
            host.showPaused(projection)
        }

        internal fun handleShellStart(
            host: PausedServiceHost,
            command: Intent?,
        ): Boolean {
            val intent = authority.snapshot()
            val recoveryCommand = command?.action == PauseRestoreAction || isServiceRecoveryStartAction(command?.action)
            return if (intent == null || Mode.fromString(intent.mode) != host.mode || !recoveryCommand) {
                false
            } else {
                attach(host)
                host.showPaused(intent)
                host.scope.launch(Dispatchers.IO) {
                    recovery.recover()
                    commands.withLock {
                        if (!authority.isCurrent(intent)) return@withLock
                        when (intent.phase) {
                            PausePhase.Releasing, PausePhase.Resuming -> {
                                authority.transition(
                                    intent,
                                    PausePhase.Deferred,
                                    PauseFailure.RuntimeRejected,
                                )
                            }

                            PausePhase.CleanupPending -> {
                                authority.transition(
                                    intent,
                                    PausePhase.Deferred,
                                    PauseFailure.RuntimeRejected,
                                )
                            }

                            else -> {
                                Unit
                            }
                        }
                        val current = authority.snapshot() ?: return@withLock
                        val remaining = authority.remainingMillis(current)
                        when {
                            command?.action == PauseRestoreAction && command.data ==
                                pauseIdentity(
                                    current,
                                )
                            -> {
                                resumeLocked(host, current, true)
                            }

                            wasUserStopped(
                                current,
                            ) -> {
                                authority.transition(current, PausePhase.Deferred, PauseFailure.UserStopped)
                            }

                            remaining == null -> {
                                authority.transition(
                                    current,
                                    PausePhase.Deferred,
                                    PauseFailure.ClockChanged,
                                )
                            }

                            remaining == 0L -> {
                                resumeLocked(host, current, false)
                            }

                            else -> {
                                schedule(current)
                            }
                        }
                        authority.snapshot()?.let(host.showPaused)
                    }
                }
                true
            }
        }

        internal suspend fun receive(command: Intent) {
            val intent = authority.snapshot() ?: cleanupState.value ?: return
            if (command.data != pauseIdentity(intent)) return
            when (command.action) {
                PauseStopAction -> {
                    stopBound(intent)
                }

                PauseResumeAction -> {
                    resumeBound(intent)
                }

                PauseAlarmAction -> {
                    val host = liveHost()
                    if (host == null) {
                        authority.transition(intent, PausePhase.Deferred, PauseFailure.BackgroundRestricted)
                    } else {
                        host.scope.launch(Dispatchers.IO) { resume(host, intent, false) }
                    }
                }
            }
        }

        private suspend fun resume(
            host: PausedServiceHost,
            intent: PauseIntent,
            immediate: Boolean,
        ) {
            recovery.recover()
            commands.withLock { resumeLocked(host, intent, immediate) }
        }

        private suspend fun resumeLocked(
            host: PausedServiceHost,
            intent: PauseIntent,
            immediate: Boolean,
        ) {
            if (liveHost()?.identity !== host.identity || !authority.isCurrent(intent)) return
            val mode = Mode.fromString(intent.mode)
            val failure =
                when {
                    wasUserStopped(intent) && !immediate -> PauseFailure.UserStopped

                    mode == Mode.VPN && VpnService.prepare(context) != null -> PauseFailure.ConsentRequired

                    mode == Mode.VPN &&
                        lockdown.read().status != AndroidHardKillSwitchStatus.NOT_ENABLED -> PauseFailure.Lockdown

                    else -> null
                }
            val activation = if (failure == null) authority.claimResume(intent, immediate) else null
            if (failure != null) {
                authority.transition(intent, PausePhase.Deferred, failure)
            } else if (activation != null) {
                authority.snapshot()?.let(host.showPaused)
                cancelAlarm(intent)
                try {
                    kotlinx.coroutines.coroutineScope {
                        val ownerJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                        val watcher =
                            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                authority.states.first { it?.generation != intent.generation }
                                ownerJob?.cancel(CancellationException("Resume intent superseded"))
                            }
                        try {
                            withContext(
                                PauseResumeAuthority(authority, intent) +
                                    RuntimeCommandStartAuthority(
                                        com.poyka.ripdpi.data.RuntimeAppliedIntent
                                            .Resume(intent, activation),
                                    ),
                            ) { host.resume(intent) }
                        } finally {
                            watcher.cancel()
                        }
                    }
                    if (authority.isCurrent(
                            intent,
                        )
                    ) {
                        authority.transition(intent, PausePhase.Deferred, PauseFailure.RuntimeRejected)
                    }
                } catch (cancelled: CancellationException) {
                    withContext(
                        NonCancellable,
                    ) { authority.transition(intent, PausePhase.Deferred, PauseFailure.RuntimeRejected) }
                    throw cancelled
                } catch (_: IllegalStateException) {
                    authority.transition(intent, PausePhase.Deferred, PauseFailure.RuntimeRejected)
                }
            }
            authority.snapshot()?.let(host.showPaused) ?: setPauseBootEnabled(false)
        }

        internal suspend fun restoreAfterBoot(action: String?) {
            if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            recovery.recover()
            val intent = authority.snapshot()
            when {
                intent == null -> {
                    setPauseBootEnabled(false)
                }

                wasUserStopped(intent) -> {
                    authority.transition(intent, PausePhase.Deferred, PauseFailure.UserStopped)
                }

                else -> {
                    val service =
                        if (Mode.fromString(intent.mode) ==
                            Mode.VPN
                        ) {
                            RipDpiVpnService::class.java
                        } else {
                            RipDpiProxyService::class.java
                        }
                    try {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, service).apply {
                                this.action =
                                    if (action ==
                                        Intent.ACTION_BOOT_COMPLETED
                                    ) {
                                        bootRecoveryStartAction
                                    } else {
                                        packageReplacedRecoveryStartAction
                                    }
                                data = pauseIdentity(intent)
                                putExtra(durableIntentGenerationExtra, intent.generation)
                            },
                        )
                    } catch (_: IllegalStateException) {
                        authority.transition(intent, PausePhase.Deferred, PauseFailure.BackgroundRestricted)
                    }
                }
            }
        }

        private fun setPauseBootEnabled(enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(context, PauseBootReceiver::class.java),
                if (enabled) {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }

        private fun schedule(intent: PauseIntent) {
            val remaining = authority.remainingMillis(intent) ?: return
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                Math.addExact(SystemClock.elapsedRealtime(), remaining),
                pendingIntent(context, intent, PauseAlarmAction),
            )
        }

        private fun cancelAlarm(intent: PauseIntent) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, intent, PauseAlarmAction))
        }

        private fun wasUserStopped(intent: PauseIntent): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                context
                    .getSystemService(
                        ActivityManager::class.java,
                    ).getHistoricalProcessExitReasons(context.packageName, 0, RecentProcessExitLimit)
                    .any {
                        it.timestamp >= intent.createdWallMillis &&
                            it.reason == ApplicationExitInfo.REASON_USER_REQUESTED
                    }

        private fun liveHost(): PausedServiceHost? = synchronized(ownersLock) { owner }

        companion object {
            private const val RecentProcessExitLimit = 8
            internal const val PauseRestoreAction = "com.poyka.ripdpi.pause.RESTORE"
            internal const val PauseResumeAction = "com.poyka.ripdpi.pause.RESUME"
            internal const val PauseStopAction = "com.poyka.ripdpi.pause.STOP"
            internal const val PauseAlarmAction = "com.poyka.ripdpi.pause.DEADLINE"

            internal fun pauseIdentity(intent: PauseIntent): Uri =
                Uri
                    .Builder()
                    .scheme("ripdpi-pause")
                    .authority("local")
                    .appendPath(intent.generation.toString())
                    .appendPath(intent.token)
                    .build()

            internal fun pendingIntent(
                context: Context,
                intent: PauseIntent,
                action: String,
            ): PendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, PauseDeadlineReceiver::class.java).setAction(action).setData(pauseIdentity(intent)),
                    PendingIntent.FLAG_IMMUTABLE,
                )
        }
    }

/** This host exists independently from native session graphs. */
internal class PausedServiceHost(
    val identity: Any,
    val mode: Mode,
    val scope: CoroutineScope,
    val release: suspend (PauseIntent) -> RuntimeStopOutcome,
    val resume: suspend (PauseIntent) -> Unit,
    val showPaused: (PauseIntent) -> Unit,
    val stopShell: suspend (ServiceDispatchLease) -> RuntimeStopOutcome,
    val discardIdleShell: suspend (PauseIntent) -> Unit,
)

@AndroidEntryPoint
class PauseDeadlineReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TimedPauseController

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { controller.receive(intent) }.onFailure { failure ->
                    if (failure !is Exception || failure is CancellationException) throw failure
                    co.touchlab.kermit.Logger
                        .e { "Pause deadline handling failed: ${failure::class.java.simpleName}" }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

internal class PauseResumeAuthority(
    private val authority: PauseIntentAuthority,
    val intent: PauseIntent,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    fun ensureCurrent() {
        if (!authority.isCurrent(intent)) throw CancellationException("Pause resume superseded")
    }

    fun ensureGeneration() {
        if (authority.reference() !=
            intent.reference
        ) {
            throw CancellationException("Pause resume generation superseded")
        }
    }

    companion object Key : kotlin.coroutines.CoroutineContext.Key<PauseResumeAuthority>
}
