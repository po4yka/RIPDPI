package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.xray.VpnProviderState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-owned native lane. Bind once per process, independently of any service scope.
 * Cancelling an await never cancels a native operation or releases its session lease.
 * Admission permits one start, one observation and one stop per lease, even if Go hangs.
 */
class XrayRuntimeOwner(
    private val bridge: XrayNativeBridge,
    dispatcher: CoroutineDispatcher = NativeDispatcher,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var active: XrayRuntimeLease? = null
    private var nextGeneration = 0L
    private var endpointResolution: Deferred<List<String>>? = null

    val isOccupied: Boolean
        get() = synchronized(lock) { active != null || endpointResolution != null }

    /** One detached relay bootstrap lookup. Cancellation cannot enqueue unbounded blocking DNS jobs. */
    fun resolveEndpoint(resolve: () -> List<String>): Deferred<List<String>> {
        val operation =
            synchronized(lock) {
                check(active == null && endpointResolution == null) { "Xray native lane is still owned" }
                scope
                    .async(start = CoroutineStart.LAZY) {
                        try {
                            resolve()
                        } finally {
                            synchronized(lock) { endpointResolution = null }
                        }
                    }.also { endpointResolution = it }
            }
        operation.start()
        return operation
    }

    internal fun acquire(protectController: XrayProtectController): XrayRuntimeLease =
        synchronized(lock) {
            if (active != null || endpointResolution != null) {
                throw XrayRuntimeException.IllegalLifecycle("Xray native cleanup is still owned")
            }
            XrayRuntimeLease(++nextGeneration, protectController).also { active = it }
        }

    internal fun start(
        lease: XrayRuntimeLease,
        renderedConfig: String,
    ): Deferred<Int> {
        val operation =
            synchronized(lock) {
                check(!lease.startSubmitted) { "Xray start was already submitted" }
                lease.startSubmitted = true
                scope
                    .async(start = CoroutineStart.LAZY) {
                        requireStarting(lease)
                        val code =
                            runCatching {
                                bridge.registerProtect(lease.protect)
                                bridge.start(renderedConfig)
                            }.getOrElse { throw XrayRuntimeException.StartupFailed("Xray native start failed", -1) }
                        val version = runCatching { bridge.version() }.getOrDefault("unknown")
                        synchronized(lock) {
                            if (active === lease && !lease.stopRequested) {
                                lease.status = lease.status.copy(version = version, alive = code == 0)
                            }
                        }
                        code
                    }
            }
        operation.start()
        return operation
    }

    internal fun observe(lease: XrayRuntimeLease): Deferred<XrayRuntimeStatus> {
        val operation =
            synchronized(lock) {
                if (active !== lease || lease.stopRequested) return CompletableDeferred(lease.status)
                lease.observation?.takeUnless { it.isCompleted } ?: scope
                    .async(start = CoroutineStart.LAZY) {
                        if (!canObserve(lease)) return@async lease.status
                        val alive = runCatching { bridge.isAlive() }.getOrDefault(false)
                        val ready = alive && runCatching { bridge.listenerReady() }.getOrDefault(false)
                        synchronized(lock) {
                            if (active === lease && !lease.stopRequested) {
                                lease.status =
                                    lease.status.copy(
                                        alive = alive,
                                        listenerReady = ready,
                                        failed =
                                            lease.status.failed ||
                                                (lease.status.state == VpnProviderState.Running && (!alive || !ready)),
                                        state = if (ready) VpnProviderState.Running else lease.status.state,
                                    )
                            }
                            lease.status
                        }
                    }.also { lease.observation = it }
            }
        operation.start()
        return operation
    }

    internal fun stop(lease: XrayRuntimeLease): Deferred<StopCause> {
        val operation =
            synchronized(lock) {
                if (active !== lease) return CompletableDeferred(StopCause.AlreadyStopped)
                lease.revokeProtection()
                lease.stopRequested = true
                lease.status = lease.status.copy(state = VpnProviderState.Stopping, listenerReady = false)
                lease.stop?.takeUnless { it.isCompleted } ?: scope
                    .async(start = CoroutineStart.LAZY) {
                        stopNative(lease)
                    }.also { lease.stop = it }
            }
        operation.start()
        return operation
    }

    private fun stopNative(lease: XrayRuntimeLease): StopCause {
        if (synchronized(lock) { active !== lease }) return StopCause.AlreadyStopped
        val result = runCatching { bridge.stop() }
        return if (result.isFailure) {
            StopCause.Failed("Xray native cleanup failed")
        } else {
            synchronized(lock) {
                if (active === lease) {
                    lease.status =
                        lease.status.copy(
                            state = VpnProviderState.Stopped,
                            alive = false,
                            listenerReady = false,
                        )
                    active = null
                }
            }
            // async completes only after publication/release, never while holding lock.
            StopCause.Clean
        }
    }

    private fun requireStarting(lease: XrayRuntimeLease) {
        synchronized(lock) {
            if (active !== lease || lease.stopRequested) {
                throw XrayRuntimeException.IllegalLifecycle("Xray start was cancelled before native execution")
            }
        }
    }

    private fun canObserve(lease: XrayRuntimeLease): Boolean =
        synchronized(lock) { active === lease && !lease.stopRequested }

    private companion object {
        val NativeDispatcher by lazy {
            Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "ripdpi-xray").apply { isDaemon = true }
                }.asCoroutineDispatcher()
        }
    }
}

internal class XrayRuntimeLease(
    val generation: Long,
    protectController: XrayProtectController,
) {
    private val protection = AtomicReference<XrayProtectController?>(protectController)
    val protect =
        XrayProtectController { fd ->
            val controller = protection.get()
            fd >= 0 && controller != null &&
                runCatching { controller.protect(fd) }.getOrDefault(false) && protection.get() === controller
        }

    fun revokeProtection() {
        protection.set(null)
    }

    @Volatile
    var status = XrayRuntimeStatus(state = VpnProviderState.Starting)
    var stopRequested = false
    var startSubmitted = false
    var observation: Deferred<XrayRuntimeStatus>? = null
    var stop: Deferred<StopCause>? = null
}

data class XrayRuntimeStatus(
    val state: VpnProviderState = VpnProviderState.Stopped,
    val version: String = "unknown",
    val alive: Boolean = false,
    val listenerReady: Boolean = false,
    val failed: Boolean = false,
)
