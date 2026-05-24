package com.poyka.ripdpi.diagnostics.replay

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProbeReplayServiceTest {
    /**
     * HTTP-only harness per the spec's degradation clause: TLS steps are
     * not emitted in the success path because the request is plain HTTP.
     * Tests 1 and 3 assert the subset {DnsResolve, TcpOpen, FirstByte};
     * test 2 (connection reset) uses HTTP because OkHttp reports the
     * RST on the response read, exercising the ConnectionReset
     * classification independently of TLS.
     */

    @Test
    fun successfulProbe_emitsDnsTcpFirstByteThenSuccessFinished() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(200).build())
                server.start()

                val service = DefaultProbeReplayService(httpFactory(server))
                val events =
                    service
                        .run(ReplayProbeRequest(domain = "example.test", strategyId = "split", timeoutMs = 5_000))
                        .toList()

                val completed = events.filterIsInstance<ReplayStepEvent.StepCompleted>().map { it.step }
                assertTrue("expected DnsResolve completed in $completed", ReplayStepKind.DnsResolve in completed)
                assertTrue("expected TcpOpen completed in $completed", ReplayStepKind.TcpOpen in completed)
                assertTrue("expected FirstByte completed in $completed", ReplayStepKind.FirstByte in completed)

                val finished = events.filterIsInstance<ReplayStepEvent.Finished>().single()
                assertEquals(ReplayVerdict.Success, finished.verdict)
                assertEquals("", finished.recommendationKey)
                // Terminal step on success is the last successful step (no in-flight step).
                assertEquals(ReplayStepKind.FirstByte, finished.terminalStep)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun connectionResetMidRequest_emitsStepFailedConnectionResetThenFinishedFailure() =
        runTest {
            MockWebServer().use { server ->
                // Server accepts the TCP connection but slams the socket
                // shut before responding, mimicking the RST-after-handshake
                // pattern OkHttp surfaces as "unexpected end of stream" /
                // "Connection reset".
                server.enqueue(
                    MockResponse
                        .Builder()
                        .onRequestStart(SocketEffect.CloseSocket())
                        .build(),
                )
                server.start()

                val service = DefaultProbeReplayService(httpFactory(server))
                val events =
                    service
                        .run(ReplayProbeRequest(domain = "example.test", strategyId = "split", timeoutMs = 5_000))
                        .toList()

                val failed = events.filterIsInstance<ReplayStepEvent.StepFailed>().firstOrNull()
                assertNotNull("expected at least one StepFailed in $events", failed)
                assertEquals(ReplayErrorKind.ConnectionReset, failed!!.errorKind)

                val finished = events.filterIsInstance<ReplayStepEvent.Finished>().single()
                assertEquals(ReplayVerdict.Failure, finished.verdict)
                // TCP came up (we got past connectEnd), then the server
                // closed the socket -- so the terminal step is whichever
                // step OkHttp was in flight on, which is the next one
                // after TcpOpen in our state machine. With HTTP (no TLS)
                // that is the implicit FirstByte boundary.
                assertNotNull("expected terminal step set on failure", finished.terminalStep)
            }
        }

    @Test
    fun timeout_emitsFinishedFailureWithTerminalStep() =
        runTest {
            MockWebServer().use { server ->
                // Server accepts connection but delays the response well
                // past the client timeout, forcing a SocketTimeoutException.
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .headersDelay(2_000, TimeUnit.MILLISECONDS)
                        .build(),
                )
                server.start()

                val service = DefaultProbeReplayService(httpFactory(server, timeoutMsOverride = 250))
                val events =
                    service
                        .run(ReplayProbeRequest(domain = "example.test", strategyId = "split", timeoutMs = 250))
                        .toList()

                val finished = events.filterIsInstance<ReplayStepEvent.Finished>().single()
                assertEquals(ReplayVerdict.Failure, finished.verdict)
                assertNotNull(finished.terminalStep)
            }
        }

    @Test
    fun flowCancellation_cancelsUnderlyingCall() =
        // runBlocking, not runTest: the implementation does real I/O on
        // Dispatchers.IO, and runTest's virtual scheduler reports
        // UncompletedCoroutinesError when a real-dispatched task is in
        // flight at the end of the block. Cancellation is what we're
        // measuring here, not virtual time.
        runBlocking {
            MockWebServer().use { server ->
                // Indefinite stall: connection is accepted, then nothing.
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .headersDelay(60_000, TimeUnit.MILLISECONDS)
                        .build(),
                )
                server.start()

                val service = DefaultProbeReplayService(httpFactory(server, timeoutMsOverride = 60_000))

                val request =
                    ReplayProbeRequest(
                        domain = "example.test",
                        strategyId = "split",
                        timeoutMs = 60_000,
                    )
                coroutineScope {
                    val collector =
                        async {
                            service
                                .run(request)
                                .test {
                                    // Wait for the call to actually start the
                                    // TCP open (proves the call was dispatched)
                                    // then cancel.
                                    while (true) {
                                        val ev = awaitItem()
                                        if (ev is ReplayStepEvent.StepCompleted && ev.step == ReplayStepKind.TcpOpen) {
                                            break
                                        }
                                    }
                                    cancelAndIgnoreRemainingEvents()
                                }
                        }
                    // Bounded wait so this test cannot hang the suite.
                    val finished = withTimeoutOrNull(10_000) { collector.await() }
                    assertNotNull("flow collector did not return after cancel", finished)
                }

                // Single request reached the server. If the call were not
                // cancelled, the 60s delay would still be in flight, but
                // the test would have hung at the await above.
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun firstEvent_isStepStarted_forDnsResolve() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(200).build())
                server.start()

                val service = DefaultProbeReplayService(httpFactory(server))
                val first =
                    service
                        .run(ReplayProbeRequest(domain = "example.test", strategyId = "split", timeoutMs = 5_000))
                        .first()

                assertTrue("first event was $first", first is ReplayStepEvent.StepStarted)
                assertEquals(ReplayStepKind.DnsResolve, (first as ReplayStepEvent.StepStarted).step)
            }
        }

    // --- Test factory: routes the synthetic domain to MockWebServer over HTTP. ---

    private fun httpFactory(
        server: MockWebServer,
        timeoutMsOverride: Long? = null,
    ): ReplayHttpClientFactory =
        ReplayHttpClientFactory { request, listener ->
            val timeout = timeoutMsOverride ?: request.timeoutMs
            val dns =
                Dns { hostname ->
                    if (hostname == request.domain) {
                        listOf(InetAddress.getByName("127.0.0.1"))
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            val client =
                OkHttpClient
                    .Builder()
                    .dns(dns)
                    .eventListener(listener)
                    .callTimeout(timeout, TimeUnit.MILLISECONDS)
                    .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                    .readTimeout(timeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .build()
            val url =
                HttpUrl
                    .Builder()
                    .scheme("http")
                    .host(request.domain)
                    .port(server.port)
                    .build()
            ReplayHttpClient(client, url)
        }
}
