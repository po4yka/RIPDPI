package com.poyka.ripdpi.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class Tun2SocksTunnelTest {
    @Test
    fun tunnelStatsMapNativeArrayIntoTypedModel() {
        val stats = TunnelStats.fromNative(longArrayOf(10L, 20L, 30L, 40L))

        assertEquals(10L, stats.txPackets)
        assertEquals(20L, stats.txBytes)
        assertEquals(30L, stats.rxPackets)
        assertEquals(40L, stats.rxBytes)
    }

    @Test
    fun tunnelConfigSerializesToCamelCaseJson() {
        val config =
            Tun2SocksConfig(
                tunnelName = "tun1",
                socks5Port = 1081,
                logLevel = "info",
            )

        val payload = Json.parseToJsonElement(Json.encodeToString(config)).jsonObject

        assertEquals("tun1", payload.getValue("tunnelName").jsonPrimitive.content)
        assertEquals("1081", payload.getValue("socks5Port").jsonPrimitive.content)
        assertEquals("info", payload.getValue("logLevel").jsonPrimitive.content)
    }

    @Test
    fun encryptedDnsConfigSerializesResolverAndFallbackFields() {
        val config =
            Tun2SocksConfig(
                socks5Port = 1080,
                mapdnsAddress = "198.18.0.53",
                mapdnsPort = 53,
                mapdnsNetwork = "198.18.0.0",
                mapdnsNetmask = "255.254.0.0",
                encryptedDnsResolverId = "cloudflare",
                encryptedDnsProtocol = "doh",
                encryptedDnsHost = "cloudflare-dns.com",
                encryptedDnsPort = 443,
                encryptedDnsTlsServerName = "cloudflare-dns.com",
                encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
                dnsQueryTimeoutMs = 4_000,
                resolverFallbackActive = true,
                resolverFallbackReason = "UDP DNS showed udp_blocked",
            )

        val payload = Json.parseToJsonElement(Json.encodeToString(config)).jsonObject

        assertEquals("cloudflare", payload.getValue("encryptedDnsResolverId").jsonPrimitive.content)
        assertEquals("doh", payload.getValue("encryptedDnsProtocol").jsonPrimitive.content)
        assertEquals("cloudflare-dns.com", payload.getValue("encryptedDnsHost").jsonPrimitive.content)
        assertEquals("443", payload.getValue("encryptedDnsPort").jsonPrimitive.content)
        assertEquals("true", payload.getValue("resolverFallbackActive").jsonPrimitive.content)
        assertEquals("UDP DNS showed udp_blocked", payload.getValue("resolverFallbackReason").jsonPrimitive.content)
    }
}
