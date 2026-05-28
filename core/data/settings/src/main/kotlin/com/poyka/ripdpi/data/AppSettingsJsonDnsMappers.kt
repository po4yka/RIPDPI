package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettingsSnapshot.withDnsSnapshot(
    dns: DnsSettingsSection,
    ipv6Enabled: Boolean,
): AppSettingsSnapshot {
    val activeDns =
        activeDnsSettings(
            dnsMode = dns.dnsMode,
            dnsProviderId = dns.dnsProviderId,
            dnsIp = dns.dnsIp,
            encryptedDns =
                EncryptedDnsConfigInput(
                    protocol = dns.encryptedDnsProtocol,
                    host = dns.encryptedDnsHost,
                    port = dns.encryptedDnsPort,
                    tlsServerName = dns.encryptedDnsTlsServerName,
                    bootstrapIps = dns.encryptedDnsBootstrapIps,
                    dohUrl = dns.encryptedDnsDohUrl,
                    dnscryptProviderName = dns.encryptedDnsDnscryptProviderName,
                    dnscryptPublicKey = dns.encryptedDnsDnscryptPublicKey,
                    odohProxyUrl = dns.encryptedDnsOdohProxyUrl,
                    odohProxyOperatorId = dns.encryptedDnsOdohProxyOperatorId,
                    odohTargetHost = dns.encryptedDnsOdohTargetHost,
                    odohTargetPath = dns.encryptedDnsOdohTargetPath,
                    odohTargetOperatorId = dns.encryptedDnsOdohTargetOperatorId,
                    odohConfigSource = dns.encryptedDnsOdohConfigSource,
                    odohConfigsHex = dns.encryptedDnsOdohConfigsHex,
                    odohConfigsRetrievedAtSecs = dns.encryptedDnsOdohConfigsRetrievedAtSecs,
                    odohConfigsTtlSecs = dns.encryptedDnsOdohConfigsTtlSecs,
                ),
        )
    return copy(
        dns =
            AppSettingsDnsSnapshot(
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
                encryptedDnsOdohProxyUrl = activeDns.encryptedDnsOdohProxyUrl,
                encryptedDnsOdohProxyOperatorId = activeDns.encryptedDnsOdohProxyOperatorId,
                encryptedDnsOdohTargetHost = activeDns.encryptedDnsOdohTargetHost,
                encryptedDnsOdohTargetPath = activeDns.encryptedDnsOdohTargetPath,
                encryptedDnsOdohTargetOperatorId = activeDns.encryptedDnsOdohTargetOperatorId,
                encryptedDnsOdohConfigSource = activeDns.encryptedDnsOdohConfigSource,
                encryptedDnsOdohConfigsHex = activeDns.encryptedDnsOdohConfigsHex,
                encryptedDnsOdohConfigsRetrievedAtSecs = activeDns.encryptedDnsOdohConfigsRetrievedAtSecs,
                encryptedDnsOdohConfigsTtlSecs = activeDns.encryptedDnsOdohConfigsTtlSecs,
                ipv6Enabled = ipv6Enabled,
            ),
    )
}

internal fun AppSettingsSnapshot.toActiveDnsSettings(): ActiveDnsSettings =
    activeDnsSettings(
        dnsMode = dns.dnsMode,
        dnsProviderId = dns.dnsProviderId,
        dnsIp = dns.dnsIp,
        encryptedDns =
            EncryptedDnsConfigInput(
                protocol = dns.encryptedDnsProtocol,
                host = dns.encryptedDnsHost,
                port = dns.encryptedDnsPort,
                tlsServerName = dns.encryptedDnsTlsServerName,
                bootstrapIps = dns.encryptedDnsBootstrapIps,
                dohUrl = dns.encryptedDnsDohUrl,
                dnscryptProviderName = dns.encryptedDnsDnscryptProviderName,
                dnscryptPublicKey = dns.encryptedDnsDnscryptPublicKey,
                odohProxyUrl = dns.encryptedDnsOdohProxyUrl,
                odohProxyOperatorId = dns.encryptedDnsOdohProxyOperatorId,
                odohTargetHost = dns.encryptedDnsOdohTargetHost,
                odohTargetPath = dns.encryptedDnsOdohTargetPath,
                odohTargetOperatorId = dns.encryptedDnsOdohTargetOperatorId,
                odohConfigSource = dns.encryptedDnsOdohConfigSource,
                odohConfigsHex = dns.encryptedDnsOdohConfigsHex,
                odohConfigsRetrievedAtSecs = dns.encryptedDnsOdohConfigsRetrievedAtSecs,
                odohConfigsTtlSecs = dns.encryptedDnsOdohConfigsTtlSecs,
            ),
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
        .setEncryptedDnsOdohProxyUrl(activeDns.encryptedDnsOdohProxyUrl)
        .setEncryptedDnsOdohProxyOperatorId(activeDns.encryptedDnsOdohProxyOperatorId)
        .setEncryptedDnsOdohTargetHost(activeDns.encryptedDnsOdohTargetHost)
        .setEncryptedDnsOdohTargetPath(activeDns.encryptedDnsOdohTargetPath)
        .setEncryptedDnsOdohTargetOperatorId(activeDns.encryptedDnsOdohTargetOperatorId)
        .setEncryptedDnsOdohConfigSource(activeDns.encryptedDnsOdohConfigSource)
        .setEncryptedDnsOdohConfigsHex(activeDns.encryptedDnsOdohConfigsHex)
        .setEncryptedDnsOdohConfigsRetrievedAtSecs(activeDns.encryptedDnsOdohConfigsRetrievedAtSecs)
        .setEncryptedDnsOdohConfigsTtlSecs(activeDns.encryptedDnsOdohConfigsTtlSecs)
