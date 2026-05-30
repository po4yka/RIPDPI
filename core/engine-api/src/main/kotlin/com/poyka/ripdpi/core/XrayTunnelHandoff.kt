package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.data.xray.XrayTunnelTopology

/**
 * Pure-Kotlin handoff resolver that decides which local upstream endpoint the
 * existing TUN-to-SOCKS tunnel should target, given the active
 * [VpnProviderKind].
 *
 * This is the single decision point that lets VPN startup "select Xray as the
 * tunnel's upstream local endpoint". It produces a [TunnelUpstream] that the
 * service layer maps onto its internal `LocalProxyEndpoint` /
 * `Tun2SocksConfig`. Keeping the decision here — free of Android / native /
 * gomobile linkage — makes provider selection unit-testable offline.
 *
 * ### Default-native invariant
 * [VpnProviderKind.Native] returns [TunnelUpstream.Native], which carries no
 * Xray endpoint and tells the caller to keep the original RIPDPI-native
 * tun2socks upstream. Xray is opt-in; nothing about this resolver changes the
 * native path's behaviour.
 *
 * ### DNS-loop ownership (one source of truth)
 * The tunnel — NOT Xray — owns DNS interception in the [XrayTunnelTopology.TunToLocalInbound]
 * topology. Reasoning:
 *
 * - The TUN tunnel already terminates DNS locally (its mapdns / encrypted-DNS
 *   machinery) and forwards only the resolved upstream connections into the
 *   SOCKS inbound. If we ALSO let Xray resolve DNS through its own outbound,
 *   the query would be emitted by an Xray outbound socket. That socket is
 *   protected (see [XrayProtectController]) so it does not loop into the TUN
 *   fd — but it would create a SECOND, uncoordinated DNS path with different
 *   caching, leak, and strategy behaviour. That is the "split model" the task
 *   explicitly warns against.
 * - Therefore [TunnelUpstream.Xray.dnsOwner] is fixed to [DnsLoopOwner.Tunnel]:
 *   DNS is resolved by the RIPDPI tunnel and the resolved TCP/UDP flows are
 *   handed to the Xray SOCKS inbound as plain connect targets. Xray's own DNS
 *   outbound is intentionally NOT used for the tunnelled path.
 * - The protect-controller seam still guarantees that whatever sockets Xray
 *   DOES open (the relay outbound to the server) are protected before connect,
 *   so provider traffic never re-enters the TUN. DNS-loop avoidance is thus a
 *   product of two cooperating but non-overlapping owners: the tunnel owns
 *   *resolution*, the protect seam owns *socket routing*.
 *
 * @see XrayProviderOrchestrator for the lifecycle that consumes this.
 */
object XrayTunnelHandoff {
    /**
     * Resolve the tunnel upstream for [providerKind].
     *
     * @param providerKind active provider. [VpnProviderKind.Native] keeps the
     *   RIPDPI-native path; [VpnProviderKind.Xray] points the tunnel at the
     *   Xray local inbound described by [xrayConfig].
     * @param xrayConfig Xray provider config (loopback port + topology). Only
     *   consulted when [providerKind] is [VpnProviderKind.Xray].
     * @throws IllegalArgumentException if [providerKind] is [VpnProviderKind.Xray]
     *   with the unimplemented [XrayTunnelTopology.LibXraySetTunFd] topology —
     *   this resolver only bridges the loopback ([XrayTunnelTopology.TunToLocalInbound])
     *   topology. SetTunFd is a declared-but-unimplemented follow-up.
     */
    fun resolveUpstream(
        providerKind: VpnProviderKind,
        xrayConfig: XrayProviderConfig = XrayProviderConfig(),
    ): TunnelUpstream =
        when (providerKind) {
            VpnProviderKind.Native -> TunnelUpstream.Native
            VpnProviderKind.Xray -> resolveXrayUpstream(xrayConfig)
        }

    private fun resolveXrayUpstream(xrayConfig: XrayProviderConfig): TunnelUpstream =
        when (xrayConfig.tunnelTopology) {
            XrayTunnelTopology.TunToLocalInbound -> {
                TunnelUpstream.Xray(
                    host = LoopbackHost,
                    port = xrayConfig.localInboundPort,
                    dnsOwner = DnsLoopOwner.Tunnel,
                )
            }

            XrayTunnelTopology.LibXraySetTunFd -> {
                throw IllegalArgumentException(
                    "XrayTunnelTopology.LibXraySetTunFd is a declared-but-unimplemented " +
                        "follow-up; XrayTunnelHandoff only bridges the TunToLocalInbound topology",
                )
            }
        }

    /** Loopback host the Xray local inbound binds to. Never a routable address. */
    const val LoopbackHost: String = "127.0.0.1"
}

/**
 * Where the tunnel should send its upstream (post-DNS-resolution) connections.
 *
 * Mapped by the service layer onto its internal `LocalProxyEndpoint`; this
 * pure-Kotlin shape exists so the handoff decision is testable without the
 * service module (which links native code).
 */
sealed interface TunnelUpstream {
    /** Keep the RIPDPI-native tun2socks upstream. Carries no Xray endpoint. */
    data object Native : TunnelUpstream

    /**
     * Point the tunnel at the Xray local SOCKS/HTTP inbound on loopback.
     *
     * @property host loopback host (always `127.0.0.1`).
     * @property port the Xray local inbound port (from [XrayProviderConfig.localInboundPort]).
     * @property dnsOwner who resolves DNS for the tunnelled path; fixed to
     *   [DnsLoopOwner.Tunnel] — see [XrayTunnelHandoff] DNS-loop ownership note.
     */
    data class Xray(
        val host: String,
        val port: Int,
        val dnsOwner: DnsLoopOwner,
    ) : TunnelUpstream {
        init {
            require(host == XrayTunnelHandoff.LoopbackHost) {
                "Xray inbound must bind loopback; refusing non-loopback host '$host'"
            }
            require(port in MinPort..MaxPort) {
                "Xray inbound port out of range: $port"
            }
            require(dnsOwner == DnsLoopOwner.Tunnel) {
                "DNS-loop ownership must be the RIPDPI tunnel; Xray DNS outbound is not used " +
                    "for the tunnelled path (single source of truth)"
            }
        }

        private companion object {
            const val MinPort = 1
            const val MaxPort = 65_535
        }
    }
}

/**
 * Single source of truth for who resolves DNS for the tunnelled path.
 *
 * Only [Tunnel] is valid in the [XrayTunnelTopology.TunToLocalInbound] bridge.
 * [XrayDns] is reserved for the future [XrayTunnelTopology.LibXraySetTunFd]
 * topology, where Xray owns the packet loop and must own DNS too; it is NOT
 * selectable today and exists only to make the "split model" the task warns
 * against explicit and rejectable.
 */
enum class DnsLoopOwner {
    /** The RIPDPI tunnel resolves DNS; Xray sees only resolved connect targets. */
    Tunnel,

    /** Reserved for SetTunFd topology: Xray owns DNS. Not selectable today. */
    XrayDns,
}
