package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit

class DnsSemanticProbesTest {
    @Test
    fun wireNegativePostResponseDoesNotTriggerGetOrBecomeTransportFailure() =
        runBlocking {
            val methods = mutableListOf<String>()
            val client =
                client { request ->
                    methods += request.method
                    reply(request, negative(query(request)))
                }
            val result =
                DohWireAddressProbe(
                    client,
                    listOf(Endpoint),
                ).resolveResponse("example.com")
            assertEquals(DnsResponseOutcome.NXDOMAIN, result.response?.outcome)
            assertEquals(listOf("POST"), methods)
            assertTrue(result.addresses.isEmpty())
        }

    @Test
    fun methodNotAllowedFallbackStillBindsResponseToQuery() =
        runBlocking {
            val methods = mutableListOf<String>()
            val client =
                client { request ->
                    methods += request.method
                    if (request.method ==
                        "POST"
                    ) {
                        reply(request, byteArrayOf(), 405)
                    } else {
                        reply(request, negative(query(request)))
                    }
                }
            val result =
                DohWireAddressProbe(
                    client,
                    listOf(Endpoint),
                ).resolveResponse("example.com")
            assertEquals(DnsResponseOutcome.NXDOMAIN, result.response?.outcome)
            assertEquals(listOf("POST", "GET"), methods)
        }

    @Test
    fun httpErrorsAndSocketTimeoutRemainDifferent() =
        runBlocking {
            val error =
                DohWireAddressProbe(
                    client { reply(it, byteArrayOf(), 503) },
                    listOf(Endpoint),
                )
            val timeout =
                DohWireAddressProbe(
                    client { throw SocketTimeoutException() },
                    listOf(Endpoint),
                )
            assertEquals(DnsResponseOutcome.TRANSPORT_ERROR, error.resolveResponse("example.com").response?.outcome)
            assertEquals(DnsResponseOutcome.TIMEOUT, timeout.resolveResponse("example.com").response?.outcome)
        }

    @Test
    fun jsonNodataIsAResponseAndMissingQuestionIsMalformed() =
        runBlocking {
            val client = client { reply(it, JsonNegative.toByteArray(), mediaType = "application/dns-json") }
            val probe = DohJsonAddressProbe(client, listOf(Endpoint))
            assertEquals(DnsResponseOutcome.NODATA, probe.resolveResponse("example.com").response?.outcome)
            assertEquals(DnsResponseOutcome.MALFORMED, probe.resolveResponse("other.example").response?.outcome)
        }

    @Test
    fun onlyKnownUnchangedDohEndpointsSupplyAComparableProfile() =
        runBlocking {
            val known = "https://dns.google/resolve"
            val client = client { reply(it, JsonNegative.toByteArray(), mediaType = "application/dns-json") }
            val knownAnswer = DohJsonAddressProbe(client, listOf(known)).resolveResponse("example.com")
            assertEquals(DohResolverProfile.GOOGLE_PUBLIC, knownAnswer.dohProfile)

            val alternate = DohJsonAddressProbe(client, listOf("$known?policy=other")).resolveResponse("example.com")
            assertEquals("dns.google", alternate.resolver)
            assertNull(alternate.dohProfile)

            val redirectedClient =
                client { original ->
                    val final = original.newBuilder().url("https://dns.google/another-policy").build()
                    reply(final, JsonNegative.toByteArray(), mediaType = "application/dns-json")
                }
            val redirected = DohJsonAddressProbe(redirectedClient, listOf(known)).resolveResponse("example.com")
            assertEquals("dns.google", redirected.resolver)
            assertNull(redirected.dohProfile)
        }

    @Test
    fun cancellingDuringHttpBodyReadStopsTheExchangeAndDoesNotRetry() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(delayedJson())
                val bodyStarted = CompletableDeferred<Unit>()
                val client =
                    OkHttpClient
                        .Builder()
                        .eventListener(
                            object : EventListener() {
                                override fun responseBodyStart(call: Call) {
                                    bodyStarted.complete(Unit)
                                }
                            },
                        ).build()
                val probe =
                    DohJsonAddressProbe(
                        client = client,
                        endpoints = listOf(server.url("/first").toString(), server.url("/next").toString()),
                    )
                val job = launch { probe.resolveResponse("example.com") }
                withTimeout(3_000) { bodyStarted.await() }

                withTimeout(1_500) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun jsonTotalTimeoutDuringBodyReadDoesNotTryAnotherEndpoint() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(delayedJson())
                val probe =
                    DohJsonAddressProbe(
                        endpoints = listOf(server.url("/first").toString(), server.url("/next").toString()),
                        timeoutMs = 750,
                    )
                val answer = withTimeout(3_000) { probe.resolveResponse("example.com") }
                assertEquals(DnsResponseOutcome.TIMEOUT, answer.response?.outcome)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun jsonUsesTheResolverThatAnsweredAfterRedirect() =
        runBlocking {
            MockWebServer().use { redirect ->
                redirect.start()
                MockWebServer().use { destination ->
                    destination.start()
                    val actual =
                        destination
                            .url("/answer")
                            .newBuilder()
                            .host("127.0.0.1")
                            .build()
                    redirect.enqueue(
                        MockResponse
                            .Builder()
                            .code(302)
                            .addHeader("Location", actual.toString())
                            .build(),
                    )
                    destination.enqueue(
                        MockResponse
                            .Builder()
                            .addHeader("Content-Type", "application/dns-json")
                            .body(JsonNegative)
                            .build(),
                    )
                    val probe = DohJsonAddressProbe(endpoints = listOf(redirect.url("/resolve").toString()))
                    val answer = probe.resolveResponse("example.com")
                    assertEquals(DnsResponseOutcome.NODATA, answer.response?.outcome)
                    assertEquals("127.0.0.1", answer.resolver)
                }
            }
        }

    @Test
    fun cancellingDuringDatagramReceiveClosesTheSocket() =
        runBlocking {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
                val job =
                    launch(Dispatchers.IO) {
                        exchangeDnsDatagram(
                            "127.0.0.1",
                            DnsWireBuilder.buildQuery("example.com"),
                            timeoutMs = 10_000,
                            port = server.localPort,
                        )
                    }
                withContext(Dispatchers.IO) {
                    server.soTimeout = 2_000
                    server.receive(DatagramPacket(ByteArray(512), 512))
                }
                withTimeout(1_500) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        }

    private fun delayedJson(): MockResponse =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "application/dns-json")
            .body(JsonNegative)
            .throttleBody(1, 5, TimeUnit.SECONDS)
            .build()

    private fun client(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()

    private fun reply(
        request: Request,
        bytes: ByteArray,
        code: Int = 200,
        mediaType: String = "application/dns-message",
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(bytes.toResponseBody(mediaType.toMediaType()))
            .build()

    private fun query(request: Request): ByteArray =
        if (request.method == "POST") {
            Buffer().also { request.body!!.writeTo(it) }.readByteArray()
        } else {
            Base64.getUrlDecoder().decode(request.url.queryParameter("dns"))
        }

    private fun negative(query: ByteArray): ByteArray =
        query.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x83.toByte()
        }

    private companion object {
        const val Endpoint = "https://resolver.example/dns-query"
        const val JsonNegative = """{"Status":0,"Question":[{"name":"example.com.","type":1}]}"""
    }
}
