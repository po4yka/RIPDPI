package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProxyForwardingEvidence
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.services.testsupport.ScriptedSupervisorExit
import com.poyka.ripdpi.services.testsupport.ScriptedSupervisorExitSequence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val TestLocalProxyAuth = "alpha-123"

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyRuntimeSupervisorTest {
    @Test
    fun stopAtFormerSplitAcquisitionBoundaryRejectsRetiringRuntimeEvidence() =
        runTest {
            val leaseAcquired = CompletableDeferred<Unit>()
            val releaseLeaseAcquisition = CompletableDeferred<Unit>()
            val nativeStopStarted = CompletableDeferred<Unit>()
            val releaseNativeStop = CompletableDeferred<Unit>()
            val runtime =
                TestProxyRuntime().apply {
                    beforeStop = {
                        nativeStopStarted.complete(Unit)
                        releaseNativeStop.await()
                    }
                }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    ripDpiProxyFactory = TestRipDpiProxyFactory { runtime },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    afterForwardingLeaseAcquired = {
                        leaseAcquired.complete(Unit)
                        releaseLeaseAcquisition.await()
                    },
                )
            supervisor.start(RipDpiProxyUIPreferences()) {}

            val poll = async { supervisor.pollTelemetryAndForwardingEvidence() }
            leaseAcquired.await()
            val stopping = async { supervisor.stop() }
            nativeStopStarted.await()
            releaseLeaseAcquisition.complete(Unit)

            assertSame(RuntimeForwardingEvidence.Unavailable, poll.await().forwardingEvidence)
            releaseNativeStop.complete(Unit)
            stopping.await()
        }

    @Test
    fun combinedPollRejectsEvidenceWhenRuntimeStopsMidPoll() =
        runTest {
            val evidencePollStarted = CompletableDeferred<Unit>()
            val releaseEvidencePoll = CompletableDeferred<Unit>()
            val runtime =
                TestProxyRuntime().apply {
                    beforeForwardingEvidence = {
                        evidencePollStarted.complete(Unit)
                        releaseEvidencePoll.await()
                    }
                }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    ripDpiProxyFactory = TestRipDpiProxyFactory { runtime },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            supervisor.start(RipDpiProxyUIPreferences()) {}

            val poll = async { supervisor.pollTelemetryAndForwardingEvidence() }
            evidencePollStarted.await()
            val stopping = async { supervisor.stop() }
            runCurrent()
            releaseEvidencePoll.complete(Unit)

            assertSame(RuntimeForwardingEvidence.Unavailable, poll.await().forwardingEvidence)
            stopping.await()
        }

    @Test
    fun combinedPollPropagatesTelemetryCancellation() =
        runTest {
            val runtime = TestProxyRuntime()
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    ripDpiProxyFactory = TestRipDpiProxyFactory { runtime },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            supervisor.start(RipDpiProxyUIPreferences()) {}
            runtime.telemetryFailure = CancellationException("cancel poll")

            val failure = runCatching { supervisor.pollTelemetryAndForwardingEvidence() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            supervisor.stop()
        }

    @Test
    fun combinedPollRejectsEvidenceWhenRuntimeHandleDisappearsMidPoll() =
        runTest {
            val evidencePollStarted = CompletableDeferred<Unit>()
            val releaseEvidencePoll = CompletableDeferred<Unit>()
            val runtime =
                TestProxyRuntime().apply {
                    forwardingEvidence = ProxyForwardingEvidence.Empty
                    beforeForwardingEvidence = {
                        evidencePollStarted.complete(Unit)
                        releaseEvidencePoll.await()
                    }
                }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    ripDpiProxyFactory = TestRipDpiProxyFactory { runtime },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            supervisor.start(RipDpiProxyUIPreferences()) {}

            val poll = async { supervisor.pollTelemetryAndForwardingEvidence() }
            evidencePollStarted.await()
            supervisor.detach()
            releaseEvidencePoll.complete(Unit)

            assertSame(RuntimeForwardingEvidence.Unavailable, poll.await().forwardingEvidence)
            runtime.complete(0)
        }

    @Test
    fun forwardingEvidencePollIsAvailableAndFailureSafeWhileRunning() =
        runTest {
            val runtime =
                TestProxyRuntime().apply {
                    forwardingEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 55)
                }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    ripDpiProxyFactory = TestRipDpiProxyFactory { runtime },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            supervisor.start(RipDpiProxyUIPreferences()) {}

            assertEquals(55L, supervisor.pollForwardingEvidence()?.upstreamApplicationBytes)
            runtime.forwardingEvidenceFailure = IOException("poll failed")
            assertNull(supervisor.pollForwardingEvidence())

            supervisor.stop()
        }

    @Test
    fun startAndStopManageRuntimeLifecycle() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            val result = supervisor.start(RipDpiProxyUIPreferences()) { exits += it }

            assertSame(runtime, supervisor.runtime)
            assertEquals(1, runtime.updatedSnapshots)
            assertEquals("127.0.0.1", result.endpoint.host)
            assertEquals(1080, result.endpoint.port)

            supervisor.stop()
            advanceUntilIdle()

            assertNull(supervisor.runtime)
            assertEquals(1, runtime.stopCount)
            assertEquals(1, exits.size)
            assertEquals(SupervisorExitCause.ExpectedStop, exits.single())
        }

    @Test
    fun `start returns endpoint with ready time native snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val readySnapshot =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    health = "healthy",
                    listenerAddress = "127.0.0.1:18080",
                    autolearnEnabled = true,
                    capturedAt = 1787231291789069L,
                )
            val runtime = TestProxyRuntime().apply { telemetry = readySnapshot }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = TestRipDpiProxyFactory { runtime },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val result = supervisor.start(RipDpiProxyUIPreferences()) {}

            assertEquals("127.0.0.1", result.endpoint.host)
            assertEquals(18080, result.endpoint.port)
            assertSame(readySnapshot, result.readySnapshot)

            supervisor.stop()
        }

    @Test
    fun startupFailureCleansUpRuntimeFields() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime().apply { startFailure = IOException("proxy boom") }
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val result =
                runCatching {
                    supervisor.start(RipDpiProxyUIPreferences()) {}
                }

            val error = result.exceptionOrNull()
            assertTrue(error is SupervisorStartupFailureException)
            assertTrue((error as SupervisorStartupFailureException).exitCause.throwable is IOException)
            assertNull(supervisor.runtime)
        }

    @Test
    fun nonZeroExitIsReportedToCallback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(RipDpiProxyUIPreferences()) { exits += it }
            runtime.complete(19)
            runCurrent()
            advanceUntilIdle()

            assertEquals(SupervisorExitCause.Crash(19), exits.single())
        }

    @Test
    fun repeatedStartStopRecoversAfterScriptedCrash() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiProxyFactory()
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val exits = mutableListOf<SupervisorExitCause>()
            val scriptedExits = ScriptedSupervisorExitSequence(ScriptedSupervisorExit.Crash(19))

            supervisor.start(RipDpiProxyUIPreferences()) { cause ->
                exits += cause
                supervisor.detach()
            }
            scriptedExits.applyTo(factory.lastRuntime)
            runCurrent()
            advanceUntilIdle()

            supervisor.start(RipDpiProxyUIPreferences()) { exits += it }
            supervisor.stop()
            advanceUntilIdle()

            assertEquals(2, factory.runtimes.size)
            assertEquals(0, factory.runtimes[0].stopCount)
            assertEquals(1, factory.runtimes[1].stopCount)
            assertEquals(
                listOf(
                    SupervisorExitCause.Crash(19),
                    SupervisorExitCause.ExpectedStop,
                ),
                exits,
            )
        }

    @Test
    fun pollTelemetryReturnsEngineErrorWhenRuntimeThrows() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            supervisor.start(RipDpiProxyUIPreferences()) {}
            runtime.telemetryFailure = IOException("telemetry crash")

            val telemetry = supervisor.pollTelemetry()

            assertTrue(telemetry is RuntimeTelemetryOutcome.EngineError)
            assertEquals("telemetry crash", (telemetry as RuntimeTelemetryOutcome.EngineError).message)
        }

    @Test
    fun pollTelemetryReturnsNoDataWhenRuntimeIsMissing() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = TestRipDpiProxyFactory(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val telemetry = supervisor.pollTelemetry()

            assertEquals(RuntimeTelemetryOutcome.NoData, telemetry)
        }

    @Test
    fun stopWithNullRuntimeIsSafeNoOp() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = TestRipDpiProxyFactory(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            supervisor.stop()

            assertNull(supervisor.runtime)
        }

    @Test
    fun networkSnapshotCaptureFailureIsSwallowedAndProxyKeepsRunning() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val snapshotProvider =
                TestNativeNetworkSnapshotProvider(captureFailure = RuntimeException("capture failed"))
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = snapshotProvider,
                )

            supervisor.start(RipDpiProxyUIPreferences()) {}

            assertNotNull(supervisor.runtime)
            assertEquals(0, runtime.updatedSnapshots)
        }

    @Test
    fun awaitReadyFailureCleansUpRuntimeAndPropagatesOriginalError() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val readinessError = IOException("readiness timeout")
            val runtime = TestProxyRuntime().apply { awaitReadyFailure = readinessError }
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val error =
                runCatching {
                    supervisor.start(RipDpiProxyUIPreferences()) {}
                }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error is SupervisorStartupFailureException)
            assertNull(supervisor.runtime)
        }

    @Test
    fun startupFailsWhenReadyProxyDoesNotReportListenerAddress() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime =
                TestProxyRuntime().apply {
                    telemetry = telemetry.copy(listenerAddress = null)
                }
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val error =
                runCatching {
                    supervisor.start(RipDpiProxyUIPreferences()) {}
                }.exceptionOrNull()

            assertTrue(error is SupervisorStartupFailureException)
            assertTrue((error as SupervisorStartupFailureException).exitCause.throwable is IllegalArgumentException)
            assertNull(supervisor.runtime)
            assertEquals(1, runtime.stopCount)
        }

    @Test
    fun resolvedEndpointCarriesVpnSessionCredentials() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val endpoint =
                supervisor
                    .start(
                        RipDpiProxyUIPreferences().withSessionLocalProxyOverrides(
                            listenPortOverride = 0,
                            authToken = TestLocalProxyAuth,
                        ),
                    ) {}
                    .endpoint

            assertEquals("127.0.0.1", endpoint.host)
            assertEquals(1080, endpoint.port)
            assertEquals(VpnLocalProxyUsername, endpoint.username)
            assertEquals(TestLocalProxyAuth, endpoint.password)
        }

    @Test
    fun detachClearsRuntimeWithoutStopping() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            supervisor.start(RipDpiProxyUIPreferences()) {}
            supervisor.detach()

            assertNull(supervisor.runtime)
            assertEquals(0, runtime.stopCount)
        }
}
