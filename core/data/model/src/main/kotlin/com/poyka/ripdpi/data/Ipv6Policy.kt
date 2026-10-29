package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

/** IPv6 handling policy. */
@Serializable
enum class Ipv6Policy {
    /**
     * Secure default: no IPv6 address, route, DNS, or allowFamily(AF_INET6) is
     * programmed into the VPN tunnel. All IPv6 traffic is suppressed.
     */
    IPV4_ONLY,

    /** Full dual-stack: IPv6 TUN address, ::/0 route, AAAA DNS forwarded through tunnel. */
    ALLOW,

    /** IPv6 is blocked at the application layer but no tunnel artefacts are added. */
    BLOCK,

    /** Prefer IPv6 paths when available; falls back to IPv4. */
    PREFER,

    /** AAAA queries are forwarded to the resolver unchanged. */
    NATIVE,

    /** NAT64/DNS64 synthesis: AAAA is synthesised from A results via a NAT64 prefix. */
    TRANSLATED,
}
