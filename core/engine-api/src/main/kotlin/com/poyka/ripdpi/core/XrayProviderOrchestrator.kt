package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

interface ManagedTunnel {
    suspend fun start(upstream: TunnelUpstream)

    suspend fun quiesce()

    suspend fun stop()

    val isRunning: Boolean
}

data class ProviderRoute(
    val kind: VpnProviderKind,
    val xrayConfig: XrayProviderConfig = XrayProviderConfig(),
)

sealed interface HandoffOutcome {
    data class Running(
        val upstream: TunnelUpstream,
    ) : HandoffOutcome

    data object Stopped : HandoffOutcome

    data class Failed(
        val reason: String,
    ) : HandoffOutcome
}

class XrayProviderOrchestrator(
    private val xrayRuntimeFactory: (XrayProviderConfig) -> RipDpiXrayRuntime,
    private val tunnel: ManagedTunnel,
    protectController: XrayProtectController,
    private val renderedConfigProvider: (ProviderRoute) -> String,
) {
    private val serviceProtection = AtomicReference<XrayProtectController?>(protectController)
    private val serviceProtect =
        XrayProtectController { fd ->
            val controller = serviceProtection.get()
            controller != null && runCatching { controller.protect(fd) }.getOrDefault(false) &&
                serviceProtection.get() === controller
        }

    @Volatile
    private var activeRoute: ProviderRoute? = null

    @Volatile
    private var activeXray: RipDpiXrayRuntime? = null

    val currentRoute: ProviderRoute?
        get() = activeRoute

    val xrayState: VpnProviderState
        get() = activeXray?.providerState ?: VpnProviderState.Stopped

    fun observe(): XrayRuntimeStatus = activeXray?.observe() ?: XrayRuntimeStatus()

    val generation: Long?
        get() = activeXray?.generation

    suspend fun start(route: ProviderRoute): HandoffOutcome {
        check(activeRoute == null) { "session already active; use handover() or stop()" }
        return bringUp(route)
    }

    suspend fun handover(newRoute: ProviderRoute): HandoffOutcome {
        val current = activeRoute
        return when {
            current == null -> {
                bringUp(newRoute)
            }

            !requiresRestart(current, newRoute) &&
                (current.kind != VpnProviderKind.Xray || xrayState == VpnProviderState.Running) -> {
                HandoffOutcome.Running(
                    XrayTunnelHandoff.resolveUpstream(current.kind, current.xrayConfig),
                )
            }

            else -> {
                replaceRetainingTunnel(newRoute)
            }
        }
    }

    suspend fun replace(newRoute: ProviderRoute): HandoffOutcome =
        if (activeRoute == null) {
            bringUp(newRoute)
        } else {
            replaceRetainingTunnel(newRoute)
        }

    private suspend fun replaceRetainingTunnel(newRoute: ProviderRoute): HandoffOutcome {
        val stopped = tearDown(releaseTunnel = false)
        return if (stopped is HandoffOutcome.Failed) {
            stopped
        } else {
            bringUp(newRoute, retainTunnelBarrierOnFailure = true)
        }
    }

    suspend fun stop(): HandoffOutcome = tearDown()

    suspend fun releaseProviderForNativeReplacement() {
        check(tearDown(releaseTunnel = false) !is HandoffOutcome.Failed) {
            "Xray cleanup incomplete during provider replacement"
        }
    }

    fun revokeProtection() {
        activeXray?.revokeProtection()
    }

    fun closeServiceOwner() {
        serviceProtection.set(null)
        revokeProtection()
    }

    private fun requiresRestart(
        from: ProviderRoute,
        to: ProviderRoute,
    ): Boolean {
        if (from.kind != to.kind) return true
        return when (to.kind) {
            VpnProviderKind.Native -> {
                false
            }

            VpnProviderKind.Xray -> {
                from.xrayConfig.localInboundPort != to.xrayConfig.localInboundPort
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUp(
        route: ProviderRoute,
        retainTunnelBarrierOnFailure: Boolean = false,
    ): HandoffOutcome {
        val upstream =
            try {
                XrayTunnelHandoff.resolveUpstream(route.kind, route.xrayConfig)
            } catch (e: IllegalArgumentException) {
                return HandoffOutcome.Failed(e.message ?: "unresolvable upstream")
            }

        return when (route.kind) {
            VpnProviderKind.Native -> bringUpNative(route, upstream, retainTunnelBarrierOnFailure)
            VpnProviderKind.Xray -> bringUpXray(route, upstream, retainTunnelBarrierOnFailure)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUpNative(
        route: ProviderRoute,
        upstream: TunnelUpstream,
        retainTunnelBarrierOnFailure: Boolean,
    ): HandoffOutcome =
        try {
            tunnel.start(upstream)
            activeRoute = route
            HandoffOutcome.Running(upstream)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { cleanTunnelAfterStartFailure(route, retainTunnelBarrierOnFailure) }
            throw cancellation
        } catch (e: Exception) {
            withContext(NonCancellable) { cleanTunnelAfterStartFailure(route, retainTunnelBarrierOnFailure) }
            currentCoroutineContext().ensureActive()
            HandoffOutcome.Failed("native tunnel start failed: ${e.message}")
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUpXray(
        route: ProviderRoute,
        upstream: TunnelUpstream,
        retainTunnelBarrierOnFailure: Boolean,
    ): HandoffOutcome {
        val xray = xrayRuntimeFactory(route.xrayConfig)
        activeXray = xray
        activeRoute = route
        return try {
            check(serviceProtection.get() != null) { "Xray service owner was destroyed" }
            xray.start(renderedConfigProvider(route), serviceProtect)
            xray.awaitReady()
            currentCoroutineContext().ensureActive()
            check(serviceProtection.get() != null) { "Xray service owner was destroyed" }
            tunnel.start(upstream)
            HandoffOutcome.Running(upstream)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { cleanProviderAfterStartFailure(route, retainTunnelBarrierOnFailure) }
            throw cancellation
        } catch (_: Exception) {
            withContext(NonCancellable) { cleanProviderAfterStartFailure(route, retainTunnelBarrierOnFailure) }
            currentCoroutineContext().ensureActive()
            HandoffOutcome.Failed("Xray provider startup failed")
        }
    }

    private suspend fun cleanProviderAfterStartFailure(
        route: ProviderRoute,
        retainTunnelBarrier: Boolean,
    ) {
        if (retainTunnelBarrier) {
            tearDown(releaseTunnel = false)
            activeRoute = route
        } else {
            tearDown()
        }
    }

    private suspend fun cleanTunnelAfterStartFailure(
        route: ProviderRoute,
        retainTunnelBarrier: Boolean,
    ) {
        if (retainTunnelBarrier) {
            runCatching { tunnel.quiesce() }
            activeRoute = route
        } else {
            runCatching { tunnel.stop() }
            activeRoute = null
        }
    }

    private suspend fun tearDown(releaseTunnel: Boolean = true): HandoffOutcome =
        withContext(NonCancellable) {
            val xray = activeXray
            if (xray != null) {
                val forwardingStopped = runCatching { tunnel.quiesce() }.isSuccess
                val stopCause = xray.stop()
                if (!forwardingStopped || stopCause is StopCause.Failed || stopCause == StopCause.Pending) {
                    return@withContext HandoffOutcome.Failed("Xray cleanup incomplete; TUN barrier retained")
                }
            }
            if (releaseTunnel && runCatching { tunnel.stop() }.isFailure) {
                return@withContext HandoffOutcome.Failed("Tunnel cleanup incomplete")
            }
            activeXray = null
            activeRoute = null
            HandoffOutcome.Stopped
        }
}
