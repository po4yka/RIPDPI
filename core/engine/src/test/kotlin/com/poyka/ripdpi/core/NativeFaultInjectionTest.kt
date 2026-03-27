package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NativeFaultInjectionTest {
    @Test
    fun `proxy start failure clears handle and allows restart`() =
        runTest {
            val bindings = FakeRipDpiProxyBindings()
            val proxy = RipDpiProxy(bindings)

            bindings.faults.enqueue(
                FaultSpec(
                    target = ProxyBindingFaultTarget.START,
                    outcome = FaultOutcome.EXCEPTION,
                    message = "start failed",
                ),
            )

            val firstError =
                runCatching {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1080)))
                }.exceptionOrNull()
            assertTrue(firstError is IOException)
            assertEquals(0L, currentHandle(proxy))
            assertEquals(1, bindings.destroyedHandles.size)

            bindings.startResult = 0
            val blocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            bindings.startBlocker = blocker
            bindings.startedSignal = started
            val startJob =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1081)))
                }

            assertEquals(1L, started.await())
            proxy.stopProxy()
            blocker.complete(Unit)
            assertEquals(0, startJob.await())
            assertEquals(0L, currentHandle(proxy))
        }

    @Test
    fun `proxy telemetry malformed payload surfaces parsing failure while running`() =
        runTest {
            val bindings = FakeRipDpiProxyBindings()
            val proxy = RipDpiProxy(bindings)
            val blocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            bindings.startBlocker = blocker
            bindings.startedSignal = started
            val startJob =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1082)))
                }

            assertEquals(1L, started.await())
            bindings.faults.enqueue(
                FaultSpec(
                    target = ProxyBindingFaultTarget.TELEMETRY,
                    outcome = FaultOutcome.MALFORMED_PAYLOAD,
                ),
            )

            val error = runCatching { proxy.pollTelemetry() }.exceptionOrNull()

            assertTrue(error != null)
            proxy.stopProxy()
            blocker.complete(Unit)
            startJob.await()
        }

    @Test
    fun `tunnel stop failure still clears handle and destroys session`() =
        runTest {
            val bindings = FakeTun2SocksBindings()
            val tunnel = Tun2SocksTunnel(bindings)
            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 41)

            bindings.faults.enqueue(
                FaultSpec(
                    target = TunnelBindingFaultTarget.STOP,
                    outcome = FaultOutcome.EXCEPTION,
                    message = "stop failed",
                ),
            )

            val error = runCatching { tunnel.stop() }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(0L, currentHandle(tunnel))
            assertEquals(1, bindings.destroyedHandles.size)
        }

    @Test
    fun `tunnel telemetry blank payload falls back to idle snapshot`() =
        runTest {
            val bindings = FakeTun2SocksBindings()
            val tunnel = Tun2SocksTunnel(bindings)
            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 42)

            bindings.faults.enqueue(
                FaultSpec(
                    target = TunnelBindingFaultTarget.TELEMETRY,
                    outcome = FaultOutcome.BLANK_PAYLOAD,
                ),
            )

            val telemetry = tunnel.telemetry()

            assertEquals("idle", telemetry.state)
            tunnel.stop()
        }

    @Test
    fun `diagnostics destroy failure still clears local handle`() =
        runTest {
            val bindings = FakeNetworkDiagnosticsBindings()
            val diagnostics = NetworkDiagnostics(bindings)

            diagnostics.pollProgressJson()
            bindings.faults.enqueue(
                FaultSpec(
                    target = DiagnosticsBindingFaultTarget.DESTROY,
                    outcome = FaultOutcome.EXCEPTION,
                    message = "destroy failed",
                ),
            )

            val error = runCatching { diagnostics.destroy() }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(0L, currentHandle(diagnostics))
            assertEquals(1, bindings.destroyedHandles.size)
        }

    // -- Proxy guard / zero-handle paths ------------------------------------

    @Test
    fun `proxy create failure with zero handle throws SessionCreationFailed`() =
        runTest {
            val bindings = FakeRipDpiProxyBindings().apply { createdHandle = 0L }
            val proxy = RipDpiProxy(bindings)

            val error =
                runCatching {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1090)))
                }.exceptionOrNull()

            assertTrue(error is NativeError.SessionCreationFailed)
        }

    @Test
    fun `proxy awaitReady throws NotRunning when no startup in progress`() =
        runTest {
            val proxy = RipDpiProxy(FakeRipDpiProxyBindings())

            val error = runCatching { proxy.awaitReady() }.exceptionOrNull()

            assertTrue(error is NativeError.NotRunning)
        }

    @Test
    fun `proxy pollTelemetry returns idle snapshot when handle is zero`() =
        runTest {
            val proxy = RipDpiProxy(FakeRipDpiProxyBindings())

            val telemetry = proxy.pollTelemetry()

            assertEquals("idle", telemetry.state)
        }

    @Test
    fun `proxy stopProxy throws NotRunning when handle is zero`() =
        runTest {
            val proxy = RipDpiProxy(FakeRipDpiProxyBindings())

            val error = runCatching { proxy.stopProxy() }.exceptionOrNull()

            assertTrue(error is NativeError.NotRunning)
        }

    // -- Tunnel guard / zero-handle paths -------------------------------------

    @Test
    fun `tunnel create failure with zero handle throws SessionCreationFailed`() =
        runTest {
            val bindings = FakeTun2SocksBindings().apply { createdHandle = 0L }
            val tunnel = Tun2SocksTunnel(bindings)

            val error =
                runCatching {
                    tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 50)
                }.exceptionOrNull()

            assertTrue(error is NativeError.SessionCreationFailed)
        }

    @Test
    fun `tunnel start failure destroys created handle before propagating`() =
        runTest {
            val bindings = FakeTun2SocksBindings()
            val tunnel = Tun2SocksTunnel(bindings)

            bindings.faults.enqueue(
                FaultSpec(
                    target = TunnelBindingFaultTarget.START,
                    outcome = FaultOutcome.EXCEPTION,
                    message = "start failed",
                ),
            )

            val error =
                runCatching {
                    tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 51)
                }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(0L, currentHandle(tunnel))
            assertEquals(1, bindings.destroyedHandles.size)
            assertEquals(bindings.createdHandle, bindings.destroyedHandles.single())
        }

    @Test
    fun `tunnel start when already running throws AlreadyRunning`() =
        runTest {
            val bindings = FakeTun2SocksBindings()
            val tunnel = Tun2SocksTunnel(bindings)
            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 52)

            val error =
                runCatching {
                    tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 53)
                }.exceptionOrNull()

            assertTrue(error is NativeError.AlreadyRunning)
            tunnel.stop()
        }

    @Test
    fun `tunnel stop when not running throws NotRunning`() =
        runTest {
            val tunnel = Tun2SocksTunnel(FakeTun2SocksBindings())

            val error = runCatching { tunnel.stop() }.exceptionOrNull()

            assertTrue(error is NativeError.NotRunning)
        }

    @Test
    fun `tunnel stats returns empty when handle is zero`() =
        runTest {
            val tunnel = Tun2SocksTunnel(FakeTun2SocksBindings())

            val stats = tunnel.stats()

            assertEquals(TunnelStats(), stats)
        }

    @Test
    fun `tunnel telemetry returns idle when handle is zero`() =
        runTest {
            val tunnel = Tun2SocksTunnel(FakeTun2SocksBindings())

            val telemetry = tunnel.telemetry()

            assertEquals("idle", telemetry.state)
        }

    private fun currentHandle(target: Any): Long {
        val field = target.javaClass.getDeclaredField("handle")
        field.isAccessible = true
        return field.getLong(target)
    }
}
