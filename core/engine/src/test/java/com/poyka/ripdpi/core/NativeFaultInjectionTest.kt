package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
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
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1080))
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
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1081))
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
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1082))
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

    private fun currentHandle(target: Any): Long {
        val field = target.javaClass.getDeclaredField("handle")
        field.isAccessible = true
        return field.getLong(target)
    }
}
