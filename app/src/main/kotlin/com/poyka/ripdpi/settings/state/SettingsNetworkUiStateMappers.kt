package com.poyka.ripdpi.settings.state

import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.ProxyNetworkUiState
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettings.buildDnsUiState(
    activeDns: ActiveDnsSettings,
    systemPrivateDnsStatus: SystemPrivateDnsStatus = SystemPrivateDnsStatus.UNKNOWN,
): DnsUiState =
    DnsUiState(
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
        dnsSummary = activeDns.summary(),
        systemPrivateDnsStatus = systemPrivateDnsStatus.wireValue,
    )

internal fun AppSettings.buildProxyUiState(): ProxyNetworkUiState =
    ProxyNetworkUiState(
        proxyIp = proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = proxyPort.takeIf { it > 0 } ?: 1080,
        maxConnections = maxConnections.takeIf { it > 0 } ?: 512,
        bufferSize = bufferSize.takeIf { it > 0 } ?: 16_384,
        noDomain = noDomain,
        tcpFastOpen = tcpFastOpen,
        mixedInboundEnabled = mixedInboundEnabled,
    )
