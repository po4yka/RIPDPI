package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class NativeDoqQuicClientTest {
    @Test
    fun exchangeSendsUnframedDnsWireQueryToNativeAndFramesResponse() =
        runTest {
            val (transactionId, framedQuery) = DoqWireCodec.buildQuery(TestDomain)
            val framedNativeResponse = DoqIntegrityProbeTestFixtures.dnsResponse(TestDomain, transactionId, "1.2.3.4")
            val nativeResponse =
                framedNativeResponse.copyOfRange(
                    DoqLengthPrefixBytes,
                    framedNativeResponse.size,
                )
            val bindings =
                CapturingBindings {
                    """{"responseBase64":"${Base64.getEncoder().encodeToString(nativeResponse)}"}"""
                }
            val client = NativeDoqQuicClient(bindings)

            val framedResponse =
                client.exchange(
                    endpoint = "1.1.1.1",
                    port = 853,
                    tlsServerName = "cloudflare-dns.com",
                    query = framedQuery,
                    timeoutMs = 1_000,
                )

            val request = Json.parseToJsonElement(bindings.lastRequestJson).jsonObject
            assertEquals("1.1.1.1", request.getValue("endpoint").jsonPrimitive.content)
            assertEquals("853", request.getValue("port").jsonPrimitive.content)
            assertEquals("cloudflare-dns.com", request.getValue("tlsServerName").jsonPrimitive.content)
            assertArrayEquals(
                framedQuery.copyOfRange(DoqLengthPrefixBytes, framedQuery.size),
                Base64.getDecoder().decode(request.getValue("queryBase64").jsonPrimitive.content),
            )
            assertEquals(listOf("1.2.3.4"), DoqWireCodec.parseResponse(framedResponse, transactionId))
        }

    @Test
    fun tlsNativeErrorMapsToDpiReject() =
        runTest {
            val (_, framedQuery) = DoqWireCodec.buildQuery(TestDomain)
            val client =
                NativeDoqQuicClient(
                    CapturingBindings {
                        """{"errorKind":"tls","error":"handshake_failure"}"""
                    },
                )

            val error =
                runCatching {
                    client.exchange(
                        endpoint = "1.1.1.1",
                        port = 853,
                        tlsServerName = "cloudflare-dns.com",
                        query = framedQuery,
                        timeoutMs = 1_000,
                    )
                }.exceptionOrNull()

            assertEquals(DoqTlsRejectException::class, error?.javaClass?.kotlin)
            assertEquals("handshake_failure", error?.message)
        }

    @Test
    fun timeoutNativeErrorMapsToDoqTimeout() =
        runTest {
            val (_, framedQuery) = DoqWireCodec.buildQuery(TestDomain)
            val client =
                NativeDoqQuicClient(
                    CapturingBindings {
                        """{"errorKind":"timeout","error":"DoQ timed out"}"""
                    },
                )

            val error =
                runCatching {
                    client.exchange(
                        endpoint = "1.1.1.1",
                        port = 853,
                        tlsServerName = "cloudflare-dns.com",
                        query = framedQuery,
                        timeoutMs = 1_000,
                    )
                }.exceptionOrNull()

            assertEquals(DoqTimeoutException::class, error?.javaClass?.kotlin)
            assertEquals("DoQ timed out", error?.message)
        }

    private class CapturingBindings(
        private val response: () -> String?,
    ) : NativeDoqQuicClientBindings {
        lateinit var lastRequestJson: String

        override fun exchange(requestJson: String): String? {
            lastRequestJson = requestJson
            return response()
        }
    }

    private companion object {
        private const val TestDomain = "example.com"
        private const val DoqLengthPrefixBytes = 2
    }
}
