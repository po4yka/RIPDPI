package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindWebTunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Regression guard for audit finding P1-2: subprocess-backed relays must not report
 * ready until their readiness probe resolves. `awaitReady()` (which the supervisor awaits
 * before marking the session Connected) must block on the per-start readiness signal that
 * `start()` completes after the probe succeeds, and surface the probe failure otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubprocessRelayReadinessSignalTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun subprocessManager(): SubprocessSocksRelayManager =
        SubprocessSocksRelayManager(
            context = context,
            protectPathProvider = ActiveProtectSocketPathProvider(),
        )

    private class FakeNaiveProxyManager(
        subprocessManager: SubprocessSocksRelayManager,
        private val startGate: CompletableDeferred<Unit>,
        private val startFailure: Throwable? = null,
    ) : NaiveProxyManager(
            DefaultNaiveProxyLaunchDelegate(subprocessManager),
            NaiveProxyPreflightProbe { error("unused by overridden start") },
            unusedUpstreamLauncher(DefaultNaiveProxyLaunchDelegate(subprocessManager)),
        ) {
        val exit = CompletableDeferred<Int>()
        var startCount = 0
            private set

        override suspend fun start(config: ResolvedRipDpiRelayConfig) {
            startCount += 1
            startGate.await()
            startFailure?.let { throw it }
        }

        override suspend fun waitForExit(): Int = exit.await()

        override fun noteRestarting(reason: String) = Unit

        override suspend fun stop() {
            if (!exit.isCompleted) exit.complete(0)
        }
    }

    private class FakePluggableTransportManager(
        context: Application,
        subprocessManager: SubprocessSocksRelayManager,
        private val startGate: CompletableDeferred<Unit>,
        private val startFailure: Throwable? = null,
    ) : PluggableTransportManager(context, subprocessManager) {
        val exit = CompletableDeferred<Int>()

        override suspend fun start(config: ResolvedRipDpiRelayConfig) {
            startGate.await()
            startFailure?.let { throw it }
        }

        override suspend fun waitForExit(): Int = exit.await()

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = "relay")

        override suspend fun stop() {
            if (!exit.isCompleted) exit.complete(0)
        }
    }

    @Test
    fun `naive runtime awaitReady blocks until probe succeeds`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val manager = FakeNaiveProxyManager(subprocessManager(), startGate = gate)
            val runtime = NaiveProxyRuntime(manager)
            val config = sampleResolvedRelayConfig(kind = RelayKindNaiveProxy)

            // Mirror the supervisor: start() is launched first and reaches its first suspension
            // (assigning the readiness signal) before awaitReady() is awaited.
            val startJob = launch { runtime.start(config) }
            runCurrent()
            var ready = false
            val readyWaiter =
                launch {
                    runtime.awaitReady()
                    ready = true
                }
            runCurrent()

            // The probe (manager.start) has not resolved yet: awaitReady must still be suspended.
            assertFalse("awaitReady returned before the probe resolved", ready)

            gate.complete(Unit)
            readyWaiter.join()

            assertTrue("awaitReady did not complete after the probe succeeded", ready)

            // start() then blocks on waitForExit(); release it so no coroutine leaks.
            manager.exit.complete(0)
            startJob.join()
        }

    @Test
    fun `naive runtime surfaces probe failure through start and awaitReady`() =
        runTest {
            val gate = CompletableDeferred(Unit)
            val failure = IOException("Subprocess relay readiness timed out")
            val manager = FakeNaiveProxyManager(subprocessManager(), startGate = gate, startFailure = failure)
            val runtime = NaiveProxyRuntime(manager)
            val config = sampleResolvedRelayConfig(kind = RelayKindNaiveProxy)

            val startResult = runCatching { runtime.start(config) }
            val readyResult = runCatching { runtime.awaitReady() }

            assertTrue("start() should rethrow the readiness failure", startResult.isFailure)
            assertEquals(failure, startResult.exceptionOrNull())
            assertTrue("awaitReady() should surface the readiness failure", readyResult.isFailure)
            // kotlinx.coroutines stacktrace recovery copies the exception across the
            // Deferred.await() boundary, so awaitReady() throws a recovered IOException with
            // the same message rather than the original instance — assert type + message.
            val readyError = readyResult.exceptionOrNull()
            assertTrue("awaitReady() should surface an IOException", readyError is IOException)
            assertEquals(failure.message, readyError?.message)
            assertEquals("manager.start should run exactly once on a readiness failure", 1, manager.startCount)
        }

    @Test
    fun `naive runtime stop during restart delay cannot relaunch helper`() =
        runTest {
            val manager = FakeNaiveProxyManager(subprocessManager(), startGate = CompletableDeferred(Unit))
            manager.exit.complete(1)
            val runtime = NaiveProxyRuntime(manager)
            val startJob = launch { runtime.start(sampleResolvedRelayConfig(kind = RelayKindNaiveProxy)) }

            runCurrent()
            runtime.stop()
            advanceTimeBy(2_000)
            runCurrent()
            startJob.join()

            assertEquals("stop during backoff must not launch a new helper", 1, manager.startCount)
        }

    @Test
    fun `naive stream failure after ready does not make a later crash terminal`() =
        runTest {
            val subprocess = subprocessManager()
            val manager = FakeNaiveProxyManager(subprocess, startGate = CompletableDeferred(Unit))
            val runtime = NaiveProxyRuntime(manager)
            val startJob = launch { runtime.start(sampleResolvedRelayConfig(kind = RelayKindNaiveProxy)) }
            runCurrent()

            subprocess.handleProcessOutputEvent(SubprocessRelayOutputEvent.Ready("naiveproxy", "0.1.0"))
            subprocess.handleProcessOutputEvent(
                SubprocessRelayOutputEvent.Error("naiveproxy", "auth", "upstream rejected CONNECT with status 407"),
            )
            manager.exit.complete(1)
            advanceUntilIdle()
            startJob.join()

            assertTrue("a crash after a stream failure must restart, got ${manager.startCount}", manager.startCount > 1)
        }

    @Test
    fun `late managed helper failure survives generic readiness exit and timeout`() =
        runTest {
            listOf(false, true).forEach { running ->
                val subprocess = subprocessManager()
                val parser = SubprocessRelayOutputParser()
                val spec =
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = "ripdpi-webtunnel",
                        commandArguments = emptyList(),
                        runtimeKind = RelayKindWebTunnel,
                        managedClientBridge = ManagedClientSocksBridgeSpec("webtunnel", "127.0.0.1", 443, ""),
                    )
                listOf("VERSION 1", "CMETHOD webtunnel socks5 127.0.0.1:43123", "CMETHODS DONE").forEach { line ->
                    parser.parse(line, spec)?.let(subprocess::handleProcessOutputEvent)
                }
                val markFailed =
                    SubprocessSocksRelayManager::class.java.getDeclaredMethod("markFailed", String::class.java).apply {
                        isAccessible = true
                    }
                val probe =
                    SubprocessRelayReadinessProbe(
                        connector = SocksReadinessConnector { _, _, _ -> false },
                        pollIntervalMs = 10L,
                        timeoutMs = 5L,
                    )

                val failure =
                    runCatching {
                        probe.waitUntilReady(
                            config =
                                sampleResolvedRelayConfig().copy(
                                    localSocksUsername = "user",
                                    localSocksPassword = "pass",
                                ),
                            isRunning = { running },
                            onFailure = { fallback ->
                                // Drive the failure between the supplier read and the manager's atomic fallback.
                                subprocess.handleProcessOutputEvent(
                                    requireNotNull(
                                        parser.parse("RIPDPI-ERROR|webtunnel|auth|bridge rejected key", spec),
                                    ),
                                )
                                markFailed.invoke(subprocess, fallback) as String
                            },
                        )
                    }.exceptionOrNull()
                val telemetry = subprocess.pollTelemetry()

                assertEquals("bridge rejected key", failure?.message)
                assertEquals("bridge rejected key", telemetry.lastError)
                assertEquals("auth", telemetry.lastFailureClass)
            }
        }

    @Test
    fun `classified upstream errors clear after parsed recovery evidence`() =
        runTest {
            val subprocess = subprocessManager()
            val parser = SubprocessRelayOutputParser()
            val spec =
                SubprocessSocksRelayLaunchSpec(
                    binaryName = NaiveUpstreamBinaryName,
                    commandArguments = emptyList(),
                    runtimeKind = RelayKindNaiveProxy,
                )
            val prefix = "[1030/121224.848214:INFO:net/tools/naive/naive_proxy.cc:288] "
            val padding =
                "[1030/121159.520448:INFO:net/tools/naive/naive_proxy_delegate.cc:171] " +
                    "https://naive.test:8443 negotiated padding type: Variant1"
            subprocess.handleProcessOutputEvent(requireNotNull(parser.parse(padding, spec)))
            for (
            (code, failureClass) in
            mapOf(
                "ERR_CERT_AUTHORITY_INVALID" to "tls",
                "ERR_PROXY_CONNECTION_FAILED" to "connect",
                "ERR_HTTP2_PROTOCOL_ERROR" to "http_connect",
                "ERR_QUIC_PROTOCOL_ERROR" to "connect",
            )
            ) {
                subprocess.handleProcessOutputEvent(
                    requireNotNull(parser.parse(prefix + "Connection 0 closed: $code", spec)),
                )
                val failure = subprocess.pollTelemetry()
                assertEquals(code, failureClass, failure.lastFailureClass)
                assertEquals("upstream connection closed: $code", failure.lastError)

                parser.parse(prefix + "Connection 1 to private-target.invalid:443 via HTTPS naive.test:8443", spec)
                subprocess.handleProcessOutputEvent(
                    requireNotNull(parser.parse(prefix + "Connection 1 closed: OK", spec)),
                )
                val recovered = subprocess.pollTelemetry()
                assertNull(recovered.lastFailureClass)
                assertNull(recovered.lastError)
            }
            assertNull(parser.parse(prefix + "Connection 2 closed: ERR_CONNECTION_REFUSED", spec))
            assertNull(subprocess.pollTelemetry().lastFailureClass)
        }

    @Test
    fun `pluggable transport runtime awaitReady blocks until probe succeeds`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val manager = FakePluggableTransportManager(context, subprocessManager(), startGate = gate)
            val runtime = PluggableTransportRuntime(manager)
            val config = sampleResolvedRelayConfig(kind = RelayKindWebTunnel)

            val startJob = launch { runtime.start(config) }
            runCurrent()
            var ready = false
            val readyWaiter =
                launch {
                    runtime.awaitReady()
                    ready = true
                }
            runCurrent()

            assertFalse("awaitReady returned before the probe resolved", ready)

            gate.complete(Unit)
            readyWaiter.join()

            assertTrue("awaitReady did not complete after the probe succeeded", ready)

            // start() then blocks on waitForExit(); release it so no coroutine leaks.
            manager.exit.complete(0)
            startJob.join()
        }

    @Test
    fun `pluggable transport readiness respects caller deadline without cancelling startup`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val manager = FakePluggableTransportManager(context, subprocessManager(), startGate = gate)
            val runtime = PluggableTransportRuntime(manager)
            val startJob = launch { runtime.start(sampleResolvedRelayConfig(kind = RelayKindWebTunnel)) }
            runCurrent()
            val result = async { runCatching { runtime.awaitReady(25) } }
            runCurrent()
            advanceTimeBy(25)
            runCurrent()

            assertTrue("readiness must finish at its caller deadline", result.isCompleted)
            assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(startJob.isActive)
            gate.complete(Unit)
            runCurrent()
            runtime.awaitReady(25)
            manager.exit.complete(0)
            startJob.join()
        }

    @Test
    fun `pluggable transport stop cancels pending readiness`() =
        runTest {
            val manager = FakePluggableTransportManager(context, subprocessManager(), CompletableDeferred())
            val runtime = PluggableTransportRuntime(manager)
            val startJob = launch { runtime.start(sampleResolvedRelayConfig(kind = RelayKindWebTunnel)) }
            runCurrent()
            val result = async { runCatching { runtime.awaitReady(100) } }
            runCurrent()

            runtime.stop()
            runCurrent()

            assertTrue(result.await().exceptionOrNull() is CancellationException)
            startJob.cancel()
        }

    @Test
    fun `pluggable transport runtime surfaces probe failure through awaitReady`() =
        runTest {
            val gate = CompletableDeferred(Unit)
            val failure = IOException("Managed PT listener readiness timed out")
            val manager =
                FakePluggableTransportManager(
                    context = context,
                    subprocessManager = subprocessManager(),
                    startGate = gate,
                    startFailure = failure,
                )
            val runtime = PluggableTransportRuntime(manager)
            val config = sampleResolvedRelayConfig(kind = RelayKindWebTunnel)

            val startResult = runCatching { runtime.start(config) }
            val readyResult = runCatching { runtime.awaitReady() }

            assertTrue("start() should rethrow the readiness failure", startResult.isFailure)
            assertEquals(failure, startResult.exceptionOrNull())
            assertTrue("awaitReady() should surface the readiness failure", readyResult.isFailure)
            // kotlinx.coroutines stacktrace recovery copies the exception across the
            // Deferred.await() boundary, so awaitReady() throws a recovered IOException with
            // the same message rather than the original instance — assert type + message.
            val readyError = readyResult.exceptionOrNull()
            assertTrue("awaitReady() should surface an IOException", readyError is IOException)
            assertEquals(failure.message, readyError?.message)
        }
}
