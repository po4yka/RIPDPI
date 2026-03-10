package com.poyka.ripdpi.integration

import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksNativeBindings
import com.poyka.ripdpi.core.Tun2SocksTunnel
import com.poyka.ripdpi.core.TunnelStats
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeInstrumentedTest {
    @Test
    fun proxyWrapperStartsStopsAndRejectsDuplicateUse() =
        runBlocking {
            val proxy = RipDpiProxy(RipDpiProxyNativeBindings())
            val port = reserveLoopbackPort()
            val preferences = RipDpiProxyUIPreferences(port = port)

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
            assertEquals(0, proxyJob.await())
        }

    @Test
    fun tunnelWrapperReportsIdleStatsAndRejectsInvalidFd() =
        runBlocking {
            val tunnel = Tun2SocksTunnel(Tun2SocksNativeBindings())

            assertEquals(TunnelStats(), tunnel.stats())

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = -1)
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
}
