package com.poyka.ripdpi.data.xray

/**
 * Enumerates the first supported VPN provider kinds.
 *
 * - [Native] — the original RIPDPI native Rust/WireGuard provider managed by
 *   `:core:service` directly.
 * - [Xray] — the embedded Xray-core provider routed through a local inbound;
 *   managed by the Xray adapter module generated from `:core:engine`.
 *
 * Both provider kinds share the same [VpnProviderState] machine so that the
 * VPN service layer needs no provider-specific lifecycle branches.
 */
enum class VpnProviderKind {
    Native,
    Xray,
}

/**
 * Shared VPN provider lifecycle states used by both [VpnProviderKind] paths.
 *
 * Valid transitions:
 * ```
 * Stopped -> Starting -> Running -> Stopping -> Stopped
 * Starting -> Stopped  (error / abort)
 * ```
 */
enum class VpnProviderState {
    Stopped,
    Starting,
    Running,
    Stopping,
    ;

    /**
     * Returns `true` if transitioning from `this` state to [next] is permitted.
     *
     * The transition table encodes the linear lifecycle plus the abort edge
     * `Starting -> Stopped` that covers setup failures before [Running] is reached.
     */
    fun canTransitionTo(next: VpnProviderState): Boolean =
        when (this) {
            Stopped -> next == Starting
            Starting -> next == Running || next == Stopped
            Running -> next == Stopping
            Stopping -> next == Stopped
        }
}

/**
 * Tunnel topology decision for the Xray provider.
 *
 * ### TunToLocalInbound (preferred)
 * The Android VpnService TUN fd is owned by the existing native runner.
 * IP packets are read from the fd, forwarded to a local Xray SOCKS/HTTP
 * inbound (127.0.0.1:port), processed by Xray-core, and then sent out
 * through a protected socket.
 *
 * **Tradeoffs:**
 * - Pro: no direct Xray API surface for fd hand-off; survives libXray ABI churn.
 * - Pro: socket protection logic stays in one place (`:core:service`).
 * - Con: one extra loopback copy per packet compared to direct fd hand-off.
 * - Con: requires a running local inbound listener on a fixed loopback port.
 *
 * ### LibXraySetTunFd (alternative)
 * The TUN fd is passed directly to libXray via `libXray.SetTunFd(fd)`.
 * Xray-core then owns the packet loop internally.
 *
 * **Tradeoffs:**
 * - Pro: zero loopback copy; Xray does native TUN I/O.
 * - Con: tightly coupled to libXray Go-bridge ABI which changes across Xray versions.
 * - Con: socket protection must be re-registered whenever Xray reopens sockets;
 *   requires a `ProtectCallback` bridge into Android VpnService.
 * - Con: DNS traffic emitted by Xray bypasses the service DNS-loop guard unless
 *   explicitly excluded via a bypass rule in the Xray routing config.
 */
sealed class XrayTunnelTopology {
    /** Forward TUN packets to a local Xray inbound over loopback (preferred). */
    data object TunToLocalInbound : XrayTunnelTopology()

    /**
     * Hand the TUN fd directly to libXray via `libXray.SetTunFd`.
     * Use only when loopback overhead is measurably unacceptable and the team
     * accepts the ABI coupling and DNS-loop risk.
     */
    data object LibXraySetTunFd : XrayTunnelTopology()
}

/**
 * Runtime configuration snapshot for the Xray provider.
 *
 * Immutable; produced by the Xray adapter and consumed by `:core:service`
 * to parameterise the VPN session without reaching into libXray internals.
 *
 * @param tunnelTopology Chosen tunnel topology. Defaults to [XrayTunnelTopology.TunToLocalInbound].
 * @param localInboundPort Loopback port used by [XrayTunnelTopology.TunToLocalInbound].
 *   Ignored when topology is [XrayTunnelTopology.LibXraySetTunFd].
 */
data class XrayProviderConfig(
    val tunnelTopology: XrayTunnelTopology = XrayTunnelTopology.TunToLocalInbound,
    val localInboundPort: Int = 10808,
)
