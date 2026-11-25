package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderConfig

object XrayTunnelHandoff {
    fun resolveUpstream(
        providerKind: VpnProviderKind,
        xrayConfig: XrayProviderConfig = XrayProviderConfig(),
    ): TunnelUpstream =
        when (providerKind) {
            VpnProviderKind.Native -> TunnelUpstream.Native
            VpnProviderKind.Xray -> TunnelUpstream.Xray(LoopbackHost, xrayConfig.localInboundPort, DnsLoopOwner.Tunnel)
        }

    const val LoopbackHost: String = "127.0.0.1"
}

sealed interface TunnelUpstream {
    data object Native : TunnelUpstream

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

enum class DnsLoopOwner {
    Tunnel,

    XrayDns,
}
