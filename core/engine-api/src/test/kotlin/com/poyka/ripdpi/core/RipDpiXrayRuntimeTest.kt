package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RipDpiXrayRuntimeTest {
    private val secretConfig =
        """
        {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[
        {"id":"d0c0ffee-dead-beef-cafe-000000000001","flow":"xtls-rprx-vision"}]}]}}]}
        """.trimIndent()

    private val protect = XrayProtectController { true }

    @Test
    fun `start registers protect before invoking native start`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val runtime = RipDpiXrayRuntime(bridge)

            val code = runtime.start(secretConfig, protect)

            assertEquals(0, code)
            assertEquals(VpnProviderState.Starting, runtime.providerState)
            // Protect-first invariant: registerProtect strictly precedes start.
            val registerIdx = bridge.callLog.indexOf("registerProtect")
            val startIdx = bridge.callLog.indexOf("start")
            assertTrue("registerProtect must be logged", registerIdx >= 0)
            assertTrue("start must be logged", startIdx >= 0)
            assertTrue("registerProtect must precede start", registerIdx < startIdx)
            assertSame(protect, bridge.registeredProtectController)
        }

    @Test
    fun `start rejects blank config without touching native start`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val runtime = RipDpiXrayRuntime(bridge)

            try {
                runtime.start("   ", protect)
                fail("expected InvalidConfig")
            } catch (e: XrayRuntimeException.InvalidConfig) {
                assertNotNull(e.message)
            }
            assertEquals(0, bridge.startCount)
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
        }

    @Test
    fun `startup failure maps to typed StartupFailed and resets to Stopped`() =
        runTest {
            val bridge = FakeXrayNativeBridge(startCode = 7)
            val runtime = RipDpiXrayRuntime(bridge)

            try {
                runtime.start(secretConfig, protect)
                fail("expected StartupFailed")
            } catch (e: XrayRuntimeException.StartupFailed) {
                assertEquals(7, e.code)
            }
            // Reset so a retry is possible.
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
        }

    @Test
    fun `awaitReady transitions to Running once listener is ready`() =
        runTest {
            val bridge = FakeXrayNativeBridge(readyAfterPolls = 3)
            val runtime = RipDpiXrayRuntime(bridge)

            runtime.start(secretConfig, protect)
            runtime.awaitReady(timeoutMillis = 10_000)

            assertEquals(VpnProviderState.Running, runtime.providerState)
        }

    @Test
    fun `awaitReady times out and tears down when listener never becomes ready`() =
        runTest {
            // Listener never ready (needs more polls than the timeout allows),
            // but process stays alive so it is a timeout, not a crash.
            val bridge = FakeXrayNativeBridge(readyAfterPolls = Int.MAX_VALUE)
            val runtime = RipDpiXrayRuntime(bridge, readinessPollIntervalMs = 50)

            runtime.start(secretConfig, protect)
            try {
                runtime.awaitReady(timeoutMillis = 200)
                fail("expected ReadinessTimeout")
            } catch (e: XrayRuntimeException.ReadinessTimeout) {
                assertNotNull(e.message)
            }
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
            // Timed-out start must be torn down.
            assertTrue("stop must run on readiness timeout", bridge.stopCount >= 1)
        }

    @Test
    fun `awaitReady maps process exit during startup to typed Crashed`() =
        runTest {
            val bridge =
                FakeXrayNativeBridge(
                    readyAfterPolls = Int.MAX_VALUE,
                    aliveDuringStartup = false,
                )
            val runtime = RipDpiXrayRuntime(bridge)

            runtime.start(secretConfig, protect)
            try {
                runtime.awaitReady(timeoutMillis = 10_000)
                fail("expected Crashed")
            } catch (e: XrayRuntimeException.Crashed) {
                assertNotNull(e.message)
            }
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
        }

    @Test
    fun `awaitReady from wrong state is rejected`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val runtime = RipDpiXrayRuntime(bridge)

            try {
                runtime.awaitReady()
                fail("expected IllegalLifecycle")
            } catch (e: XrayRuntimeException.IllegalLifecycle) {
                assertNotNull(e.message)
            }
        }

    // The stop-path tests use runBlocking (real time): stop() bounds a blocking
    // native call on Dispatchers.IO, which does not compose with runTest's
    // virtual clock. Readiness tests above keep runTest for fast virtual delays.

    @Test
    fun `stop returns Clean on normal teardown`() {
        val bridge = FakeXrayNativeBridge(readyAfterPolls = 0)
        val runtime = RipDpiXrayRuntime(bridge)

        runBlocking {
            runtime.start(secretConfig, protect)
            runtime.awaitReady(timeoutMillis = 10_000)

            val cause = runtime.stop()

            assertEquals(StopCause.Clean, cause)
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
            assertEquals(1, bridge.stopCount)
        }
    }

    @Test
    fun `stop is idempotent and reports AlreadyStopped without touching native`() {
        val bridge = FakeXrayNativeBridge()
        val runtime = RipDpiXrayRuntime(bridge)

        runBlocking {
            // Late / never-started stop.
            val first = runtime.stop()
            assertEquals(StopCause.AlreadyStopped, first)
            assertEquals(0, bridge.stopCount)

            // Start, stop once cleanly, then stop again — second is AlreadyStopped.
            runtime.start(secretConfig, protect)
            runtime.awaitReady(timeoutMillis = 10_000)
            assertEquals(StopCause.Clean, runtime.stop())
            assertEquals(StopCause.AlreadyStopped, runtime.stop())
            assertEquals(1, bridge.stopCount)
        }
    }

    @Test
    fun `stop maps a throwing native stop to typed Failed but still reaches Stopped`() {
        val bridge =
            FakeXrayNativeBridge(stopBehavior = FakeXrayNativeBridge.StopBehavior.Throw)
        val runtime = RipDpiXrayRuntime(bridge)

        runBlocking {
            runtime.start(secretConfig, protect)
            runtime.awaitReady(timeoutMillis = 10_000)

            val cause = runtime.stop()

            assertTrue(cause is StopCause.Failed)
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
        }
    }

    @Test
    fun `stop bounds a hanging native stop and maps to Failed`() {
        // Real-time test: a hanging native stop must not block past the bound.
        // Uses runBlocking (real dispatcher) so the withTimeoutOrNull fires.
        val bridge =
            FakeXrayNativeBridge(stopBehavior = FakeXrayNativeBridge.StopBehavior.Hang)
        val runtime = RipDpiXrayRuntime(bridge)

        runBlocking {
            runtime.start(secretConfig, protect)
            runtime.awaitReady(timeoutMillis = 10_000)

            val cause = runtime.stop(timeoutMillis = 150)

            assertTrue("hanging stop must map to Failed", cause is StopCause.Failed)
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
        }
    }

    @Test
    fun `pollTelemetry carries version and provider state but no profile secrets`() =
        runTest {
            val bridge =
                FakeXrayNativeBridge(versionString = "Xray 26.4.7 (fake)")
            val runtime = RipDpiXrayRuntime(bridge, config = XrayProviderConfig(localInboundPort = 10808))

            // Stopped snapshot.
            val stopped = runtime.pollTelemetry()
            assertEquals("xray", stopped.source)
            assertEquals("xray", stopped.ptRuntimeKind)
            assertEquals("stopped", stopped.ptRuntimeState)
            assertEquals("Xray 26.4.7 (fake)", stopped.ptRuntimeVersion)
            assertNull("no listener address while stopped", stopped.listenerAddress)

            runtime.start(secretConfig, protect)
            runtime.awaitReady(timeoutMillis = 10_000)
            val running = runtime.pollTelemetry()

            assertEquals("running", running.ptRuntimeState)
            assertEquals("healthy", running.health)
            assertEquals("127.0.0.1:10808", running.listenerAddress)

            // Secret-safety: no field of the snapshot leaks config secrets.
            val serialized = running.toString() + stopped.toString()
            assertFalse(
                "telemetry must not contain the VLESS UUID",
                serialized.contains("d0c0ffee-dead-beef-cafe-000000000001"),
            )
            assertFalse(
                "telemetry must not contain raw outbound config",
                serialized.contains("outbounds"),
            )
        }

    @Test
    fun `pollTelemetry degrades version to unknown when bridge version throws`() =
        runTest {
            val bridge =
                object : FakeXrayNativeBridge() {
                    override fun version(): String = error("native gone")
                }
            val runtime = RipDpiXrayRuntime(bridge)

            val snapshot = runtime.pollTelemetry()

            assertEquals("unknown", snapshot.ptRuntimeVersion)
        }

    @Test
    fun `protect controller is the exact instance the bridge will invoke`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val runtime = RipDpiXrayRuntime(bridge)
            var protectedFd = -1
            val recording =
                XrayProtectController { fd ->
                    protectedFd = fd
                    true
                }

            runtime.start(secretConfig, recording)

            // Simulate libXray invoking the registered protect callback for an fd.
            val accepted = bridge.registeredProtectController?.protect(42) ?: false
            assertTrue(accepted)
            assertEquals(42, protectedFd)
        }
}
