package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ManagedTunnel
import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.TunnelUpstream
import com.poyka.ripdpi.data.ActiveDnsSettings

/**
 * Per-start parameters the [XrayManagedTunnel] needs to drive the existing
 * [VpnTunnelRuntime]. The orchestrator's [ManagedTunnel.start] only carries the
 * resolved [TunnelUpstream]; everything else (active DNS, log context, override
 * reason) comes from the live VPN session and is supplied through this provider
 * at start time so the tunnel never holds stale session state across restarts.
 */
internal data class XrayTunnelStartParams(
    val activeDns: ActiveDnsSettings,
    val overrideReason: String?,
    val logContext: RipDpiLogContext?,
    val forceTunnelDns: Boolean,
    val splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy? = null,
)

/**
 * Mutable holder decoupling Hilt construction order from per-start data: the
 * [XrayProviderSessionController] sets the live params just before driving the
 * orchestrator, and [XrayManagedTunnel] reads them inside its (synchronous)
 * [ManagedTunnel.start]. Lifecycle calls are serialized, so no cross-thread
 * visibility race exists; `@Volatile` is belt-and-suspenders.
 */
internal class XrayTunnelStartParamsHolder {
    @Volatile
    var current: XrayTunnelStartParams? = null

    fun require(): XrayTunnelStartParams = checkNotNull(current) { "XrayTunnelStartParams not set before tunnel start" }
}

/**
 * Holds the secret-bearing rendered Xray config for exactly the duration of a
 * single orchestrator start, decoupling the suspend route build from the
 * orchestrator's synchronous `renderedConfigProvider`. The
 * [XrayProviderSessionController] sets it just before `orchestrator.start`, the
 * orchestrator reads it synchronously during start, and the controller clears it
 * immediately after — so the secret never lives on a long-lived object. Never
 * logged, telemetered, or persisted.
 */
internal class XrayRenderedConfigHolder {
    @Volatile
    var current: String? = null

    fun require(): String = checkNotNull(current) { "rendered xray config not set before start" }
}

/**
 * Minimal driver seam over [VpnTunnelRuntime] so [XrayManagedTunnel] is unit
 * testable without constructing the full (native-linking) tunnel runtime.
 * Production binds this to the real [VpnTunnelRuntime] via [fromVpnTunnelRuntime].
 */
internal interface XrayTunnelDriver {
    val isRunning: Boolean

    suspend fun start(
        params: XrayTunnelStartParams,
        endpoint: LocalProxyEndpoint,
    )

    suspend fun quiesce()

    suspend fun stop()

    companion object {
        fun fromVpnTunnelRuntime(runtime: VpnTunnelRuntime): XrayTunnelDriver =
            object : XrayTunnelDriver {
                override val isRunning: Boolean
                    get() = runtime.isRunning

                override suspend fun start(
                    params: XrayTunnelStartParams,
                    endpoint: LocalProxyEndpoint,
                ) {
                    val start = if (runtime.isRunning) runtime::rebuild else runtime::start
                    start(
                        params.activeDns,
                        params.overrideReason,
                        params.logContext,
                        endpoint,
                        params.forceTunnelDns,
                        params.splitStrictDnsPolicy,
                        null,
                    )
                }

                override suspend fun quiesce() {
                    runtime.retainFailClosedBarrier()
                }

                override suspend fun stop() {
                    runtime.stop()
                }
            }
    }
}

/**
 * [ManagedTunnel] adapter that bridges the [XrayProviderOrchestrator]'s tunnel
 * seam onto RIPDPI's existing [VpnTunnelRuntime] (decision 3).
 *
 * Maps [TunnelUpstream.Xray] -> [LocalProxyEndpoint] on the loopback inbound the
 * Xray engine listens on, then drives the tunnel via [XrayTunnelDriver], reusing
 * the native TUN-to-SOCKS data plane verbatim. The orchestrator guarantees Xray
 * is listening before this `start` runs (protect-first + readiness-before-tunnel),
 * so the forwarder never races a not-yet-bound inbound.
 *
 * [TunnelUpstream.Native] is a guard error: the native provider keeps its own
 * composition path in [VpnRuntimeCompositionCoordinator] (BYTE-IDENTICAL); it
 * never flows through this Xray tunnel.
 *
 * Idempotent stop mirrors [VpnTunnelRuntime.stop] (no-op when not running).
 */
internal class XrayManagedTunnel(
    private val driver: XrayTunnelDriver,
    private val startParamsProvider: () -> XrayTunnelStartParams,
) : ManagedTunnel {
    constructor(
        vpnTunnelRuntime: VpnTunnelRuntime,
        startParamsProvider: () -> XrayTunnelStartParams,
    ) : this(XrayTunnelDriver.fromVpnTunnelRuntime(vpnTunnelRuntime), startParamsProvider)

    override val isRunning: Boolean
        get() = driver.isRunning

    override suspend fun start(upstream: TunnelUpstream) {
        val xrayUpstream =
            when (upstream) {
                is TunnelUpstream.Xray -> {
                    upstream
                }

                TunnelUpstream.Native -> {
                    error(
                        "XrayManagedTunnel only bridges the Xray loopback inbound; " +
                            "the native provider uses VpnRuntimeCompositionCoordinator directly",
                    )
                }
            }

        // The Xray inbound binds loopback by construction (TunnelUpstream.Xray
        // enforces host == 127.0.0.1); no auth on the local SOCKS inbound.
        val endpoint =
            LocalProxyEndpoint(
                host = xrayUpstream.host,
                port = xrayUpstream.port,
            )
        driver.start(startParamsProvider(), endpoint)
    }

    override suspend fun quiesce() {
        driver.quiesce()
    }

    override suspend fun stop() {
        driver.stop()
    }
}
