package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Tcp16FatHeaderProbeTest {
    @Test
    fun allOkUsesSixteenHeadRequestsOnOneConnection() =
        runTest {
            MockWebServer().use { server ->
                repeat(16) {
                    server.enqueue(okHeadResponse())
                }
                server.start()

                val result = probe().runWithRttHint(mockTarget(server), hintRttMs = 25)

                assertEquals(Tcp16Verdict.OK, result.verdict)
                assertTrue(result.alive)
                assertNull(result.detectedAtKb)
                assertEquals(1, result.connectionCount)
                assertEquals(16, server.requestCount)

                val requests = (0 until 16).map { server.takeRequest(1, TimeUnit.SECONDS) }
                assertTrue(requests.all { request -> request?.method == "HEAD" })
                assertNull(requests.first()?.headers?.get("X-Pad"))
                requests.drop(1).forEach { request ->
                    assertEquals(4096, request?.headers?.get("X-Pad")?.length)
                }
            }
        }

    @Test
    fun resetOnFifthPaddedRequestReturnsDetectedAtTwentyKilobytes() =
        runTest {
            MockWebServer().use { server ->
                val seen = AtomicInteger()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val requestNumber = seen.incrementAndGet()
                            return if (requestNumber == 6) {
                                MockResponse
                                    .Builder()
                                    .onResponseStart(SocketEffect.CloseSocket())
                                    .build()
                            } else {
                                okHeadResponse()
                            }
                        }
                    }
                server.start()

                val result = probe().runWithRttHint(mockTarget(server), hintRttMs = 25)

                assertEquals(Tcp16Verdict.DETECTED_AT_KB, result.verdict)
                assertTrue(result.alive)
                assertEquals(20, result.detectedAtKb)
                assertEquals("connection closed during padded header train", result.errorDetail)
            }
        }

    @Test
    fun deadTargetReturnsDeadVerdict() =
        runTest {
            val server = MockWebServer()
            server.start()
            val target = mockTarget(server)
            server.close()

            val result = probe().runWithRttHint(target, hintRttMs = 25)

            assertEquals(Tcp16Verdict.DEAD, result.verdict)
            assertTrue(!result.alive)
            assertNotNull(result.errorDetail)
        }

    @Test
    fun reconnectingDuringTrainReturnsInvalidReconnected() =
        runTest {
            val runner =
                object : Tcp16RequestRunner {
                    override fun execute(
                        target: Tcp16Target,
                        request: Tcp16FatHeaderRequest,
                        tracker: Tcp16ConnectionTracker,
                    ): Tcp16FatHeaderResponse {
                        if (request.requestIndex == 0) {
                            tracker.recordConnectStart()
                        }
                        if (request.requestIndex == 3) {
                            tracker.recordConnectStart()
                        }
                        return Tcp16FatHeaderResponse(statusCode = 200, elapsedMs = 12)
                    }
                }
            val probe = Tcp16FatHeaderProbe(requestRunner = runner)

            val result = probe.runWithRttHint(mockTarget(port = 443), hintRttMs = 25)

            assertEquals(Tcp16Verdict.INVALID_RECONNECTED, result.verdict)
            assertEquals(2, result.connectionCount)
        }

    @Test
    fun byAsnAggregatesProviderAndVerdictCounts() {
        val results =
            listOf(
                result(asn = "AS1", provider = "Alpha", verdict = Tcp16Verdict.OK),
                result(asn = "AS1", provider = "Alpha", verdict = Tcp16Verdict.DETECTED_AT_KB),
                result(asn = "AS2", provider = "Beta", verdict = Tcp16Verdict.DEAD),
            )

        val summaries = results.byAsn()

        assertEquals(2, summaries.getValue("AS1").checked)
        assertEquals(1, summaries.getValue("AS1").detected)
        assertEquals("Alpha", summaries.getValue("AS1").providers.single())
        assertEquals(1, summaries.getValue("AS2").dead)
    }

    private fun probe(): Tcp16FatHeaderProbe =
        Tcp16FatHeaderProbe(
            paddingPool = RandomPaddingPool(random = RandomPaddingPool.deterministicRandom(3)),
        )

    private fun mockTarget(server: MockWebServer) = mockTarget(port = server.port)

    private fun mockTarget(port: Int): Tcp16Target =
        Tcp16Target(
            id = "mock",
            asn = "AS64500",
            provider = "Mock ISP",
            ip = "127.0.0.1",
            port = port,
            sni = "example.test",
        )

    private fun okHeadResponse(): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .build()

    private fun result(
        asn: String,
        provider: String,
        verdict: Tcp16Verdict,
    ): Tcp16ProbeResult =
        Tcp16ProbeResult(
            targetId = "$asn-$provider-$verdict",
            asn = asn,
            provider = provider,
            ip = "192.0.2.1",
            port = 443,
            alive = verdict != Tcp16Verdict.DEAD,
            verdict = verdict,
            detectedAtKb = if (verdict == Tcp16Verdict.DETECTED_AT_KB) 20 else null,
            measuredRttMs = null,
            errorDetail = null,
            connectionCount = 1,
        )
}
