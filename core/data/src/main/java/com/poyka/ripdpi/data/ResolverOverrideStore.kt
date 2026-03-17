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
