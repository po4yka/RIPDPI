package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class PluggableTransportReachabilityProbeTest {
    @Test
    fun everyLegRunsOnTheIoDispatcher() =
        runTest {
            val ioThreads = Executors.newSingleThreadExecutor { task -> Thread(task, PtIoThreadName) }
            try {
                val threads = Collections.synchronizedList(mutableListOf<String>())
                val leg =
                    PtSubprobe {
                        threads += Thread.currentThread().name.substringBefore(" @")
                        PtProbeTrace("bridge", PtProbeStatus.CONNECTED, 1)
                    }

                PluggableTransportReachabilityProbe(leg, leg, leg, leg, ioThreads.asCoroutineDispatcher()).run()

                assertEquals(List(4) { PtIoThreadName }, threads.toList())
            } finally {
                ioThreads.shutdown()
            }
        }

    @Test
    fun unexpectedLegFailureKeepsOtherEndpointResults() =
        runTest {
            val broker = PtProbeTrace("broker", PtProbeStatus.HTTP_RESPONSE, 10, 404)
            val stun = PtProbeTrace("stun", PtProbeStatus.BINDING_RESPONSE, 10)
            val front = PtProbeTrace("front", PtProbeStatus.HTTP_RESPONSE, 10, 503)
            val result =
                PluggableTransportReachabilityProbe(
                    obfs4Probe = PtSubprobe { throw IOException("connect") },
                    snowflakeBrokerProbe = PtSubprobe { broker },
                    snowflakeStunProbe = PtSubprobe { stun },
                    meekProbe = PtSubprobe { front },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).run()

            assertEquals(PtProbeStatus.NETWORK_ERROR, result.obfs4Tcp.status)
            assertEquals(broker, result.snowflakeBroker)
            assertEquals(stun, result.snowflakeStun)
            assertEquals(front, result.meekHead)
        }

    @Test
    fun ptSubprobesRunInParallel() =
        runTest {
            val leg =
                PtSubprobe {
                    delay(50)
                    PtProbeTrace("bridge", PtProbeStatus.CONNECTED, 50)
                }
            PluggableTransportReachabilityProbe(leg, leg, leg, leg, StandardTestDispatcher(testScheduler)).run()

            assertEquals(50L, currentTime)
        }

    @Test
    fun obfs4ConnectsAndClosesWithoutSendingTransportBytes() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 2_000
                val peer =
                    async(Dispatchers.IO) {
                        server.accept().use {
                            it.soTimeout = 2_000
                            it.getInputStream().read()
                        }
                    }
                val endpoint = PtEndpoint(server.inetAddress.hostAddress!!, server.localPort)
                val trace = Obfs4ReachabilityProbe(bridges = listOf(endpoint)).run(timeoutMs = 1_000)

                assertEquals(PtProbeStatus.CONNECTED, trace.status)
                assertEquals(endpoint.toString(), trace.target)
                assertEquals(-1, peer.await())
            }
        }

    @Test
    fun placeholderOnlyObfs4BridgesAreReportedAsNotConfigured() =
        runTest {
            val trace =
                Obfs4ReachabilityProbe(
                    bridges = listOf(PtEndpoint("192.0.2.10", 443), PtEndpoint("203.0.113.5", 443)),
                    socketFactory = { error("Documentation endpoint must never open a socket") },
                ).run(timeoutMs = 1_000)

            assertEquals(PtProbeStatus.NOT_CONFIGURED, trace.status)
        }

    @Test
    fun blackholedObfs4BridgesShareOneTimeoutBudget() =
        runTest {
            val connectTimeouts = mutableListOf<Int>()
            val probe =
                Obfs4ReachabilityProbe(
                    bridges = (1..4).map { index -> PtEndpoint("10.0.0.$index", 443) },
                    socketFactory = {
                        object : Socket() {
                            override fun connect(
                                endpoint: SocketAddress?,
                                timeout: Int,
                            ) {
                                connectTimeouts += timeout
                                Thread.sleep(timeout.toLong())
                                throw SocketTimeoutException("connect timed out")
                            }
                        }
                    },
                )

            val trace = probe.run(timeoutMs = 300)

            assertEquals(PtProbeStatus.TIMEOUT, trace.status)
            assertTrue("connect timeouts $connectTimeouts", connectTimeouts.sum() <= 300)
        }

    @Test
    fun snowflakeBrokerReportsHttpErrorsAsEndpointResponses() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val broker = SnowflakeBrokerReachabilityProbe(brokerUrl = server.url("/").toString())

                for (code in listOf(404, 503)) {
                    server.enqueue(MockResponse(code = code))
                    val trace = broker.run(1_000)

                    assertEquals(PtProbeStatus.HTTP_RESPONSE, trace.status)
                    assertEquals(code, trace.httpStatusCode)
                    assertEquals("POST", server.takeRequest().method)
                }
            }
        }

    @Test
    fun snowflakeStunMatchingBindingResponseReturnsOk() =
        runTest {
            val trace =
                SnowflakeStunReachabilityProbe(
                    endpoint = PtEndpoint("127.0.0.1", 3478),
                    socketFactory = { FakeDatagramSocket() },
                    transactionId = { ByteArray(12) },
                ).run(1_000)

            assertEquals(PtProbeStatus.BINDING_RESPONSE, trace.status)
        }

    @Test
    fun snowflakeStunRejectsUnrelatedOrMalformedDatagrams() =
        runTest {
            val malformedResponses =
                listOf<(ByteArray) -> ByteArray>(
                    { it.copyOf(19) },
                    { it.copyOf().also { bytes -> bytes[0] = 0 } },
                    { it.copyOf().also { bytes -> bytes[4] = 0 } },
                    { it.copyOf().also { bytes -> bytes[8] = 1 } },
                    { it.copyOf().also { bytes -> bytes[3] = 4 } },
                )
            malformedResponses.forEach { mutate ->
                val trace =
                    SnowflakeStunReachabilityProbe(
                        endpoint = PtEndpoint("127.0.0.1", 3478),
                        socketFactory = { FakeDatagramSocket(mutateResponse = mutate) },
                        transactionId = { ByteArray(12) },
                    ).run(1_000)

                assertEquals(PtProbeStatus.INVALID_RESPONSE, trace.status)
            }
        }

    @Test
    fun snowflakeStunRejectsResponseFromOtherEndpoint() =
        runTest {
            listOf(InetSocketAddress("127.0.0.2", 3478), InetSocketAddress("127.0.0.1", 3479)).forEach { source ->
                val trace =
                    SnowflakeStunReachabilityProbe(
                        endpoint = PtEndpoint("127.0.0.1", 3478),
                        socketFactory = { FakeDatagramSocket(sourceOverride = source) },
                        transactionId = { ByteArray(12) },
                    ).run(1_000)

                assertEquals(PtProbeStatus.INVALID_RESPONSE, trace.status)
            }
        }

    @Test
    fun snowflakeStunTimeoutIsDistinctFromAnInvalidResponse() =
        runTest {
            val trace =
                SnowflakeStunReachabilityProbe(
                    socketFactory = { FakeDatagramSocket(timeout = true) },
                    transactionId = { ByteArray(12) },
                ).run(1_000)

            assertEquals(PtProbeStatus.TIMEOUT, trace.status)
        }

    @Test
    fun meekHeadRecordsRedirectWithoutFollowingIt() =
        runTest {
            MockWebServer().use { front ->
                MockWebServer().use { other ->
                    front.start()
                    other.start()
                    front.enqueue(
                        MockResponse
                            .Builder()
                            .code(302)
                            .addHeader("Location", other.url("/other").toString())
                            .build(),
                    )
                    other.enqueue(MockResponse(code = 204))
                    val url = front.url("/front").toString()
                    val trace = MeekReachabilityProbe(fronts = listOf(url)).run(1_000)

                    assertEquals(PtProbeStatus.HTTP_RESPONSE, trace.status)
                    assertEquals(302, trace.httpStatusCode)
                    assertEquals(url, trace.target)
                    assertEquals("HEAD", front.takeRequest().method)
                    assertEquals(0, other.requestCount)
                }
            }
        }

    @Test
    fun emptyMeekTargetsAreNotConfigured() =
        runTest {
            val trace = MeekReachabilityProbe(fronts = emptyList()).run(1_000)

            assertEquals(PtProbeStatus.NOT_CONFIGURED, trace.status)
        }

    private class FakeDatagramSocket(
        private val mutateResponse: (ByteArray) -> ByteArray = { it },
        private val sourceOverride: InetSocketAddress? = null,
        private val timeout: Boolean = false,
    ) : DatagramSocket() {
        private lateinit var request: DatagramPacket

        override fun send(packet: DatagramPacket) {
            request =
                DatagramPacket(
                    packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                    packet.length,
                )
            request.socketAddress = packet.socketAddress
        }

        override fun receive(packet: DatagramPacket) {
            if (timeout) {
                throw SocketTimeoutException("timeout")
            }
            val bytes = ByteArray(20)
            bytes[0] = 0x01
            bytes[1] = 0x01
            request.data.copyInto(bytes, destinationOffset = 4, startIndex = 4, endIndex = 20)
            packet.data = mutateResponse(bytes)
            packet.socketAddress = sourceOverride ?: request.socketAddress
        }
    }
}

private const val PtIoThreadName = "pt-probe-io"
