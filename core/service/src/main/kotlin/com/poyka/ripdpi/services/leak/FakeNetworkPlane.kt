package com.poyka.ripdpi.services.leak

/**
 * JVM fake of the system DNS + IPv6 router used by leak matrix tests.
 *
 * Callers configure which resolver handles a query and whether IPv6 traffic
 * exits through the tunnel or the default network interface. Tests inspect
 * [dnsQueries] and [ipv6Packets] to assert absence of leaks.
 */
internal class FakeNetworkPlane {
    /** All DNS queries recorded during a test run. */
    val dnsQueries: MutableList<FakeDnsQuery> = mutableListOf()

    /** All IPv6 packets recorded during a test run. */
    val ipv6Packets: MutableList<FakeIpv6Packet> = mutableListOf()

    /** When true the plane simulates an IPv6-capable underlying network. */
    var ipv6Capable: Boolean = false

    /** When true all DNS queries are routed via the VPN tunnel (not the ISP resolver). */
    var dnsViaTunnel: Boolean = true

    /**
     * Simulates a DNS query for [domain].
     *
     * Records the query with resolver source determined by [dnsViaTunnel].
     * Returns the fake resolver address used.
     */
    fun query(domain: String): String {
        val resolver = if (dnsViaTunnel) VpnResolver else IspResolver
        dnsQueries += FakeDnsQuery(domain = domain, resolverAddress = resolver, viaDefaultNetwork = !dnsViaTunnel)
        return resolver
    }

    /**
     * Simulates an IPv6 packet from [sourceAddress].
     *
     * Records the packet; whether it exits via the default network is
     * determined by [ipv6Capable] and [dnsViaTunnel] (tunnel off ⇒ direct).
     */
    fun sendIpv6(sourceAddress: String) {
        val viaDefault = ipv6Capable && !dnsViaTunnel
        ipv6Packets += FakeIpv6Packet(sourceAddress = sourceAddress, viaDefaultNetwork = viaDefault)
    }

    /** Returns true if any DNS query leaked to the ISP resolver. */
    fun hasDnsLeak(): Boolean = dnsQueries.any { it.viaDefaultNetwork }

    /** Returns true if any public IPv6 packet exited via the default network. */
    fun hasIpv6Leak(): Boolean = ipv6Packets.any { it.viaDefaultNetwork && !isPrivateIpv6(it.sourceAddress) }

    /** Clears all recorded queries and packets. */
    fun reset() {
        dnsQueries.clear()
        ipv6Packets.clear()
    }

    companion object {
        const val VpnResolver = "198.18.0.53"
        const val IspResolver = "8.8.8.8"

        private fun isPrivateIpv6(address: String): Boolean {
            val lower = address.lowercase()
            return lower.startsWith("fe80") || lower.startsWith("fc") || lower.startsWith("fd")
        }
    }
}

internal data class FakeDnsQuery(
    val domain: String,
    val resolverAddress: String,
    val viaDefaultNetwork: Boolean,
)

internal data class FakeIpv6Packet(
    val sourceAddress: String,
    val viaDefaultNetwork: Boolean,
)
