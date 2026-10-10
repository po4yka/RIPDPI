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

/** Metadata is absent for legacy probes that expose only address lists. */
data class DnsProbeAnswer(
    val addresses: List<String> = emptyList(),
    val response: com.poyka.ripdpi.diagnostics.DnsResponseSemantics? = null,
) {
    internal fun legacyRecords(): List<String> =
        when (response?.outcome) {
            com.poyka.ripdpi.diagnostics.DnsResponseOutcome.NXDOMAIN -> listOf(DnsWireBuilder.NXDOMAIN)
            com.poyka.ripdpi.diagnostics.DnsResponseOutcome.MALFORMED -> listOf(DnsWireBuilder.PARSE_ERR)
            else -> addresses
        }
}

interface SemanticDnsUdpProbe : DnsUdpProbe {
    suspend fun resolveResponse(domain: String): DnsProbeAnswer

    override suspend fun resolveA(domain: String): List<String> = resolveResponse(domain).legacyRecords()
}

interface SemanticDnsAddressProbe : BootstrapFilterableDnsAddressProbe {
    suspend fun resolveResponse(
        domain: String,
        excludedDohHostnames: Set<String> = emptySet(),
    ): DnsProbeAnswer

    override suspend fun resolveA(domain: String): Set<String> = resolveResponse(domain).addresses.toSet()

    override suspend fun resolveA(
        domain: String,
        excludedDohHostnames: Set<String>,
    ): Set<String> = resolveResponse(domain, excludedDohHostnames).addresses.toSet()
}
