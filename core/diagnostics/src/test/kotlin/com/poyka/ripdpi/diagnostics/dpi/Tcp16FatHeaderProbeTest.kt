package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class Tcp16FatHeaderProbeTest {
    @Test
    fun tlsOnlyClientReportsHttpTargetAsUnsupportedBeforeConnecting() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build()
                val probe = Tcp16FatHeaderProbe(requestRunner = OkHttpTcp16RequestRunner(client))

                val result = probe.runTarget(mockTarget(server))

                assertEquals(Tcp16Verdict.UNSUPPORTED, result.verdict)
                assertEquals(0, result.connectionCount)
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun invalidIpAndUnspecifiedNonstandardTransportNeverConnect() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val target = mockTarget(server)

                val invalid = probe().runTarget(target.copy(ip = "example.test"))
                val unsupported = probe().runTarget(target.copy(transport = null))

                assertEquals(Tcp16Verdict.INVALID_TARGET, invalid.verdict)
                assertEquals(Tcp16Verdict.UNSUPPORTED, unsupported.verdict)
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun allOkUsesSixteenHeadRequestsOnOneConnection() =
        runTest {
            MockWebServer().use { server ->
                repeat(16) {
                    server.enqueue(okHeadResponse())
                }
                server.start()

                val result = probe().runTarget(mockTarget(server), responseTimeHintMs = 25)

                assertEquals(Tcp16Verdict.OK, result.verdict)
                assertTrue(result.alive)
                assertEquals(1, result.connectionCount)
                assertEquals(16, server.requestCount)

                val requests = (0 until 16).map { server.takeRequest(1, TimeUnit.SECONDS) }
                assertTrue(requests.all { request -> request?.method == "HEAD" })
                assertTrue(requests.all { request -> request?.headers?.get("Host") == "example.test:${server.port}" })
                assertNull(requests.first()?.headers?.get("X-Pad"))
                requests.drop(1).forEach { request ->
                    assertTrue(requireNotNull(request?.headers?.get("X-Pad")).length in 1..4096)
                }
                assertEquals(
                    requests.drop(1).sumOf { requireNotNull(it?.headers?.get("X-Pad")).length },
                    result.completedPaddingBytes,
                )
            }
        }

    @Test
    fun peerClosePreservesCompletedPaddingAndReadStage() =
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

                val result = probe().runTarget(mockTarget(server), responseTimeHintMs = 25)

                assertEquals(Tcp16Verdict.PADDED_TRAIN_INTERRUPTED, result.verdict)
                assertTrue(result.alive)
                assertEquals(Tcp16Stage.READ_RESPONSE, result.failedStage)
                assertEquals(
                    List(5) { server.takeRequest(1, TimeUnit.SECONDS) }
                        .drop(1)
                        .sumOf { requireNotNull(it?.headers?.get("X-Pad")).length },
                    result.completedPaddingBytes,
                )
            }
        }

    @Test
    fun resetOnFirstPaddedRequestIsAnErrorNotADetection() =
        runTest {
            MockWebServer().use { server ->
                val seen = AtomicInteger()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (seen.incrementAndGet() == 2) {
                                MockResponse
                                    .Builder()
                                    .onResponseStart(SocketEffect.CloseSocket())
                                    .build()
                            } else {
                                okHeadResponse()
                            }
                    }
                server.start()

                val result = probe().runTarget(mockTarget(server), responseTimeHintMs = 25)

                assertEquals(Tcp16Verdict.ERROR, result.verdict)
                assertEquals(0, result.completedPaddingBytes)
            }
        }

    @Test(timeout = 30_000)
    fun cancellingTheRunStopsTheBlockedRequestAtOnce() =
        runBlocking {
            MockWebServer().use { server ->
                val seen = AtomicInteger()
                val stalled = CountDownLatch(1)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (seen.incrementAndGet() == 2) {
                                stalled.countDown()
                                MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()
                            } else {
                                okHeadResponse()
                            }
                    }
                server.start()
                val run =
                    launch(Dispatchers.Default) { probe().runTarget(mockTarget(server), responseTimeHintMs = 10_000) }
                assertTrue(stalled.await(5, TimeUnit.SECONDS))

                val cancelMs = measureTimeMillis { run.cancelAndJoin() }

                assertTrue("cancel took $cancelMs ms", cancelMs < 3_000)
                assertEquals(2, seen.get())
            }
        }

    @Test
    fun deadTargetReturnsDeadVerdict() =
        runTest {
            val server = MockWebServer()
            server.start()
            val target = mockTarget(server)
            server.close()

            val result = probe().runTarget(target, responseTimeHintMs = 25)

            assertEquals(Tcp16Verdict.DEAD, result.verdict)
            assertTrue(!result.alive)
            assertNotNull(result.errorDetail)
        }

    @Test
    fun reconnectingDuringTrainReturnsInvalidReconnected() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .onResponseEnd(SocketEffect.CloseSocket())
                        .build(),
                )
                server.enqueue(okHeadResponse())
                server.start()

                val result = probe().runTarget(mockTarget(server), responseTimeHintMs = 25)

                assertEquals(Tcp16Verdict.INVALID_RECONNECTED, result.verdict)
                assertEquals(2, result.connectionCount)
            }
        }

    @Test
    fun rejectingPaddedRequestRetainsHttpStatus() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(okHeadResponse())
                server.enqueue(MockResponse.Builder().code(431).build())
                server.start()

                val result = probe().runTarget(mockTarget(server))

                assertEquals(Tcp16Verdict.HTTP_REJECTED, result.verdict)
                assertEquals(431, result.httpStatus)
                assertEquals(1, result.connectionCount)
                assertEquals(2, server.requestCount)
                assertEquals(0, result.completedPaddingBytes)
            }
        }

    @Test
    fun connectionCloseHeaderIsASeparateServerOutcome() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Connection", "close")
                        .build(),
                )
                server.start()

                val result = probe().runTarget(mockTarget(server))

                assertEquals(Tcp16Verdict.SERVER_CLOSED, result.verdict)
                assertEquals(200, result.httpStatus)
                assertEquals(1, server.requestCount)
            }
        }

    @Test(timeout = 10_000)
    fun silentPeerHasATimeoutOutcome() =
        runBlocking {
            MockWebServer().use { server ->
                repeat(5) { server.enqueue(okHeadResponse()) }
                server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
                server.start()

                val result = probe().runTarget(mockTarget(server), responseTimeHintMs = 25)

                assertEquals(Tcp16Verdict.TIMED_OUT, result.verdict)
                assertEquals(Tcp16Stage.READ_RESPONSE, result.failedStage)
                assertEquals(6, server.requestCount)
            }
        }

    @Test
    fun okHttpRunnerUsesInjectedBaseClient() =
        runTest {
            MockWebServer().use { server ->
                repeat(16) {
                    server.enqueue(okHeadResponse())
                }
                server.start()
                val baseClient =
                    OkHttpClient
                        .Builder()
                        .addInterceptor(
                            Interceptor { chain ->
                                chain.proceed(
                                    chain
                                        .request()
                                        .newBuilder()
                                        .header("X-Owned-Tls-Client", "diagnostics")
                                        .build(),
                                )
                            },
                        ).build()
                val probe =
                    Tcp16FatHeaderProbe(
                        requestRunner = OkHttpTcp16RequestRunner(baseClient = baseClient),
                    )

                val result = probe.runTarget(mockTarget(server), responseTimeHintMs = 25)

                assertEquals(Tcp16Verdict.OK, result.verdict)
                assertEquals("diagnostics", server.takeRequest(1, TimeUnit.SECONDS)?.headers?.get("X-Owned-Tls-Client"))
            }
        }

    @Test
    fun targetRunLeavesNoKeepAliveSocketInTheSharedClientPool() =
        runTest {
            MockWebServer().use { server ->
                repeat(16) { server.enqueue(okHeadResponse()) }
                server.start()
                val sharedPool = ConnectionPool(5, 5, TimeUnit.MINUTES)
                val okHttpRunner =
                    OkHttpTcp16RequestRunner(baseClient = OkHttpClient.Builder().connectionPool(sharedPool).build())
                var targetTracker: Tcp16ConnectionTracker? = null
                val runner =
                    object : Tcp16RequestRunner {
                        override fun execute(
                            target: Tcp16Target,
                            request: Tcp16FatHeaderRequest,
                            tracker: Tcp16ConnectionTracker,
                        ): Tcp16FatHeaderResponse =
                            okHttpRunner.execute(target, request, tracker).also {
                                targetTracker =
                                    tracker
                            }
                    }

                val result =
                    Tcp16FatHeaderProbe(
                        requestRunner = runner,
                    ).runTarget(mockTarget(server), responseTimeHintMs = 25)

                assertEquals(Tcp16Verdict.OK, result.verdict)
                assertEquals(1, result.connectionCount)
                assertEquals(0, sharedPool.connectionCount())
                assertEquals(0, targetTracker?.connectionPool?.connectionCount())
            }
        }

    @Test
    fun randomHostnameKeepsOneRandomHostAndOneConnectionForTheTrain() =
        runTest {
            MockWebServer().use { server ->
                repeat(16) {
                    server.enqueue(okHeadResponse())
                }
                server.start()

                val result = probe().runTarget(mockTarget(server), responseTimeHintMs = 25, randomHostname = true)

                val hostHeaders =
                    (0 until 16).mapNotNull {
                        val request = server.takeRequest(1, TimeUnit.SECONDS)
                        request?.headers?.get("Host")?.substringBefore(':')
                    }
                assertEquals(Tcp16Verdict.OK, result.verdict)
                assertEquals(16, hostHeaders.size)
                assertEquals(1, hostHeaders.toSet().size)
                assertTrue(hostHeaders.first() != "example.test")
                assertEquals(1, result.connectionCount)
                assertEquals(hostHeaders, result.requestedHosts)
            }
        }

    @Test
    fun resultsCarryDiagnosticsTlsClientState() =
        runTest {
            val tlsState =
                DiagnosticsTlsClientState(
                    profileId = "chrome_stable",
                    nativeOwnedTlsAvailable = false,
                    fallbackActive = true,
                    fallbackReason = "android_okhttp_fingerprint_template",
                )
            val probe =
                Tcp16FatHeaderProbe(
                    requestRunner =
                        object : Tcp16RequestRunner {
                            override fun execute(
                                target: Tcp16Target,
                                request: Tcp16FatHeaderRequest,
                                tracker: Tcp16ConnectionTracker,
                            ): Tcp16FatHeaderResponse = Tcp16FatHeaderResponse(statusCode = 200, elapsedMs = 10)
                        },
                    tlsClientStateProvider = { tlsState },
                )

            val result = probe.runTarget(mockTarget(port = 443), responseTimeHintMs = 25)

            assertEquals(tlsState, result.tlsClientState)
        }

    @Test
    fun withConcurrencyPreservesDiagnosticsTlsClientStateProvider() =
        runTest {
            val tlsState =
                DiagnosticsTlsClientState(
                    profileId = "chrome_stable",
                    nativeOwnedTlsAvailable = false,
                    fallbackActive = true,
                    fallbackReason = "android_okhttp_fingerprint_template",
                )
            val probe =
                Tcp16FatHeaderProbe(
                    requestRunner =
                        object : Tcp16RequestRunner {
                            override fun execute(
                                target: Tcp16Target,
                                request: Tcp16FatHeaderRequest,
                                tracker: Tcp16ConnectionTracker,
                            ): Tcp16FatHeaderResponse = Tcp16FatHeaderResponse(statusCode = 200, elapsedMs = 10)
                        },
                    tlsClientStateProvider = { tlsState },
                ).withConcurrency(2)

            val result = probe.runTarget(mockTarget(port = 443), responseTimeHintMs = 25)

            assertEquals(tlsState, result.tlsClientState)
        }

    @Test
    fun byAsnAggregatesProviderAndVerdictCounts() {
        val results =
            listOf(
                result(asn = "AS1", provider = "Alpha", verdict = Tcp16Verdict.OK),
                result(asn = "AS1", provider = "Alpha", verdict = Tcp16Verdict.PADDED_TRAIN_INTERRUPTED),
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
            transport = if (port == 443) Tcp16Transport.HTTPS else Tcp16Transport.HTTP,
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
            completedPaddingBytes = if (verdict == Tcp16Verdict.PADDED_TRAIN_INTERRUPTED) 20 * 1_024 else 0,
            meanResponseMs = null,
            errorDetail = null,
            connectionCount = 1,
        )
}
