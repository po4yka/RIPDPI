package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RipDpiXrayRuntimeTest {
    private val secretConfig =
        """
        {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[
        {"id":"d0c0ffee-dead-beef-cafe-000000000001","flow":"xtls-rprx-vision"}]}]}}]}
        """.trimIndent()

    private val protect = XrayProtectController { true }

    @Test
    fun `blocked bootstrap lookup is detached bounded and owns the native lane`() =
        runBlocking {
            val owner = XrayRuntimeOwner(FakeXrayNativeBridge())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val operation =
                owner.resolveEndpoint {
                    entered.countDown()
                    release.await()
                    listOf("192.0.2.1")
                }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertNull(withTimeoutOrNull(25) { operation.await() })
                assertTrue(owner.isOccupied)
                assertTrue(runCatching { owner.resolveEndpoint { emptyList() } }.isFailure)
            } finally {
                release.countDown()
            }
            assertEquals(listOf("192.0.2.1"), operation.await())
            assertFalse(owner.isOccupied)
        }

    @Test
    fun `cancelled blocked start retains admission and never publishes late Running`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val bridge =
                object : FakeXrayNativeBridge() {
                    override fun start(renderedConfig: String): Int {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return super.start(renderedConfig)
                    }
                }
            val owner = XrayRuntimeOwner(bridge)
            val runtime = RipDpiXrayRuntime(owner)
            val call = async(Dispatchers.IO) { runtime.start(secretConfig, protect) }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                call.cancelAndJoin()
                assertFalse(checkNotNull(bridge.registeredProtectController).protect(42))
                assertEquals(StopCause.Pending, runtime.stop(timeoutMillis = 25))
                assertTrue(owner.isOccupied)
                assertTrue(runCatching { RipDpiXrayRuntime(owner).start(secretConfig, protect) }.isFailure)
                assertEquals(VpnProviderState.Stopping, runtime.providerState)
            } finally {
                release.countDown()
                call.cancelAndJoin()
            }
            assertTrue(runtime.stop() in setOf(StopCause.Clean, StopCause.AlreadyStopped))
            assertEquals(1, bridge.stopCount)
            assertEquals(VpnProviderState.Stopped, runtime.providerState)
            assertFalse(owner.isOccupied)
        }

    @Test
    fun `post-readiness native exit is retained as failure until cleanup`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val owner = XrayRuntimeOwner(bridge, Dispatchers.Unconfined)
            val runtime = RipDpiXrayRuntime(owner)
            runtime.start(secretConfig, protect)
            runtime.awaitReady()
            bridge.aliveDuringStartup = false
            assertTrue(runtime.observe().failed)
            assertEquals("failed", runtime.pollTelemetry().health)
            assertTrue(owner.isOccupied)
            assertEquals(StopCause.Clean, runtime.stop())
            assertFalse(owner.isOccupied)
        }

    @Test
    fun `start registers protect before invoking native start`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

            val code = runtime.start(secretConfig, protect)

            assertEquals(0, code)
            assertEquals(VpnProviderState.Starting, runtime.providerState)
            // Protect-first invariant: registerProtect strictly precedes start.
            val registerIdx = bridge.callLog.indexOf("registerProtect")
            val startIdx = bridge.callLog.indexOf("start")
            assertTrue("registerProtect must be logged", registerIdx >= 0)
            assertTrue("start must be logged", startIdx >= 0)
            assertTrue("registerProtect must precede start", registerIdx < startIdx)
            assertTrue(checkNotNull(bridge.registeredProtectController).protect(42))
        }

    @Test
    fun `start rejects blank config without touching native start`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

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
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

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
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

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
            val runtime =
                RipDpiXrayRuntime(
                    XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined),
                    readinessPollIntervalMs = 50,
                )

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
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

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
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

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
        val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

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
        val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

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
    fun `failed stop retains ownership until cleanup allows replacement`() =
        runTest {
            withContext(Dispatchers.IO) {
                val rejectStop = AtomicBoolean(true)
                val bridge =
                    object : FakeXrayNativeBridge() {
                        override fun stop() {
                            check(!rejectStop.get()) { "native cleanup rejected" }
                            super.stop()
                        }
                    }
                val owner = XrayRuntimeOwner(bridge)
                val current = RipDpiXrayRuntime(owner)
                val replacement = RipDpiXrayRuntime(owner)
                try {
                    current.start(secretConfig, protect)
                    current.awaitReady()
                    assertTrue(current.stop() is StopCause.Failed)
                    assertEquals(VpnProviderState.Stopping, current.providerState)
                    try {
                        replacement.start(secretConfig, protect)
                        fail("replacement must not acquire an uncleared native runtime")
                    } catch (_: XrayRuntimeException.IllegalLifecycle) {
                        // The current lease still owns native cleanup.
                    }
                    assertEquals(1, bridge.startCount)
                    rejectStop.set(false)
                    assertEquals(StopCause.Clean, current.stop())
                    assertEquals(0, replacement.start(secretConfig, protect))
                    replacement.awaitReady()
                    assertEquals(2, bridge.startCount)
                } finally {
                    rejectStop.set(false)
                    withContext(NonCancellable) {
                        replacement.stop()
                        current.stop()
                    }
                }
            }
        }

    @Test
    fun `stop returns before blocked native stop completes`() =
        runTest {
            withContext(Dispatchers.IO) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val exited = CountDownLatch(1)
                val bridge =
                    object : FakeXrayNativeBridge() {
                        override fun stop() {
                            entered.countDown()
                            try {
                                release.await()
                                super.stop()
                            } finally {
                                exited.countDown()
                            }
                        }
                    }
                val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge))
                runtime.start(secretConfig, protect)
                runtime.awaitReady()
                val stopCall = async { runtime.stop(timeoutMillis = 100) }
                try {
                    assertTrue("native stop must enter", entered.await(5, TimeUnit.SECONDS))
                    val result = withTimeoutOrNull(1_000) { stopCall.await() }
                    assertNotNull("caller must return while native stop is still blocked", result)
                    assertEquals("native call must not have completed", 1L, exited.count)
                } finally {
                    release.countDown()
                    withContext(NonCancellable) { stopCall.join() }
                    assertTrue("native stop must finish after release", exited.await(5, TimeUnit.SECONDS))
                }
            }
        }

    @Test
    fun `pollTelemetry carries version and provider state but no profile secrets`() =
        runTest {
            val bridge =
                FakeXrayNativeBridge(versionString = "Xray 26.4.7 (fake)")
            val runtime =
                RipDpiXrayRuntime(
                    XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined),
                    config = XrayProviderConfig(localInboundPort = 10808),
                )

            // Stopped snapshot.
            val stopped = runtime.pollTelemetry()
            assertEquals("xray", stopped.source)
            assertEquals("xray", stopped.ptRuntimeKind)
            assertEquals("stopped", stopped.ptRuntimeState)
            assertEquals("unknown", stopped.ptRuntimeVersion)
            assertNull("no listener address while stopped", stopped.listenerAddress)

            runtime.start(secretConfig, protect)
            runtime.awaitReady(timeoutMillis = 10_000)
            val running = runtime.pollTelemetry()

            assertEquals("running", running.ptRuntimeState)
            assertEquals("Xray 26.4.7 (fake)", running.ptRuntimeVersion)
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
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

            val snapshot = runtime.pollTelemetry()

            assertEquals("unknown", snapshot.ptRuntimeVersion)
        }

    @Test
    fun `protect controller is the exact instance the bridge will invoke`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))
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
