package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigViewModelTest {
    private val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()

    @Test
    fun `config draft defaults match canonical encrypted dns settings`() {
        val defaultDns = canonicalDefaultEncryptedDnsSettings()
        val draft = ConfigDraft()

        assertEquals(defaultDns.dnsIp, draft.dnsIp)
        assertEquals(defaultDns.summary(), draft.dnsSummary)
    }

    @Test
    fun `default draft marks recommended preset as selected`() {
        val presets = buildConfigPresets(defaultDraft)

        assertTrue(presets.first { it.id == "recommended" }.isSelected)
        assertFalse(presets.first { it.id == "proxy" }.isSelected)
        assertFalse(presets.first { it.id == "custom" }.isSelected)
    }

    @Test
    fun `proxy draft marks proxy preset as selected`() {
        val presets = buildConfigPresets(defaultDraft.copy(mode = Mode.Proxy))

        assertFalse(presets.first { it.id == "recommended" }.isSelected)
        assertTrue(presets.first { it.id == "proxy" }.isSelected)
        assertFalse(presets.first { it.id == "custom" }.isSelected)
    }

    @Test
    fun `invalid draft reports validation errors`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    proxyPort = "0",
                    maxConnections = "0",
                    bufferSize = "0",
                    defaultTtl = "999",
                ),
            )

        assertEquals("invalid_port", errors[ConfigFieldProxyPort])
        assertEquals("out_of_range", errors[ConfigFieldMaxConnections])
        assertEquals("out_of_range", errors[ConfigFieldBufferSize])
        assertEquals("out_of_range", errors[ConfigFieldDefaultTtl])
    }

    @Test
    fun `config draft surfaces custom dot dns summary`() {
        val draft =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDot)
                .setEncryptedDnsHost("dot.example.test")
                .setEncryptedDnsPort(853)
                .setEncryptedDnsTlsServerName("dot.example.test")
                .clearEncryptedDnsBootstrapIps()
                .addAllEncryptedDnsBootstrapIps(listOf("9.9.9.9"))
                .build()
                .toConfigDraft()

        assertEquals("9.9.9.9", draft.dnsIp)
        assertEquals("Encrypted DNS · Custom resolver (DoT)", draft.dnsSummary)
    }

    @Test
    fun `config draft surfaces plain dns and dnscrypt summaries`() {
        val plainDraft =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModePlainUdp)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol("")
                .setEncryptedDnsHost("")
                .setEncryptedDnsPort(0)
                .setEncryptedDnsTlsServerName("")
                .clearEncryptedDnsBootstrapIps()
                .setEncryptedDnsDohUrl("")
                .setEncryptedDnsDnscryptProviderName("")
                .setEncryptedDnsDnscryptPublicKey("")
                .build()
                .toConfigDraft()

        val dnsCryptDraft =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsIp("8.8.8.8")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDnsCrypt)
                .setEncryptedDnsHost("dnscrypt.example.test")
                .setEncryptedDnsPort(5443)
                .clearEncryptedDnsBootstrapIps()
                .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8"))
                .setEncryptedDnsDnscryptProviderName("2.dnscrypt-cert.example.test")
                .setEncryptedDnsDnscryptPublicKey(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ).build()
                .toConfigDraft()

        assertEquals("Plain DNS · 9.9.9.9", plainDraft.dnsSummary)
        assertEquals("Encrypted DNS · Custom resolver (DNSCrypt)", dnsCryptDraft.dnsSummary)
    }

    @Test
    fun `relay validation rejects unsupported hysteria salamander and masque cloudflare modes`() {
        val hysteriaErrors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindHysteria2,
                    relayServer = "relay.example",
                    relayServerName = "relay.example",
                    relayHysteriaPassword = "secret",
                    relayHysteriaSalamanderKey = "salamander",
                ),
            )
        val masqueErrors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindMasque,
                    relayMasqueUrl = "https://masque.example/",
                    relayMasqueCloudflareMode = true,
                ),
            )

        assertEquals("unsupported", hysteriaErrors[ConfigFieldRelayCredentials])
        assertEquals("unsupported", masqueErrors[ConfigFieldRelayCredentials])
    }
}
