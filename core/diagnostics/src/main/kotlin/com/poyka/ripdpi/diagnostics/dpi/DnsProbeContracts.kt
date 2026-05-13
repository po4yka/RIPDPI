package com.poyka.ripdpi.diagnostics.dpi

fun interface DnsUdpProbe {
    suspend fun resolveA(domain: String): List<String>
}

fun interface DnsAddressProbe {
    suspend fun resolveA(domain: String): Set<String>
}

interface BootstrapFilterableDnsAddressProbe : DnsAddressProbe {
    suspend fun resolveA(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): Set<String>
}
