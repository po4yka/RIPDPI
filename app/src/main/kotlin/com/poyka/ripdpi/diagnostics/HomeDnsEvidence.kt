package com.poyka.ripdpi.diagnostics

internal const val HomeDohEndpoint = "https://1.1.1.1/dns-query"

internal fun characterizeHomeDns(
    systemIps: List<String>,
    canarySystemIps: List<String>,
    dohControlIps: List<String>?,
    dohCanaryIps: List<String>?,
): HomeDnsCharacterization {
    val notes = mutableListOf<String>()
    val resolverClass =
        when {
            dohControlIps == null -> {
                notes += "DoH endpoint $HomeDohEndpoint unreachable"
                HomeDnsResolverClass.DOH_UNREACHABLE
            }

            systemIps.isEmpty() || canarySystemIps.isEmpty() || dohCanaryIps == null -> {
                notes += "DNS comparison is incomplete"
                HomeDnsResolverClass.UNKNOWN
            }

            (systemIps intersect dohControlIps.toSet()).isEmpty() ||
                (canarySystemIps intersect dohCanaryIps.toSet()).isEmpty() -> {
                notes += "System and DoH answers differ; location and CDN routing can cause this"
                HomeDnsResolverClass.UNKNOWN
            }

            else -> {
                HomeDnsResolverClass.SYSTEM_RESOLVER_OK
            }
        }
    return HomeDnsCharacterization(
        resolverClass = resolverClass,
        systemResolver = null,
        dohEndpoint = HomeDohEndpoint,
        poisonedHosts = emptyList(),
        notes = notes,
    )
}
