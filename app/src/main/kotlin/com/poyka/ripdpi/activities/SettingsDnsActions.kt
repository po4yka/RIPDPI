package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.normalizeDnsBootstrapIps

internal class SettingsDnsActions(
    private val mutations: SettingsMutationRunner,
) {
    fun selectBuiltInDnsProvider(providerId: String) {
        val resolver = dnsProviderById(providerId) ?: return
        mutations.updateSetting(
            key = "dnsProviderId",
            value = providerId,
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(resolver.providerId)
            setDnsIp(resolver.primaryIp)
            setEncryptedDnsProtocol(resolver.protocol)
            setEncryptedDnsHost(resolver.host)
            setEncryptedDnsPort(resolver.port)
            setEncryptedDnsTlsServerName(resolver.tlsServerName)
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(resolver.bootstrapIps)
            setEncryptedDnsDohUrl(resolver.dohUrl.orEmpty())
            setEncryptedDnsDnscryptProviderName(resolver.dnscryptProviderName.orEmpty())
            setEncryptedDnsDnscryptPublicKey(resolver.dnscryptPublicKey.orEmpty())
        }
    }

    fun setEncryptedDnsProtocol(protocol: String) {
        mutations.updateSetting(
            key = "encryptedDnsProtocol",
            value = protocol,
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setEncryptedDnsProtocol(protocol)
            if (protocol != EncryptedDnsProtocolDoh) {
                setEncryptedDnsDohUrl("")
            }
            if (protocol != EncryptedDnsProtocolDnsCrypt) {
                setEncryptedDnsDnscryptProviderName("")
                setEncryptedDnsDnscryptPublicKey("")
            }
            if (protocol != EncryptedDnsProtocolDot && protocol != EncryptedDnsProtocolDoh) {
                setEncryptedDnsTlsServerName("")
            }
        }
    }

    fun setPlainDnsServer(dnsIp: String) {
        mutations.updateSetting(
            key = "dnsIp",
            value = dnsIp,
        ) {
            setDnsMode(DnsModePlainUdp)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(dnsIp)
            setEncryptedDnsProtocol("")
            setEncryptedDnsHost("")
            setEncryptedDnsPort(0)
            setEncryptedDnsTlsServerName("")
            clearEncryptedDnsBootstrapIps()
            setEncryptedDnsDohUrl("")
            setEncryptedDnsDnscryptProviderName("")
            setEncryptedDnsDnscryptPublicKey("")
        }
    }

    fun setCustomDohResolver(
        dohUrl: String,
        bootstrapIps: List<String>,
    ) {
        val normalizedBootstrapIps = normalizeDnsBootstrapIps(bootstrapIps)
        mutations.updateSetting(
            key = "encryptedDnsDohUrl",
            value = dohUrl,
        ) {
            val host =
                runCatching {
                    java.net
                        .URI(dohUrl.trim())
                        .host
                        .orEmpty()
                }.getOrDefault("")
            val port =
                runCatching {
                    val uri = java.net.URI(dohUrl.trim())
                    if (uri.port > 0) uri.port else 443
                }.getOrDefault(443)
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(normalizedBootstrapIps.firstOrNull().orEmpty())
            setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
            setEncryptedDnsHost(host)
            setEncryptedDnsPort(port)
            setEncryptedDnsTlsServerName(host)
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(normalizedBootstrapIps)
            setEncryptedDnsDohUrl(dohUrl.trim())
            setEncryptedDnsDnscryptProviderName("")
            setEncryptedDnsDnscryptPublicKey("")
        }
    }

    fun setCustomDotResolver(
        host: String,
        port: Int,
        tlsServerName: String,
        bootstrapIps: List<String>,
    ) {
        val normalizedBootstrapIps = normalizeDnsBootstrapIps(bootstrapIps)
        mutations.updateSetting(
            key = "encryptedDnsHost",
            value = host,
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(normalizedBootstrapIps.firstOrNull().orEmpty())
            setEncryptedDnsProtocol(EncryptedDnsProtocolDot)
            setEncryptedDnsHost(host.trim())
            setEncryptedDnsPort(port)
            setEncryptedDnsTlsServerName(tlsServerName.trim())
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(normalizedBootstrapIps)
            setEncryptedDnsDohUrl("")
            setEncryptedDnsDnscryptProviderName("")
            setEncryptedDnsDnscryptPublicKey("")
        }
    }

    fun setCustomDnsCryptResolver(
        host: String,
        port: Int,
        providerName: String,
        publicKey: String,
        bootstrapIps: List<String>,
    ) {
        val normalizedBootstrapIps = normalizeDnsBootstrapIps(bootstrapIps)
        mutations.updateSetting(
            key = "encryptedDnsDnscryptProviderName",
            value = providerName,
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(normalizedBootstrapIps.firstOrNull().orEmpty())
            setEncryptedDnsProtocol(EncryptedDnsProtocolDnsCrypt)
            setEncryptedDnsHost(host.trim())
            setEncryptedDnsPort(port)
            setEncryptedDnsTlsServerName("")
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(normalizedBootstrapIps)
            setEncryptedDnsDohUrl("")
            setEncryptedDnsDnscryptProviderName(providerName.trim())
            setEncryptedDnsDnscryptPublicKey(publicKey.trim())
        }
    }
}
