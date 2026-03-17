package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NativeBridgeWrappersTest {
    private val json = Json

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
    fun proxyWrapperRejectsDuplicateStartWhileRunning() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val startedSignal = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    this.startedSignal = startedSignal
                    startBlocker = blocker
                }
            val proxy = RipDpiProxy(bindings)

            val firstStart =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1082))
                }

            assertEquals(1L, startedSignal.await())

            val error =
                runCatching {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1083))
                }.exceptionOrNull()

            assertTrue(error is NativeError.AlreadyRunning)

            blocker.complete(Unit)
            assertEquals(0, firstStart.await())
            assertEquals(1L, bindings.lastDestroyedHandle)
        }

    @Test
    fun proxyWrapperAwaitReadyCompletesAfterRunningTelemetryAppears() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val startedSignal = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    this.startedSignal = startedSignal
                    startBlocker = blocker
                }
            val proxy = RipDpiProxy(bindings)

            val start =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1082))
                }

            assertEquals(1L, startedSignal.await())
            bindings.telemetryJson =
                json.encodeToString(
                    NativeRuntimeSnapshot.serializer(),
                    NativeRuntimeSnapshot(source = "proxy", state = "running", health = "healthy"),
                )

            proxy.awaitReady()

            blocker.complete(Unit)
            assertEquals(0, start.await())
        }

    @Test
    fun proxyWrapperAwaitReadyPropagatesNativeStartFailure() =
        runTest {
            val startedSignal = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    this.startedSignal = startedSignal
                    startFailure = IOException("proxy boom")
                }
            val proxy = RipDpiProxy(bindings)

            val start =
                async {
                    runCatching {
                        proxy.startProxy(RipDpiProxyUIPreferences(port = 1082))
                    }.exceptionOrNull()
                }

            assertEquals(1L, startedSignal.await())

            val error = runCatching { proxy.awaitReady() }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(start.await() is IOException)
        }

    @Test
    fun proxyWrapperDestroysSessionWhenNativeStartFails() =
        runTest {
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startFailure = IOException("proxy boom")
                }
            val proxy = RipDpiProxy(bindings)

            val error =
                runCatching {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1084))
                }.exceptionOrNull()

            assertTrue(error is IOException)
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

            assertTrue(error is NativeError.NotRunning)
        }

    @Test
    fun proxyWrapperRejectsMissingNativeSessionHandle() =
        runTest {
            val proxy =
                RipDpiProxy(
                    FakeRipDpiProxyBindings().apply {
                        createdHandle = 0L
                    },
                )

            val error =
                runCatching {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1085))
                }.exceptionOrNull()

            assertTrue(error is NativeError.SessionCreationFailed)
        }

    @Test
    fun proxyWrapperParsesTelemetrySnapshot() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val startedSignal = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    this.startedSignal = startedSignal
                    startBlocker = blocker
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                state = "running",
                                health = "healthy",
                                activeSessions = 2,
                                totalSessions = 5,
                                routeChanges = 3,
                                lastRouteGroup = 1,
                                listenerAddress = "127.0.0.1:1080",
                                lastTarget = "203.0.113.5:443",
                                autolearnEnabled = true,
                                learnedHostCount = 2,
                                penalizedHostCount = 1,
                                lastAutolearnHost = "example.org",
                                lastAutolearnGroup = 1,
                                lastAutolearnAction = "group_penalized",
                                nativeEvents =
                                    listOf(
                                        NativeRuntimeEvent(
                                            source = "proxy",
                                            level = "info",
                                            message = "accepted",
                                            createdAt = 1L,
                                        ),
                                    ),
                                capturedAt = 2L,
                            ),
                        )
                }
            val proxy = RipDpiProxy(bindings)

            val start =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1081))
                }
            assertEquals(1L, startedSignal.await())
            val telemetry = proxy.pollTelemetry()

            assertEquals("running", telemetry.state)
            assertEquals(2L, telemetry.activeSessions)
            assertEquals("203.0.113.5:443", telemetry.lastTarget)
            assertTrue(telemetry.autolearnEnabled)
            assertEquals(2, telemetry.learnedHostCount)
            assertEquals("group_penalized", telemetry.lastAutolearnAction)
            assertEquals(1, telemetry.nativeEvents.size)
            blocker.complete(Unit)
            assertEquals(0, start.await())
        }

    @Test
    fun proxyWrapperReturnsIdleTelemetryWhenNativePayloadIsBlank() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val startedSignal = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    this.startedSignal = startedSignal
                    startBlocker = blocker
                    telemetryJson = "   "
                }
            val proxy = RipDpiProxy(bindings)

            val start =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1081))
                }
            assertEquals(1L, startedSignal.await())

            val telemetry = proxy.pollTelemetry()

            assertEquals("idle", telemetry.state)
            assertTrue(telemetry.nativeEvents.isEmpty())
            blocker.complete(Unit)
            assertEquals(0, start.await())
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

    @Test
    fun tunnelWrapperDestroysSessionWhenNativeStartFails() =
        runTest {
            val bindings =
                FakeTun2SocksBindings().apply {
                    startFailure = IOException("boom")
                }
            val tunnel = Tun2SocksTunnel(bindings)

            val error =
                runCatching {
                    tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 77)
                }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(1L, bindings.lastDestroyedHandle)
        }

    @Test
    fun tunnelWrapperRejectsMissingNativeSessionHandle() =
        runTest {
            val tunnel =
                Tun2SocksTunnel(
                    FakeTun2SocksBindings().apply {
                        createdHandle = 0L
                    },
                )

            val error =
                runCatching {
                    tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 88)
                }.exceptionOrNull()

            assertTrue(error is NativeError.SessionCreationFailed)
        }

    @Test
    fun tunnelWrapperRejectsStopWhenIdle() =
        runTest {
            val tunnel = Tun2SocksTunnel(FakeTun2SocksBindings())

            val error =
                runCatching {
                    tunnel.stop()
                }.exceptionOrNull()

            assertTrue(error is NativeError.NotRunning)
        }

    @Test
    fun tunnelWrapperPropagatesNativeStatsFailure() =
        runTest {
            val bindings =
                FakeTun2SocksBindings().apply {
                    statsFailure = IOException("stats boom")
                }
            val tunnel = Tun2SocksTunnel(bindings)

            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 12)

            val error = runCatching { tunnel.stats() }.exceptionOrNull()

            assertTrue(error is IOException)
            tunnel.stop()
            assertEquals(1L, bindings.lastDestroyedHandle)
        }

    @Test
    fun tunnelWrapperParsesTelemetrySnapshot() =
        runTest {
            val bindings =
                FakeTun2SocksBindings().apply {
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(
                                source = "tunnel",
                                state = "running",
                                health = "healthy",
                                activeSessions = 1,
                                totalSessions = 1,
                                tunnelStats = TunnelStats(txPackets = 7, rxBytes = 99),
                                nativeEvents =
                                    listOf(
                                        NativeRuntimeEvent(
                                            source = "tunnel",
                                            level = "info",
                                            message = "tick",
                                            createdAt = 3L,
                                        ),
                                    ),
                                capturedAt = 4L,
                            ),
                        )
                }
            val tunnel = Tun2SocksTunnel(bindings)

            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 7)
            val telemetry = tunnel.telemetry()

            assertEquals("running", telemetry.state)
            assertEquals(7L, telemetry.tunnelStats.txPackets)
            assertEquals(99L, telemetry.tunnelStats.rxBytes)
            assertEquals(1, telemetry.nativeEvents.size)
            tunnel.stop()
        }

    @Test
    fun tunnelWrapperReturnsIdleTelemetryWhenNativePayloadIsBlank() =
        runTest {
            val bindings =
                FakeTun2SocksBindings().apply {
                    telemetryJson = ""
                }
            val tunnel = Tun2SocksTunnel(bindings)

            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 22)
            val telemetry = tunnel.telemetry()

            assertEquals("idle", telemetry.state)
            assertTrue(telemetry.nativeEvents.isEmpty())
            tunnel.stop()
        }
}
