package com.poyka.ripdpi.integration

import com.poyka.ripdpi.core.NativeEchTlsHandshakeBridge
import com.poyka.ripdpi.core.NativeWarpKeyPairGenerator
import com.poyka.ripdpi.core.NetworkDiagnosticsNativeBindings
import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiWarpNativeBindings
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksNativeBindings
import com.poyka.ripdpi.core.Tun2SocksTunnel
import com.poyka.ripdpi.core.WarpProbeSocketController
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeBridgeInstrumentedTest {
    @Test
    fun warpKeyPairGenerationUsesThePackagedJniBinding() =
        runBlocking {
            val generator = NativeWarpKeyPairGenerator()
            val first = generator.generate()
            val second = generator.generate()

            assertEquals(32, Base64.getDecoder().decode(first.privateKey).size)
            assertEquals(32, Base64.getDecoder().decode(first.publicKey).size)
            assertTrue("Each registration needs a new private key", first.privateKey != second.privateKey)
            assertTrue("Each registration needs a new public key", first.publicKey != second.publicKey)
            assertTrue("Debug output must hide private keys", !first.toString().contains(first.privateKey))
        }

    @Test
    fun warpProbeGuardDeniesBeforeSendAndClearsCallbackExceptions() =
        runBlocking {
            // Public test vectors: https://www.rfc-editor.org/rfc/rfc7748#section-6.1
            fun vector(hex: String): String =
                Base64.getEncoder().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            val privateKey = vector("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
            val peerPublicKey = vector("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
            val bindings = RipDpiWarpNativeBindings()
            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { receiver ->
                receiver.soTimeout = 500
                val request =
                    // Keep this JNI contract test independent of target DTO constructors and serializers.
                    """
                    {
                      "endpoint": {
                        "host": "127.0.0.1",
                        "ipv4": null,
                        "ipv6": null,
                        "port": ${receiver.localPort},
                        "source": "provisioning"
                      },
                      "privateKey": "$privateKey",
                      "peerPublicKey": "$peerPublicKey",
                      "clientId": null,
                      "amnezia": {
                        "wireProfile": "awg-3.1-ranges-v1",
                        "enabled": false,
                        "jc": 0,
                        "jmin": 0,
                        "jmax": 0,
                        "h1": "1",
                        "h2": "2",
                        "h3": "3",
                        "h4": "4",
                        "s1": 0,
                        "s2": 0,
                        "s3": 0,
                        "s4": 0,
                        "i1": "",
                        "i2": "",
                        "i3": "",
                        "i4": "",
                        "i5": "",
                        "presharedKey": "",
                        "persistentKeepalive": 25,
                        "v3": {
                          "headerProtectionKey": "",
                          "contentPaddingAddition": "",
                          "rekeyAfterTime": "",
                          "rekeyTimeout": "",
                          "rejectAfterTime": "",
                          "keepaliveTimeout": "",
                          "maxHandshakeAttempts": "",
                          "randomTrailers": false,
                          "disableCookies": false
                        }
                      },
                      "timeoutMs": 250,
                      "socketProtection": "inactive"
                    }
                    """.trimIndent()
                val packet = DatagramPacket(ByteArray(2048), 2048)
                val legacyProbe =
                    RipDpiWarpNativeBindings::class.java.getDeclaredMethod(
                        "jniProbeEndpoint",
                        String::class.java,
                    )
                legacyProbe.isAccessible = true
                assertNull(legacyProbe.invoke(bindings, request))
                assertThrows(SocketTimeoutException::class.java) { receiver.receive(packet) }
                for (
                controller in
                listOf(
                    WarpProbeSocketController { false },
                    WarpProbeSocketController { throw IllegalStateException("Probe protection denied") },
                )
                ) {
                    assertNull(bindings.probeEndpoint(request, controller))
                    assertThrows(SocketTimeoutException::class.java) { receiver.receive(packet) }
                }
                // A later request can use JNI after the throwing callback. Its
                // peer receives a WireGuard initiation, with no real account.
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val allowed =
                        executor.submit(
                            Callable {
                                bindings.probeEndpoint(request, WarpProbeSocketController { true })
                            },
                        )
                    receiver.soTimeout = 5_000
                    receiver.receive(packet)
                    assertEquals(148, packet.length)
                    // The separate test APK must not depend on shrunk app library overloads.
                    assertArrayEquals(byteArrayOf(1, 0, 0, 0), Arrays.copyOf(packet.data, 4))
                    assertNull(allowed.get(5, TimeUnit.SECONDS))
                } finally {
                    executor.shutdownNow()
                    assertTrue("Probe worker must stop", executor.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }

    @Test
    fun nativeEchRequestCancellationIsBoundToItsHandle() {
        val handle = NativeEchTlsHandshakeBridge.createRequest()
        assertTrue(handle > 0L)
        try {
            NativeEchTlsHandshakeBridge.cancelRequest(handle)
            val payload =
                NativeEchTlsHandshakeBridge.connect(
                    handle,
                    """{"target":"127.0.0.1","echConfigBytesB64":"%%%","connectTimeoutMs":10000}""",
                )
            assertTrue(requireNotNull(payload).contains("\"cancelled\":true"))
        } finally {
            NativeEchTlsHandshakeBridge.releaseRequest(handle)
        }
        NativeEchTlsHandshakeBridge.cancelRequest(handle)
        NativeEchTlsHandshakeBridge.releaseRequest(handle)
    }

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
            proxy.awaitReady(timeoutMillis = 5_000)
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
            val preferences = RipDpiProxyCmdPreferences("--ip 127.0.0.1 --port $port --split host+1")

            val proxyJob =
                async(Dispatchers.IO) {
                    proxy.startProxy(preferences)
                }

            awaitPortOpen(port)
            proxy.awaitReady(timeoutMillis = 5_000)
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
                  "schemaVersion":3,
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
                  "schemaVersion":3,
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

    @Test
    fun rawBindingsAcceptZapretEgressStrategyTunnelConfig() {
        val tunnelBindings = Tun2SocksNativeBindings()
        val strategyChainYaml =
            """
            version: 1
            strategies:
              - id: fake_tls
                match:
                  proto: [tls]
                  port: [443]
                steps:
                  - type: fake
                    ttl: 5
              - id: quic_udplen
                match:
                  proto: [quic]
                  port: [443]
                steps:
                  - type: udplen
                    delta: 4
              - id: tcp_ipv6_ext
                match:
                  proto: [tls]
                steps:
                  - type: ipv6Ext
                    ext_type: destopts
            """.trimIndent()

        val tunnelHandle =
            tunnelBindings.create(
                Json.encodeToString(
                    Tun2SocksConfig(
                        socks5Port = 1080,
                        strategyChainYaml = strategyChainYaml,
                        protectPath = "/data/user/0/com.poyka.ripdpi/files/protect.sock",
                        logLevel = "warn",
                    ),
                ),
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
