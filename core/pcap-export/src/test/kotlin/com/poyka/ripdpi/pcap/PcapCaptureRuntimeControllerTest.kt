package com.poyka.ripdpi.pcap

import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PcapCaptureRuntimeControllerTest {
    @Test
    fun `capacity publishes the capture receipt without a user stop`() =
        runTest {
            var terminalReady = false
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { 7L },
                    stopCapture = { error("Poll must not stop an active writer") },
                    pollCompletion = {
                        if (terminalReady) {
                            PcapStopResult(
                                true,
                                emptyList(),
                                totalDrops = 2L,
                                limitReached = true,
                            )
                        } else {
                            null
                        }
                    },
                    monitorScope = backgroundScope,
                )
            controller.bindTunnel(FakeTunnelBridge(41L))
            controller.start()
            advanceTimeBy(300L)
            assertEquals(PcapCaptureRuntimeState.Recording(7L), controller.state.value)

            terminalReady = true
            advanceTimeBy(300L)
            val expected =
                PcapCaptureRuntimeState.Completed(
                    PcapCaptureCompletionReceipt(7L, 2L, PcapCaptureOutcome.CapturedSeparate, limitReached = true),
                )
            assertEquals(expected, controller.state.value)
            assertEquals(expected, controller.stopIfOwned(7L))
        }

    @Test
    fun `owner settles an automatic completion after manual recording starts`() =
        runTest {
            var nextId = 7L
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { nextId++ },
                    stopCapture = { PcapStopResult(true, emptyList()) },
                    pollCompletion = { PcapStopResult(true, emptyList(), totalDrops = 2L, limitReached = true) },
                    monitorScope = backgroundScope,
                )
            controller.bindTunnel(FakeTunnelBridge(41L))
            assertEquals(
                PcapCaptureLeaseAcquisition.Acquired(PcapCaptureRuntimeState.Recording(7L)),
                controller.startIfIdle(),
            )
            advanceTimeBy(300L)
            assertEquals(PcapCaptureRuntimeState.Recording(8L), controller.start())

            assertEquals(
                PcapCaptureRuntimeState.Completed(
                    PcapCaptureCompletionReceipt(7L, 2L, PcapCaptureOutcome.CapturedSeparate, limitReached = true),
                ),
                controller.stopIfOwned(7L),
            )
            assertEquals(PcapCaptureRuntimeState.Recording(8L), controller.state.value)
            assertEquals(null, controller.stopIfOwned(7L))
        }

    @Test
    fun `monitor finishes with failure after its native handle retires`() =
        runTest {
            val bridge = FakeTunnelBridge(41L)
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { 7L },
                    stopCapture = { error("The retired handle cannot be stopped") },
                    pollCompletion = { error("The retired handle cannot be polled") },
                    monitorScope = backgroundScope,
                )
            controller.bindTunnel(bridge)
            controller.start()
            bridge.handle = null
            advanceTimeBy(300L)

            assertEquals(
                PcapCaptureRuntimeState.Failed(
                    PcapCaptureCompletionReceipt(7L, null, PcapCaptureOutcome.Failed),
                    "session_unavailable",
                ),
                controller.state.value,
            )
        }

    @Test
    fun `native timeout leaves stop retryable until completion is collected`() =
        runTest {
            var terminalReady = false
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { 7L },
                    stopCapture = { PcapStopResult(true, emptyList(), failure = "timeout") },
                    pollCompletion = { if (terminalReady) PcapStopResult(true, emptyList()) else null },
                    monitorScope = backgroundScope,
                )
            controller.bindTunnel(FakeTunnelBridge(41L))
            controller.start()
            assertEquals(PcapCaptureRuntimeState.StopFailed(7L, "timeout"), controller.stop())

            terminalReady = true
            advanceTimeBy(300L)
            assertEquals(
                PcapCaptureRuntimeState.Completed(
                    PcapCaptureCompletionReceipt(7L, 0L, PcapCaptureOutcome.CapturedSeparate),
                ),
                controller.state.value,
            )
        }

    @Test
    fun `cancellation after native start still publishes a stoppable capture`() =
        runTest {
            listOf(false, true).forEach { acquireLease ->
                lateinit var startJob: Job
                val stoppedHandles = mutableListOf<Long>()
                val controller =
                    PcapCaptureRuntimeController(
                        startCapture = {
                            withContext(StandardTestDispatcher(testScheduler)) {
                                startJob.cancel()
                                7L
                            }
                        },
                        stopCapture = { handle ->
                            stoppedHandles += handle
                            PcapStopResult(true, emptyList())
                        },
                    )
                controller.bindTunnel(FakeTunnelBridge(41L))
                startJob =
                    launch {
                        if (acquireLease) controller.startIfIdle() else controller.start()
                    }
                startJob.join()

                assertEquals(PcapCaptureRuntimeState.Recording(7L), controller.state.value)
                controller.stop()
                assertEquals(listOf(41L), stoppedHandles)
            }
        }

    @Test
    fun `start and stop use the live tunnel handle`() =
        runTest {
            val bridge = FakeTunnelBridge(handle = 41L)
            val startedHandles = mutableListOf<Long>()
            val stoppedHandles = mutableListOf<Long>()
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { handle ->
                        startedHandles += handle
                        7L
                    },
                    stopCapture = { handle ->
                        stoppedHandles += handle
                        PcapStopResult(wasActive = true, files = emptyList(), totalDrops = 3L)
                    },
                )

            controller.bindTunnel(bridge)
            assertEquals(PcapCaptureRuntimeState.Recording(7L), controller.start())
            assertEquals(
                PcapCaptureRuntimeState.Completed(
                    PcapCaptureCompletionReceipt(7L, 3L, PcapCaptureOutcome.CapturedSeparate),
                ),
                controller.stop(),
            )

            assertEquals(listOf(41L), startedHandles)
            assertEquals(listOf(41L), stoppedHandles)
        }

    @Test
    fun `writer failure finalizes a path-free receipt with exact drops`() =
        runTest {
            val bridge = FakeTunnelBridge(handle = 42L)
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { 9L },
                    stopCapture = {
                        PcapStopResult(wasActive = true, files = emptyList(), totalDrops = 5L, failure = "write")
                    },
                )

            controller.bindTunnel(bridge)
            controller.start()
            val result = controller.stop()

            assertEquals(
                PcapCaptureRuntimeState.Failed(
                    receipt = PcapCaptureCompletionReceipt(9L, 5L, PcapCaptureOutcome.Failed),
                    code = "write",
                ),
                result,
            )
            assertTrue(controller.state.value !is PcapCaptureRuntimeState.Recording)
        }

    @Test
    fun `retiring a tunnel whose stop throws finalizes capture so the next tunnel can bind`() =
        runTest {
            val retired = FakeTunnelBridge(handle = 41L)
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { 7L },
                    stopCapture = { throw IllegalStateException("native panic in pcap stop") },
                )
            controller.bindTunnel(retired)
            controller.start()

            controller.retireTunnel(retired)
            controller.bindTunnel(FakeTunnelBridge(handle = 42L))

            assertEquals(
                PcapCaptureRuntimeState.Failed(
                    receipt = PcapCaptureCompletionReceipt(7L, null, PcapCaptureOutcome.Failed),
                    code = "stop_failed",
                ),
                controller.state.value,
            )
        }

    @Test
    fun `stop failure preserves its capture lease for retry`() =
        runTest {
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { 12L },
                    stopCapture = { error("transient stop") },
                )
            controller.bindTunnel(FakeTunnelBridge(44L))
            controller.start()

            assertEquals(PcapCaptureRuntimeState.StopFailed(12L, "stop_failed"), controller.stop())
            assertEquals(null, controller.stopIfOwned(13L))
        }

    @Test
    fun `idle acquisition never claims an existing capture`() =
        runTest {
            val controller = PcapCaptureRuntimeController({ 16L }, { PcapStopResult(true, emptyList()) })
            controller.bindTunnel(FakeTunnelBridge(45L))
            assertEquals(
                PcapCaptureLeaseAcquisition.Acquired(PcapCaptureRuntimeState.Recording(16L)),
                controller.startIfIdle(),
            )
            assertEquals(
                PcapCaptureLeaseAcquisition.AlreadyActive(PcapCaptureRuntimeState.Recording(16L)),
                controller.startIfIdle(),
            )
        }

    @Test
    fun `owned stop replays only its matching terminal receipt`() =
        runTest {
            val controller =
                PcapCaptureRuntimeController({ 17L }, { PcapStopResult(true, emptyList(), totalDrops = 1L) })
            controller.bindTunnel(FakeTunnelBridge(46L))
            controller.startIfIdle()

            val terminal = controller.stopIfOwned(17L)

            assertEquals(terminal, controller.stopIfOwned(17L))
            assertEquals(null, controller.stopIfOwned(18L))
        }

    @Test
    fun `tunnel retirement terminalizes its active capture`() =
        runTest {
            val bridge = FakeTunnelBridge(47L)
            val controller =
                PcapCaptureRuntimeController({ 18L }, { PcapStopResult(true, emptyList(), totalDrops = 2L) })
            controller.bindTunnel(bridge)
            controller.startIfIdle()

            controller.retireTunnel(bridge)

            assertEquals(
                PcapCaptureRuntimeState.Completed(
                    PcapCaptureCompletionReceipt(18L, 2L, PcapCaptureOutcome.CapturedSeparate),
                ),
                controller.state.value,
            )
        }

    private class FakeTunnelBridge(
        var handle: Long?,
    ) : Tun2SocksBridge {
        override suspend fun start(
            config: Tun2SocksConfig,
            tunFd: Int,
            flowAttributionBridge: Any?,
        ) = Unit

        override suspend fun stop() = Unit

        override suspend fun stats(): TunnelStats = TunnelStats()

        override suspend fun telemetry(): NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "test")

        override suspend fun forwardingEvidence(): TunForwardingEvidence = TunForwardingEvidence()

        override suspend fun <T> withSessionHandle(block: suspend (Long) -> T): T? = handle?.let { block(it) }
    }
}
