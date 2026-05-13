package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    @Test
    fun failedStartDestroysNativeHandleAndReturnsIdleSnapshots() =
        runTest {
            val bindings =
                FakeTun2SocksBindings().apply {
                    startFailure = IOException("native start failed")
                    nativeStats = longArrayOf(1L, 2L, 3L, 4L)
                    telemetryJson =
                        Json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "tunnel", state = "running"),
                        )
                }
            val tunnel = Tun2SocksTunnel(bindings)

            val error = runCatching { tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 42) }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(listOf(1L), bindings.destroyedHandles)
            assertEquals(TunnelStats(), tunnel.stats())
            assertEquals("idle", tunnel.telemetry().state)
            assertTrue(bindings.statsHandles.isEmpty())
            assertTrue(bindings.telemetryHandles.isEmpty())
        }

    @Test
    fun repeatedStartStopDoesNotReuseStaleHandle() =
        runTest {
            val bindings = FakeTun2SocksBindings()
            val tunnel = Tun2SocksTunnel(bindings)

            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 42)
            tunnel.stop()
            bindings.createdHandle = 2L
            tunnel.start(Tun2SocksConfig(socks5Port = 1081), tunFd = 43)
            tunnel.stop()

            assertEquals(listOf(1L, 2L), bindings.startedHandles)
            assertEquals(listOf(1L, 2L), bindings.stoppedHandles)
            assertEquals(listOf(1L, 2L), bindings.destroyedHandles)
        }
}
