package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException

class PluggableTransportReachabilityProbeTest {
    @Test
    fun allThreePtsReachableReturnsAllOk() =
        runTest {
            val result = probe().run()

            assertTrue(result.obfs4 is PtVerdict.PtOk)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
            assertEquals(4, result.traces.size)
        }

    @Test
    fun obfs4BridgeBlockedIsolated() =
        runTest {
            val result =
                probe(
                    obfs4 = { PtProbeTrace("obfs4", "bridge", false, 10, "rst") },
                ).run()

            assertTrue(result.obfs4 is PtVerdict.PtBridgeBlocked)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
        }

    @Test
    fun snowflakeBrokerUnreachableReturnsBrokerBlocked() =
        runTest {
            val result =
                probe(
                    snowflakeBroker = { PtProbeTrace("snowflake_broker", "broker", false, 10, "503") },
                ).run()

            assertTrue(result.snowflake is PtVerdict.PtBrokerBlocked)
        }

    @Test
    fun snowflakeBrokerOkButStunBlockedReturnsStunBlocked() =
        runTest {
            val result =
                probe(
                    snowflakeStun = { PtProbeTrace("snowflake_stun", "stun", false, 10, "timeout") },
                ).run()

            assertTrue(result.snowflake is PtVerdict.PtStunBlocked)
        }

    @Test
    fun meekAllFrontsBlocked() =
        runTest {
            val result =
                probe(
                    meek = { PtProbeTrace("meek", "front", false, 10, "timeout") },
                ).run()

            assertTrue(result.meek is PtVerdict.PtFrontBlocked)
        }

    @Test
    fun onePtFailureDoesNotCancelOthers() =
        runTest {
            val result =
                probe(
                    obfs4 = { throw IllegalStateException("boom") },
                ).run()

            assertTrue(result.obfs4 is PtVerdict.PtError)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
        }

    @Test
    fun ptSubprobesRunInParallel() =
        runTest {
            val result =
                probe(
                    obfs4 = {
                        delay(50)
                        PtProbeTrace("obfs4", "bridge", true, 50)
                    },
                    snowflakeBroker = {
                        delay(50)
                        PtProbeTrace("snowflake_broker", "broker", true, 50)
                    },
                    meek = {
                        delay(50)
                        PtProbeTrace("meek", "front", true, 50)
                    },
                ).run(timeoutMs = 1_000)

            assertTrue(result.obfs4 is PtVerdict.PtOk)
            assertTrue(result.snowflake is PtVerdict.PtOk)
            assertTrue(result.meek is PtVerdict.PtOk)
        }

    @Test
    fun obfs4BridgeRespondsWithAnyByteReturnsOk() =
        runTest {
            val probe =
                Obfs4ReachabilityProbe(
                    bridges = listOf(PtEndpoint("203.0.113.1", 443)),
                    socketFactory = { FakeSocket(input = byteArrayOf(1)) },
                    randomBytes = { size -> ByteArray(size) { 7 } },
                )

            val trace = probe.run(timeoutMs = 1_000)

            assertTrue(trace.ok)
            assertEquals("203.0.113.1:443", trace.target)
        }

    @Test
    fun obfs4BridgeTcpResetTriesAllBridgesBeforeBlockedTrace() =
        runTest {
            val sockets = mutableListOf<FakeSocket>()
            val probe =
                Obfs4ReachabilityProbe(
                    bridges =
                        listOf(
                            PtEndpoint("203.0.113.1", 443),
                            PtEndpoint("203.0.113.2", 443),
                            PtEndpoint("203.0.113.3", 443),
                        ),
                    socketFactory = {
                        FakeSocket(connectError = IOException("rst")).also(sockets::add)
                    },
                    randomBytes = { size -> ByteArray(size) },
                )

            val trace = probe.run(timeoutMs = 1_000)

            assertEquals(false, trace.ok)
            assertEquals(3, sockets.size)
        }

    @Test
    fun snowflakeBrokerHttpClientErrorStillCountsReachable() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse(code = 400, body = "{}"))
                server.start()
                val trace = SnowflakeBrokerReachabilityProbe(brokerUrl = server.url("/").toString()).run(1_000)

                assertTrue(trace.ok)
            }
        }

    @Test
    fun snowflakeBrokerServerErrorReturnsBlockedTrace() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse(code = 503, body = "unavailable"))
                server.start()
                val trace = SnowflakeBrokerReachabilityProbe(brokerUrl = server.url("/").toString()).run(1_000)

                assertEquals(false, trace.ok)
                assertEquals("503", trace.error)
            }
        }

    @Test
    fun snowflakeStunResponseHeaderReturnsOk() =
        runTest {
            val trace =
                SnowflakeStunReachabilityProbe(
                    socketFactory = { FakeDatagramSocket(responseLength = 20) },
                    transactionId = { ByteArray(12) },
                ).run(1_000)

            assertTrue(trace.ok)
        }

    @Test
    fun snowflakeStunTimeoutReturnsBlockedTrace() =
        runTest {
            val trace =
                SnowflakeStunReachabilityProbe(
                    socketFactory = { FakeDatagramSocket(timeout = true) },
                    transactionId = { ByteArray(12) },
                ).run(1_000)

            assertEquals(false, trace.ok)
            assertEquals("timeout", trace.error)
        }

    @Test
    fun meekOneFrontOkReturnsOk() =
        runTest {
            MockWebServer().use { blocked ->
                MockWebServer().use { reachable ->
                    blocked.enqueue(MockResponse(code = 503))
                    reachable.enqueue(MockResponse(code = 204))
                    blocked.start()
                    reachable.start()
                    val trace =
                        MeekReachabilityProbe(
                            fronts = listOf(blocked.url("/").toString(), reachable.url("/").toString()),
                        ).run(1_000)

                    assertTrue(trace.ok)
                    assertEquals(reachable.url("/").toString(), trace.target)
                }
            }
        }

    @Test
    fun meekAllFrontsBlockedReturnsBlockedTrace() =
        runTest {
            MockWebServer().use { first ->
                MockWebServer().use { second ->
                    first.enqueue(MockResponse(code = 503))
                    second.enqueue(MockResponse(code = 404))
                    first.start()
                    second.start()
                    val trace =
                        MeekReachabilityProbe(
                            fronts = listOf(first.url("/").toString(), second.url("/").toString()),
                        ).run(1_000)

                    assertEquals(false, trace.ok)
                }
            }
        }

    private fun probe(
        obfs4: suspend () -> PtProbeTrace = { PtProbeTrace("obfs4", "bridge", true, 10) },
        snowflakeBroker: suspend () -> PtProbeTrace = { PtProbeTrace("snowflake_broker", "broker", true, 10) },
        snowflakeStun: suspend () -> PtProbeTrace = { PtProbeTrace("snowflake_stun", "stun", true, 10) },
        meek: suspend () -> PtProbeTrace = { PtProbeTrace("meek", "front", true, 10) },
    ): PluggableTransportReachabilityProbe =
        PluggableTransportReachabilityProbe(
            obfs4Probe = FakePtSubprobe(obfs4),
            snowflakeBrokerProbe = FakePtSubprobe(snowflakeBroker),
            snowflakeStunProbe = FakePtSubprobe(snowflakeStun),
            meekProbe = FakePtSubprobe(meek),
        )

    private class FakePtSubprobe(
        private val result: suspend () -> PtProbeTrace,
    ) : PtSubprobe {
        override suspend fun run(timeoutMs: Long): PtProbeTrace = result()
    }

    private class FakeSocket(
        private val input: ByteArray = byteArrayOf(),
        private val connectError: IOException? = null,
    ) : Socket() {
        val output = ByteArrayOutputStream()

        override fun connect(
            endpoint: SocketAddress?,
            timeout: Int,
        ) {
            connectError?.let { throw it }
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(input)

        override fun getOutputStream(): OutputStream = output
    }

    private class FakeDatagramSocket(
        private val responseLength: Int = 0,
        private val timeout: Boolean = false,
    ) : DatagramSocket() {
        override fun receive(packet: DatagramPacket) {
            if (timeout) {
                throw SocketTimeoutException("timeout")
            }
            packet.length = responseLength
        }
    }
}
