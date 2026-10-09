package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Base64

class DnsSemanticProbesTest {
    @Test
    fun wireNegativePostResponseDoesNotTriggerGetOrBecomeTransportFailure() =
        runTest {
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
                    StandardTestDispatcher(testScheduler),
                ).resolveResponse("example.com")
            assertEquals(DnsResponseOutcome.NXDOMAIN, result.response?.outcome)
            assertEquals(listOf("POST"), methods)
            assertTrue(result.addresses.isEmpty())
        }

    @Test
    fun methodNotAllowedFallbackStillBindsResponseToQuery() =
        runTest {
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
                    StandardTestDispatcher(testScheduler),
                ).resolveResponse("example.com")
            assertEquals(DnsResponseOutcome.NXDOMAIN, result.response?.outcome)
            assertEquals(listOf("POST", "GET"), methods)
        }

    @Test
    fun httpErrorsAndSocketTimeoutRemainDifferent() =
        runTest {
            val error =
                DohWireAddressProbe(
                    client { reply(it, byteArrayOf(), 503) },
                    listOf(Endpoint),
                    StandardTestDispatcher(testScheduler),
                )
            val timeout =
                DohWireAddressProbe(
                    client { throw SocketTimeoutException() },
                    listOf(Endpoint),
                    StandardTestDispatcher(testScheduler),
                )
            assertEquals(DnsResponseOutcome.TRANSPORT_ERROR, error.resolveResponse("example.com").response?.outcome)
            assertEquals(DnsResponseOutcome.TIMEOUT, timeout.resolveResponse("example.com").response?.outcome)
        }

    @Test
    fun jsonNodataIsAResponseAndMissingQuestionIsMalformed() =
        runTest {
            val negative = """{"Status":0,"Question":[{"name":"example.com.","type":1}]}"""
            val client = client { reply(it, negative.toByteArray()) }
            val probe = DohJsonAddressProbe(client, listOf(Endpoint), StandardTestDispatcher(testScheduler))
            assertEquals(DnsResponseOutcome.NODATA, probe.resolveResponse("example.com").response?.outcome)
            assertEquals(DnsResponseOutcome.MALFORMED, probe.resolveResponse("other.example").response?.outcome)
        }

    @Test
    fun cancellationIsNeverTranslatedToDnsFailure() =
        runTest {
            val client = client { throw CancellationException("cancel") }
            val probe = DohJsonAddressProbe(client, listOf(Endpoint), StandardTestDispatcher(testScheduler))
            try {
                probe.resolveResponse("example.com")
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                // Expected.
            }
        }

    private fun client(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()

    private fun reply(
        request: Request,
        bytes: ByteArray,
        code: Int = 200,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(bytes.toResponseBody())
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
    }
}
