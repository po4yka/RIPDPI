package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VpnTunnelForwardingEvidenceTest {
    @Test
    fun combinedPollRejectsEvidenceWhenForwardingIsRetainedFailClosedMidPoll() =
        runTest {
            val evidencePollStarted = CompletableDeferred<Unit>()
            val releaseEvidencePoll = CompletableDeferred<Unit>()
            val bridge =
                TestTun2SocksBridge().apply {
                    beforeForwardingEvidence = {
                        evidencePollStarted.complete(Unit)
                        releaseEvidencePoll.await()
                    }
                }
            val runtime = startedRuntime(bridge)

            val poll = async { runtime.pollTelemetryAndForwardingEvidence() }
            evidencePollStarted.await()
            assertTrue(runtime.retainFailClosedBarrier())
            releaseEvidencePoll.complete(Unit)

            assertSame(RuntimeForwardingEvidence.Unavailable, poll.await().forwardingEvidence)
            runtime.stop()
        }

    @Test
    fun combinedPollPropagatesTunnelTelemetryCancellation() =
        runTest {
            val bridge = TestTun2SocksBridge().apply { telemetryFailure = CancellationException("cancel poll") }
            val runtime = startedRuntime(bridge)

            val failure = runCatching { runtime.pollTelemetryAndForwardingEvidence() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            runtime.stop()
        }

    @Test
    fun forwardingEvidencePollIsAvailableAndFailureSafeWhileRunning() =
        runTest {
            val bridge =
                TestTun2SocksBridge().apply {
                    forwardingEvidence = TunForwardingEvidence(tunReadBytes = 22, tunWriteBytes = 11)
                }
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )
            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint =
                    LocalProxyEndpoint(
                        host = "127.0.0.1",
                        port = 18_080,
                    ),
            )

            val combinedPoll = runtime.pollTelemetryAndForwardingEvidence()
            assertTrue(combinedPoll.telemetry is RuntimeTelemetryOutcome.Snapshot)
            val available = combinedPoll.forwardingEvidence as RuntimeForwardingEvidence.Available
            assertEquals(22L, available.value.tunReadBytes)
            assertEquals(11L, available.value.tunWriteBytes)
            bridge.forwardingEvidenceFailure = IOException("poll failed")
            assertNull(runtime.pollForwardingEvidence())
            assertSame(
                RuntimeForwardingEvidence.Unavailable,
                runtime.pollTelemetryAndForwardingEvidence().forwardingEvidence,
            )

            runtime.stop()
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.startedRuntime(
        bridge: TestTun2SocksBridge,
    ): VpnTunnelRuntime =
        VpnTunnelRuntime(
            vpnHost = TestVpnServiceHost(this),
            appSettingsRepository = TestAppSettingsRepository(),
            proxyGroupRepository = TestProxyGroupRepository(),
            tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
            vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
        ).also { runtime ->
            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint = LocalProxyEndpoint(host = "127.0.0.1", port = 18_080),
            )
        }
}
