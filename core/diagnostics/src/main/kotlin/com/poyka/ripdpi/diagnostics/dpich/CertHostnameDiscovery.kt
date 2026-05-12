package com.poyka.ripdpi.diagnostics.dpich

data class CertHostnameDiscovery(
    val ip: String,
    val port: Int,
    val commonName: String?,
    val subjectAltNames: List<String>,
    val error: String? = null,
) {
    val hostnames: List<String> =
        (subjectAltNames + listOfNotNull(commonName))
            .distinctBy { hostname -> hostname.lowercase() }
}
