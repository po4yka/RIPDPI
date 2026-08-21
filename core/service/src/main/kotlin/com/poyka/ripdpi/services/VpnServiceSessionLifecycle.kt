package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeCoordinator
import dagger.hilt.EntryPoints
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Provider

internal class VpnServiceSessionLifecycle(
    private val service: RipDpiVpnService,
    private val serviceStateStore: ServiceStateStore,
    private val sessionComponentBuilderProvider: Provider<VpnServiceSessionComponentBuilder>,
    private val activeProtectSocketPathProvider: ActiveProtectSocketPathProvider,
    private val runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
    private val acceptedUserStopRecorder: AcceptedUserStopRecorder,
    private val transportFailoverApplyTracker: TransportFailoverApplyTracker,
    private val serviceStopProvenanceRecorder: ServiceStopProvenanceRecorder,
    private val beforeUserStart: suspend () -> Unit = {},
    private val awaitStartupReadiness: suspend () -> Boolean = { true },
    private val recoverProfileMutations: suspend () -> Unit = {},
    private val awaitRecoveryUnderlay: suspend () -> Unit = {},
    private val hardKillSwitchRefreshBroadcastLifecycle: HardKillSwitchRefreshLifecycle =
        HardKillSwitchRefreshBroadcastLifecycle(
            context = service,
            onRefreshState = service::refreshHardKillSwitchState,
            onRefreshNotification = service::refreshForegroundNotification,
        ),
) {
    private var sessionComponent: VpnServiceSessionComponent? = null
    private var coordinator: VpnServiceRuntimeCoordinator? = null
    private var protectSocketServer: VpnProtectSocketServer? = null
    private val cleanup = VpnServiceSessionCleanup()

    fun createShellDelegate(): ServiceShellDelegate {
        hardKillSwitchRefreshBroadcastLifecycle.start()
        return runCatching {
            val entryPoint = createSessionEntryPoint()
            val runtimeCoordinator = entryPoint.coordinator()
            val socketServer = entryPoint.protectSocketServer()
            coordinator = runtimeCoordinator
            protectSocketServer = socketServer
            val protectionFailure =
                runCatching {
                    establishProtectPath(
                        startProtectSocketServer = socketServer::start,
                        advertiseProtectPath = { activeProtectSocketPathProvider.set(socketServer.socketPath) },
                        registerNativeProtect = {
                            VpnNativeProtectRegistration.register(service, service.underlyingNetworkBinder)
                        },
                        rollbackProtection = ::cleanupNativeProtect,
                    )
                }.exceptionOrNull()
            if (protectionFailure != null) {
                throw protectionFailure
            }
            ServiceShellDelegate(
                serviceScope = service.serviceScope,
                serviceLabel = "vpn",
                onStart = runtimeCoordinator::start,
                onStartWithId = { action, startId ->
                    if (isServiceRecoveryStartAction(action)) {
                        recoverProfileMutationsAndAwaitUnderlayThenStart(
                            awaitStartupReadiness = awaitStartupReadiness,
                            onStartupNotReady = {
                                runtimeCoordinator.stop(stopSelfStartId = startId)
                            },
                            recoverProfileMutations = recoverProfileMutations,
                            awaitRecoveryUnderlay = awaitRecoveryUnderlay,
                            startRuntime = { runtimeCoordinator.start(stopSelfStartId = startId) },
                        )
                    } else {
                        runtimeCoordinator.start(stopSelfStartId = startId)
                    }
                },
                onStop = { startId, provenance ->
                    serviceStopProvenanceRecorder.record(Mode.VPN, provenance)
                    runtimeCoordinator.stop(startId)
                },
                transportFailoverCommandHandler =
                    TransportFailoverCommandHandler(
                        restart = { requestId, expectedTarget ->
                            runtimeCoordinator.restartAfterTransportFailover(requestId, expectedTarget)
                        },
                        reject = transportFailoverApplyTracker::recordRollbackSafeFailure,
                    ),
                beforeUserStart = beforeUserStart,
                shouldPrepareUserStart = {
                    serviceStateStore.status.value.first != AppStatus.Running
                },
                isStopAllowed = service::isUserStopAllowed,
                onAcceptedStart = runtimeResumeIntentTracker::recordAcceptedStart,
                onAcceptedStop = acceptedUserStopRecorder::record,
                isCompensatingStopCurrent = runtimeResumeIntentTracker::isCurrentIntentStopped,
                onRevoke = {
                    serviceStateStore.emitFailed(
                        sender = Sender.VPN,
                        reason = FailureReason.PermissionLost("VPN"),
                    )
                    cleanup.revokeSession(
                        stopRuntime = runtimeCoordinator::stop,
                        destroyCoordinator = runtimeCoordinator::onDestroy,
                        cleanupSocketProtection = ::cleanupNativeProtect,
                    )
                },
            )
        }.onFailure {
            hardKillSwitchRefreshBroadcastLifecycle.close()
        }.getOrThrow()
    }

    fun destroy() {
        hardKillSwitchRefreshBroadcastLifecycle.close()
        val runtimeCoordinator = coordinator
        val failure =
            runCatching {
                cleanup.destroyRunningSession(
                    stopRuntime = { runtimeCoordinator?.stop() },
                    destroyCoordinator = { runtimeCoordinator?.onDestroy() },
                    cleanupSocketProtection = ::cleanupNativeProtect,
                    timeoutMillis = DESTROY_TIMEOUT_MS,
                )
            }.exceptionOrNull()
        // Cleared regardless of the teardown outcome: this service instance is
        // going away, and after a timed-out stop the coordinator destroy and
        // protection cleanup have already run inside destroyRunningSession.
        clearSessionReferences()
        if (failure != null) {
            serviceStateStore.emitFailed(Sender.VPN, FailureReason.Unexpected(failure))
        }
    }

    private fun clearSessionReferences() {
        protectSocketServer = null
        coordinator = null
        sessionComponent = null
    }

    private companion object {
        const val DESTROY_TIMEOUT_MS = 10_000L
    }

    private fun cleanupNativeProtect() {
        withdrawProtectPath(
            withdrawProtectPath = activeProtectSocketPathProvider::clear,
            cleanupNativeProtect = {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = VpnNativeProtectRegistration::unregister,
                    stopProtectSocketServer = { protectSocketServer?.stop() },
                )
            },
        )
    }

    private fun createSessionEntryPoint(): VpnServiceSessionEntryPoint {
        sessionComponent =
            sessionComponentBuilderProvider
                .get()
                .host(service)
                .vpnService(service)
                .build()
        return EntryPoints.get(checkNotNull(sessionComponent), VpnServiceSessionEntryPoint::class.java)
    }
}

internal suspend fun recoverProfileMutationsAndAwaitUnderlayThenStart(
    awaitStartupReadiness: suspend () -> Boolean = { true },
    onStartupNotReady: suspend () -> Unit = {},
    recoverProfileMutations: suspend () -> Unit,
    awaitRecoveryUnderlay: suspend () -> Unit,
    startRuntime: suspend () -> Unit,
    underlayTimeoutMillis: Long = RecoveryUnderlayTimeoutMs,
) {
    if (!awaitStartupReadiness()) {
        onStartupNotReady()
        return
    }
    recoverProfileMutations()
    withTimeoutOrNull(underlayTimeoutMillis) { awaitRecoveryUnderlay() }
    startRuntime()
}

internal const val RecoveryUnderlayTimeoutMs = 3_000L

internal interface HardKillSwitchRefreshLifecycle : AutoCloseable {
    fun start()
}

internal class HardKillSwitchRefreshBroadcastLifecycle(
    private val context: Context,
    private val onRefreshState: () -> Unit,
    private val onRefreshNotification: () -> Unit,
) : HardKillSwitchRefreshLifecycle {
    private var receiver: BroadcastReceiver? = null

    override fun start() {
        if (receiver != null) {
            return
        }
        val candidate =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (
                        intent?.action == hardKillSwitchRefreshBroadcastAction &&
                        intent.`package` == this@HardKillSwitchRefreshBroadcastLifecycle.context.packageName
                    ) {
                        onRefreshState()
                        onRefreshNotification()
                    }
                }
            }
        ContextCompat.registerReceiver(
            context,
            candidate,
            IntentFilter(hardKillSwitchRefreshBroadcastAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = candidate
    }

    override fun close() {
        val registeredReceiver = receiver ?: return
        context.unregisterReceiver(registeredReceiver)
        receiver = null
    }
}

/**
 * Protect-path advertise ordering seam (session start).
 *
 * The protect socket server MUST be listening before the env path is advertised:
 * a relay helper that reads [ActiveProtectSocketPathProvider.current] fails closed
 * if the path is set but no server answers. So [startProtectSocketServer] runs
 * strictly before [advertiseProtectPath].
 *
 * Extracted only to pin this ordering in a unit test ([VpnServiceSessionLifecycleTest]);
 * the runtime effect is identical to the inline `start(); set(path)` it replaced.
 */
internal inline fun advertiseProtectPath(
    startProtectSocketServer: () -> Unit,
    advertiseProtectPath: () -> Unit,
) {
    startProtectSocketServer()
    advertiseProtectPath()
}

internal inline fun establishProtectPath(
    startProtectSocketServer: () -> Unit,
    advertiseProtectPath: () -> Unit,
    registerNativeProtect: () -> Unit,
    rollbackProtection: () -> Unit,
) {
    val failure =
        runCatching {
            advertiseProtectPath(startProtectSocketServer, advertiseProtectPath)
            registerNativeProtect()
        }.exceptionOrNull()
    if (failure != null) {
        runCatching(rollbackProtection)
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }
}

/**
 * Protect-path withdraw ordering seam (session teardown / revoke / destroy).
 *
 * The env path MUST be withdrawn before the protect socket server stops, so a
 * relay helper never reads a stale path pointing at a dead UDS. So
 * [withdrawProtectPath] runs strictly before [cleanupNativeProtect] (which stops
 * the server).
 *
 * Extracted only to pin this ordering in a unit test ([VpnServiceSessionLifecycleTest]);
 * the runtime effect is identical to the inline `clear(); cleanupNativeProtect(...)`
 * it replaced.
 */
internal inline fun withdrawProtectPath(
    withdrawProtectPath: () -> Unit,
    cleanupNativeProtect: () -> Unit,
) {
    withdrawProtectPath()
    cleanupNativeProtect()
}
