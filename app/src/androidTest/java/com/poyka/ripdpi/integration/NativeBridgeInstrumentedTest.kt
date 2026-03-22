package com.poyka.ripdpi.integration

import com.poyka.ripdpi.core.NetworkDiagnosticsNativeBindings
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksNativeBindings
import com.poyka.ripdpi.core.Tun2SocksTunnel
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class NativeBridgeInstrumentedTest {
    @Test
    fun proxyWrapperStartsStopsAndRejectsDuplicateUse() {
        runBlocking {
            val proxy = RipDpiProxy(RipDpiProxyNativeBindings())
            val port = reserveLoopbackPort()
            val preferences = RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = port))

            assertThrows(IllegalStateException::class.java) {
                runBlocking { proxy.stopProxy() }
            }

            val proxyJob =
                async(Dispatchers.IO) {
                    proxy.startProxy(preferences)
                }

            awaitPortOpen(port)
            val duplicate =
                runCatching {
                    proxy.startProxy(preferences)
                }.exceptionOrNull()
            assertTrue(duplicate is IllegalStateException)

            proxy.stopProxy()
            assertStoppedExitCode(proxyJob.await())
        }
    }

    @Test
    fun proxyWrapperStartsWithCommandLinePayload() {
        runBlocking {
            val proxy = RipDpiProxy(RipDpiProxyNativeBindings())
            val port = reserveLoopbackPort()
            val preferences = RipDpiProxyCmdPreferences("--ip 127.0.0.1 --port $port --split 1+s")

            val proxyJob =
                async(Dispatchers.IO) {
                    proxy.startProxy(preferences)
                }

            awaitPortOpen(port)
            proxy.stopProxy()
            assertStoppedExitCode(proxyJob.await())
        }
    }

    @Test
    fun tunnelWrapperReportsIdleStatsAndRejectsInvalidFd() {
        runBlocking {
            val tunnel = Tun2SocksTunnel(Tun2SocksNativeBindings())

            assertEquals(TunnelStats(), tunnel.stats())

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = -1)
                }
            }
        }
    }

    @Test
    fun rawBindingsSurfaceMalformedJsonAsIllegalArgument() {
        val proxyBindings = RipDpiProxyNativeBindings()
        val tunnelBindings = Tun2SocksNativeBindings()

        assertThrows(IllegalArgumentException::class.java) {
            proxyBindings.create("{")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tunnelBindings.create("{")
        }
    }

    @Test
    fun rawBindingsRejectInvalidHandles() {
        val proxyBindings = RipDpiProxyNativeBindings()
        val tunnelBindings = Tun2SocksNativeBindings()

        assertThrows(IllegalArgumentException::class.java) {
            proxyBindings.start(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            proxyBindings.stop(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            proxyBindings.destroy(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tunnelBindings.start(0, 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tunnelBindings.stop(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tunnelBindings.getStats(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tunnelBindings.destroy(0)
        }
    }

    @Test
    fun rawBindingsCreateAndDestroySessionsWithoutStarting() {
        val proxyBindings = RipDpiProxyNativeBindings()
        val diagnosticsBindings = NetworkDiagnosticsNativeBindings()
        val tunnelBindings = Tun2SocksNativeBindings()

        val proxyHandle =
            proxyBindings.create(
                RipDpiProxyUIPreferences(
                    listen = RipDpiListenConfig(port = reserveLoopbackPort()),
                ).toNativeConfigJson(),
            )
        val diagnosticsHandle = diagnosticsBindings.create()
        val tunnelHandle =
            tunnelBindings.create(
                """
                {
                  "tunnelName":"tun0",
                  "tunnelMtu":8500,
                  "multiQueue":false,
                  "tunnelIpv4":null,
                  "tunnelIpv6":null,
                  "socks5Address":"127.0.0.1",
                  "socks5Port":1080,
                  "socks5Udp":"udp",
                  "socks5UdpAddress":null,
                  "socks5Pipeline":null,
                  "username":null,
                  "password":null,
                  "mapdnsAddress":null,
                  "mapdnsPort":null,
                  "mapdnsNetwork":null,
                  "mapdnsNetmask":null,
                  "mapdnsCacheSize":null,
                  "taskStackSize":81920,
                  "tcpBufferSize":null,
                  "udpRecvBufferSize":null,
                  "udpCopyBufferNums":null,
                  "maxSessionCount":null,
                  "connectTimeoutMs":null,
                  "tcpReadWriteTimeoutMs":null,
                  "udpReadWriteTimeoutMs":null,
                  "logLevel":"warn",
                  "limitNofile":null
                }
                """.trimIndent(),
            )

        assertTrue(proxyHandle > 0)
        assertTrue(diagnosticsHandle > 0)
        assertTrue(tunnelHandle > 0)

        proxyBindings.destroy(proxyHandle)
        diagnosticsBindings.destroy(diagnosticsHandle)
        tunnelBindings.destroy(tunnelHandle)
    }

    @Test
    fun rawBindingsAcceptEncryptedDnsTunnelConfig() {
        val tunnelBindings = Tun2SocksNativeBindings()

        val tunnelHandle =
            tunnelBindings.create(
                """
                {
                  "tunnelName":"tun0",
                  "socks5Address":"127.0.0.1",
                  "socks5Port":1080,
                  "mapdnsAddress":"198.18.0.53",
                  "mapdnsPort":53,
                  "mapdnsNetwork":"198.18.0.0",
                  "mapdnsNetmask":"255.254.0.0",
                  "mapdnsCacheSize":10000,
                  "encryptedDnsResolverId":"cloudflare",
                  "encryptedDnsProtocol":"doh",
                  "encryptedDnsHost":"cloudflare-dns.com",
                  "encryptedDnsPort":443,
                  "encryptedDnsTlsServerName":"cloudflare-dns.com",
                  "encryptedDnsBootstrapIps":["1.1.1.1","1.0.0.1"],
                  "encryptedDnsDohUrl":"https://cloudflare-dns.com/dns-query",
                  "dnsQueryTimeoutMs":4000,
                  "resolverFallbackActive":true,
                  "resolverFallbackReason":"UDP DNS showed udp_blocked",
                  "logLevel":"warn"
                }
                """.trimIndent(),
            )

        assertTrue(tunnelHandle > 0)

        tunnelBindings.destroy(tunnelHandle)
    }

    private suspend fun awaitPortOpen(
        port: Int,
        timeoutMs: Long = 5_000,
    ) {
        withTimeout(timeoutMs) {
            while (true) {
                val connected =
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                        }
                    }.isSuccess
                if (connected) {
                    return@withTimeout
                }
                delay(50)
            }
        }
    }

    private fun reserveLoopbackPort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }

    private fun assertStoppedExitCode(exitCode: Int) {
        assertTrue(
            "Expected a clean shutdown code, got $exitCode",
            exitCode == 0 || exitCode == 22,
        )
    }
}
