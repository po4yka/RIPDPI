package com.poyka.ripdpi.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeWrappersTest {
    @Test
    fun proxyWrapperCreatesStartsStopsAndDestroysSession() =
        runTest {
            val bindings = FakeRipDpiProxyBindings()
            val proxy = RipDpiProxy(bindings)

            val exitCode = proxy.startProxy(RipDpiProxyUIPreferences(port = 1081))

            assertEquals(0, exitCode)
            assertTrue(bindings.lastCreatePayload?.contains("\"kind\":\"ui\"") == true)
            assertEquals(1L, bindings.lastStartedHandle)
            assertEquals(1L, bindings.lastDestroyedHandle)
        }

    @Test
    fun proxyWrapperRejectsStopWhenIdle() =
        runTest {
            val proxy = RipDpiProxy(FakeRipDpiProxyBindings())

            val error =
                runCatching {
                    proxy.stopProxy()
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
        }

    @Test
    fun tunnelWrapperUsesBindingsAndMapsStats() =
        runTest {
            val bindings =
                FakeTun2SocksBindings().apply {
                    nativeStats = longArrayOf(1L, 2L, 3L, 4L)
                }
            val tunnel = Tun2SocksTunnel(bindings)

            assertEquals(TunnelStats(), tunnel.stats())

            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 42)
            assertEquals(42, bindings.lastStartTunFd)
            assertEquals(1L, bindings.lastStartHandle)

            val stats = tunnel.stats()
            assertEquals(1L, stats.txPackets)
            assertEquals(4L, stats.rxBytes)

            tunnel.stop()
            assertEquals(1L, bindings.lastStopHandle)
            assertEquals(1L, bindings.lastDestroyedHandle)
        }
}
