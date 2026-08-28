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

/**
 * Tunnel seam the orchestrator drives, decoupled from the native tun2socks
 * binding so the handover logic is unit-testable offline.
 *
 * In production this is implemented in `:core:service` over `Tun2SocksTunnel`:
 * [start] maps [TunnelUpstream] onto the service's internal `LocalProxyEndpoint`
 * + `Tun2SocksConfig` and adopts the VPN TUN fd; [stop] tears the worker down.
 * The orchestrator NEVER touches the TUN fd directly — fd ownership stays with
 * the service per `docs/architecture/JNI_CONTRACT.md` §9.
 *
 * Lifecycle calls are serialized by the orchestrator; implementations need not
 * be internally synchronized.
 */
interface ManagedTunnel {
    /** Start (or restart) the TUN-to-SOCKS worker pointed at [upstream]. */
    suspend fun start(upstream: TunnelUpstream)

    /** Stop forwarding while retaining the installed TUN as a traffic barrier. */
    suspend fun quiesce()

    /** Stop the worker. Idempotent: a stop with no running worker is a no-op. */
    suspend fun stop()

    /** Whether a worker is currently running. */
    val isRunning: Boolean
}

/**
 * What changed about the local inbound / provider route, triggering a handover.
 *
 * Any non-trivial change here requires restarting BOTH Xray and the tunnel,
 * because the tunnel's upstream endpoint is derived from the Xray inbound: if
 * the inbound moves (port change) or the provider route changes (kind change),
 * the two must be restarted together to avoid a half-migrated session where the
 * tunnel points at a dead inbound.
 */
data class ProviderRoute(
    /** Active provider kind. */
    val kind: VpnProviderKind,
    /** Xray provider config (loopback inbound port + topology). */
    val xrayConfig: XrayProviderConfig = XrayProviderConfig(),
)

/**
 * Outcome of a [XrayProviderOrchestrator.start] or
 * [XrayProviderOrchestrator.handover].
 */
sealed interface HandoffOutcome {
    /** Tunnel is running against the resolved upstream; Xray (if any) is Running. */
    data class Running(
        val upstream: TunnelUpstream,
    ) : HandoffOutcome

    /** Everything is stopped (clean teardown). */
    data object Stopped : HandoffOutcome

    /** Operation failed. The route remains owned if cleanup is incomplete. */
    data class Failed(
        val reason: String,
    ) : HandoffOutcome
}

/**
 * Pure-Kotlin orchestration that bridges VPN TUN traffic through a protected
 * Xray local inbound, with handover-driven dual restart.
 *
 * Responsibilities:
 * 1. **Provider selection** — resolve the tunnel upstream from the active
 *    [ProviderRoute] via [XrayTunnelHandoff]. Native keeps the RIPDPI path; Xray
 *    points the tunnel at the loopback inbound.
 * 2. **Protect-first ordering** — for the Xray path, start the [RipDpiXrayRuntime]
 *    (which registers the protect controller BEFORE libXray opens any outbound
 *    socket) and confirm readiness BEFORE the tunnel is pointed at the inbound.
 *    Starting the tunnel first would race packets at a not-yet-listening port.
 * 3. **Handover restart** — when the [ProviderRoute] changes (inbound port or
 *    provider kind), restart BOTH Xray and the tunnel in the correct order.
 *
 * The orchestrator owns the start/stop ORDER; it delegates the native concerns
 * to [RipDpiXrayRuntime] (Xray lifecycle + protect) and [ManagedTunnel] (TUN
 * worker). Both are injected, so this class is fully testable with the
 * [com.poyka.ripdpi.core.testing.FakeXrayNativeBridge] and a fake tunnel.
 *
 * Not thread-safe: the caller serializes lifecycle calls on one coroutine,
 * matching [RipDpiXrayRuntime].
 *
 * @param xrayRuntimeFactory builds a fresh [RipDpiXrayRuntime] per Xray start.
 *   A new runtime per start keeps the [VpnProviderState] machine linear across
 *   restarts (each session starts from [VpnProviderState.Stopped]).
 * @param tunnel the TUN-to-SOCKS worker seam.
 * @param protectController protect seam wired into the VPN service (task 3).
 * @param renderedConfigProvider supplies the rendered Xray JSON for a given
 *   route at start time. Held only for the duration of the start call (secrets
 *   are not retained on the orchestrator).
 */
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

    /** The route currently driving the session, or null when stopped. */
    val currentRoute: ProviderRoute?
        get() = activeRoute

    /** Provider state of the active Xray runtime, or Stopped when none/native. */
    val xrayState: VpnProviderState
        get() = activeXray?.providerState ?: VpnProviderState.Stopped

    fun observe(): XrayRuntimeStatus = activeXray?.observe() ?: XrayRuntimeStatus()

    val generation: Long?
        get() = activeXray?.generation

    /**
     * Start the session for [route]. Brings up Xray first (protect-first +
     * readiness) when the route selects Xray, then points the tunnel at the
     * resolved upstream.
     *
     * On any failure, tears down both halves and returns [HandoffOutcome.Failed]
     * so the caller never observes a half-started session.
     *
     * @throws IllegalStateException if a session is already active (call
     *   [handover] or [stop] first).
     */
    suspend fun start(route: ProviderRoute): HandoffOutcome {
        check(activeRoute == null) { "session already active; use handover() or stop()" }
        return bringUp(route)
    }

    /**
     * Apply a route change. If [newRoute] is equivalent to the active route
     * (same kind + same Xray inbound port + topology), this is a no-op and the
     * existing session is preserved. Otherwise BOTH Xray and the tunnel are
     * restarted: the old session is fully stopped, then the new one is brought
     * up. Restarting only one half would leave the tunnel pointing at a dead
     * inbound (or vice versa).
     *
     * If no session is active, this behaves like [start].
     */
    suspend fun handover(newRoute: ProviderRoute): HandoffOutcome {
        val current = activeRoute
        return when {
            current == null -> {
                bringUp(newRoute)
            }

            // No material change to the local inbound / provider route: keep the
            // existing session, report the current upstream.
            !requiresRestart(current, newRoute) &&
                (current.kind != VpnProviderKind.Xray || xrayState == VpnProviderState.Running) -> {
                HandoffOutcome.Running(
                    XrayTunnelHandoff.resolveUpstream(current.kind, current.xrayConfig),
                )
            }

            // DUAL RESTART: tear the whole session down, then bring the new one up.
            else -> {
                val stopped = tearDown(releaseTunnel = false)
                if (stopped is HandoffOutcome.Failed) stopped else bringUp(newRoute)
            }
        }
    }

    /** Stop the whole session; incomplete cleanup retains the route and TUN barrier. */
    suspend fun stop(): HandoffOutcome = tearDown()

    /** Release Xray while retaining the installed TUN for a provider replacement. */
    suspend fun releaseProviderForNativeReplacement() {
        check(tearDown(releaseTunnel = false) !is HandoffOutcome.Failed) {
            "Xray cleanup incomplete during provider replacement"
        }
    }

    fun revokeProtection() {
        activeXray?.revokeProtection()
    }

    /** Terminal service destruction also denies callbacks from a lease acquired after this call. */
    fun closeServiceOwner() {
        serviceProtection.set(null)
        revokeProtection()
    }

    /**
     * Whether a route change between [from] and [to] forces a dual restart.
     *
     * Restart is required when the provider kind changes, or (for the Xray
     * path) when the local inbound port or tunnel topology changes — anything
     * that moves the endpoint the tunnel is bridged to.
     */
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
                from.xrayConfig.localInboundPort != to.xrayConfig.localInboundPort ||
                    from.xrayConfig.tunnelTopology != to.xrayConfig.tunnelTopology
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUp(route: ProviderRoute): HandoffOutcome {
        val upstream =
            try {
                XrayTunnelHandoff.resolveUpstream(route.kind, route.xrayConfig)
            } catch (e: IllegalArgumentException) {
                // Unsupported topology (e.g. SetTunFd) — surface, change nothing.
                return HandoffOutcome.Failed(e.message ?: "unresolvable upstream")
            }

        return when (route.kind) {
            VpnProviderKind.Native -> bringUpNative(route, upstream)
            VpnProviderKind.Xray -> bringUpXray(route, upstream)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUpNative(
        route: ProviderRoute,
        upstream: TunnelUpstream,
    ): HandoffOutcome =
        try {
            tunnel.start(upstream)
            activeRoute = route
            HandoffOutcome.Running(upstream)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { runCatching { tunnel.stop() } }
            throw cancellation
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { tunnel.stop() } }
            currentCoroutineContext().ensureActive()
            HandoffOutcome.Failed("native tunnel start failed: ${e.message}")
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUpXray(
        route: ProviderRoute,
        upstream: TunnelUpstream,
    ): HandoffOutcome {
        val xray = xrayRuntimeFactory(route.xrayConfig)
        // Own cleanup before the first native side effect, including partial startup.
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
            withContext(NonCancellable) { tearDown() }
            throw cancellation
        } catch (_: Exception) {
            withContext(NonCancellable) { tearDown() }
            currentCoroutineContext().ensureActive()
            HandoffOutcome.Failed("Xray provider startup failed")
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
