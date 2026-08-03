package com.poyka.ripdpi.pcap

import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcapCaptureRuntimeControllerTest {
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
                        PcapStopResult(wasActive = true, files = emptyList())
                    },
                )

            controller.bindTunnel(bridge)
            assertEquals(PcapCaptureRuntimeState.Recording(7L), controller.start())
            assertEquals(PcapCaptureRuntimeState.Idle, controller.stop())

            assertEquals(listOf(41L), startedHandles)
            assertEquals(listOf(41L), stoppedHandles)
        }

    @Test
    fun `writer failure clears recording and is surfaced as a safe code`() =
        runTest {
            val bridge = FakeTunnelBridge(handle = 42L)
            val controller =
                PcapCaptureRuntimeController(
                    startCapture = { 9L },
                    stopCapture = {
                        PcapStopResult(wasActive = true, files = emptyList(), failure = "write")
                    },
                )

            controller.bindTunnel(bridge)
            controller.start()
            val result = controller.stop()

            assertEquals(PcapCaptureRuntimeState.Failed("write"), result)
            assertTrue(controller.state.value !is PcapCaptureRuntimeState.Recording)
        }

    private class FakeTunnelBridge(
        private val handle: Long?,
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

        override suspend fun <T> withSessionHandle(block: suspend (Long) -> T): T? =
            if (handle == null) null else block(handle)
    }
}
