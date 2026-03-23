package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.StateFlow

data class TemporaryResolverOverride(
    val resolverId: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val bootstrapIps: List<String>,
    val dohUrl: String,
    val dnscryptProviderName: String,
    val dnscryptPublicKey: String,
    val reason: String,
    val appliedAt: Long,
) {
    fun toActiveDnsSettings(): ActiveDnsSettings =
        activeDnsSettings(
            dnsMode = DnsModeEncrypted,
            dnsProviderId = resolverId,
            dnsIp = bootstrapIps.firstOrNull().orEmpty(),
            dnsDohUrl = dohUrl,
            dnsDohBootstrapIps = bootstrapIps,
            encryptedDnsProtocol = protocol,
            encryptedDnsHost = host,
            encryptedDnsPort = port,
            encryptedDnsTlsServerName = tlsServerName,
            encryptedDnsBootstrapIps = bootstrapIps,
            encryptedDnsDohUrl = dohUrl,
            encryptedDnsDnscryptProviderName = dnscryptProviderName,
            encryptedDnsDnscryptPublicKey = dnscryptPublicKey,
        )

    fun matches(settings: ActiveDnsSettings): Boolean {
        val active = toActiveDnsSettings()
        return active == settings
    }
}

interface ResolverOverrideStore {
    val override: StateFlow<TemporaryResolverOverride?>

    fun setTemporaryOverride(override: TemporaryResolverOverride)

    fun clear()
}

fun EncryptedDnsPathCandidate.toTemporaryResolverOverride(
    reason: String,
    appliedAt: Long = System.currentTimeMillis(),
): TemporaryResolverOverride =
    TemporaryResolverOverride(
        resolverId = resolverId.ifBlank { DnsProviderCustom },
        protocol = protocol,
        host = host,
        port = port,
        tlsServerName = tlsServerName,
        bootstrapIps = normalizeDnsBootstrapIps(bootstrapIps),
        dohUrl = dohUrl,
        dnscryptProviderName = dnscryptProviderName,
        dnscryptPublicKey = dnscryptPublicKey,
        reason = reason,
        appliedAt = appliedAt,
    )
