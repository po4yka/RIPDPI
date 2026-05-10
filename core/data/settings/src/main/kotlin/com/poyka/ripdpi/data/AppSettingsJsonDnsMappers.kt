package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettingsSnapshot.withDnsSnapshot(settings: AppSettings): AppSettingsSnapshot {
    val activeDns = settings.activeDnsSettings()
    return copy(
        dnsIp = activeDns.dnsIp,
        dnsMode = activeDns.mode,
        dnsProviderId = activeDns.providerId,
        encryptedDnsProtocol = activeDns.encryptedDnsProtocol,
        encryptedDnsHost = activeDns.encryptedDnsHost,
        encryptedDnsPort = activeDns.encryptedDnsPort,
        encryptedDnsTlsServerName = activeDns.encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = activeDns.encryptedDnsBootstrapIps,
        encryptedDnsDohUrl = activeDns.encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = activeDns.encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = activeDns.encryptedDnsDnscryptPublicKey,
        ipv6Enabled = settings.ipv6Enable,
    )
}

internal fun AppSettingsSnapshot.toActiveDnsSettings(): ActiveDnsSettings =
    activeDnsSettings(
        dnsMode = dnsMode,
        dnsProviderId = dnsProviderId,
        dnsIp = dnsIp,
        encryptedDnsProtocol = encryptedDnsProtocol,
        encryptedDnsHost = encryptedDnsHost,
        encryptedDnsPort = encryptedDnsPort,
        encryptedDnsTlsServerName = encryptedDnsTlsServerName,
        encryptedDnsBootstrapIps = encryptedDnsBootstrapIps,
        encryptedDnsDohUrl = encryptedDnsDohUrl,
        encryptedDnsDnscryptProviderName = encryptedDnsDnscryptProviderName,
        encryptedDnsDnscryptPublicKey = encryptedDnsDnscryptPublicKey,
    )

internal fun AppSettings.Builder.applyDnsSnapshot(activeDns: ActiveDnsSettings): AppSettings.Builder =
    setDnsIp(activeDns.dnsIp)
        .setDnsMode(activeDns.mode)
        .setDnsProviderId(activeDns.providerId)
        .setEncryptedDnsProtocol(activeDns.encryptedDnsProtocol)
        .setEncryptedDnsHost(activeDns.encryptedDnsHost)
        .setEncryptedDnsPort(activeDns.encryptedDnsPort)
        .setEncryptedDnsTlsServerName(activeDns.encryptedDnsTlsServerName)
        .clearEncryptedDnsBootstrapIps()
        .addAllEncryptedDnsBootstrapIps(activeDns.encryptedDnsBootstrapIps)
        .setEncryptedDnsDohUrl(activeDns.encryptedDnsDohUrl)
        .setEncryptedDnsDnscryptProviderName(activeDns.encryptedDnsDnscryptProviderName)
        .setEncryptedDnsDnscryptPublicKey(activeDns.encryptedDnsDnscryptPublicKey)
