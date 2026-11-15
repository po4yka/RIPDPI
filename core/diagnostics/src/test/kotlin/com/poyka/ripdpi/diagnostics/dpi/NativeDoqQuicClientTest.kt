package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
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
            assertEquals(listOf("1.2.3.4"), DoqWireCodec.parseResponse(framedResponse, framedQuery).addresses)
        }

    @Test
    fun nativeFailureKindsArePreserved() =
        runTest {
            val (_, framedQuery) = DoqWireCodec.buildQuery(TestDomain)
            val failures =
                mapOf(
                    "invalid_request" to DoqFailureKind.INVALID_REQUEST,
                    "setup" to DoqFailureKind.SETUP,
                    "bootstrap" to DoqFailureKind.BOOTSTRAP,
                    "tls" to DoqFailureKind.TLS,
                    "alpn" to DoqFailureKind.ALPN,
                    "quic" to DoqFailureKind.QUIC,
                    "wire" to DoqFailureKind.WIRE,
                    "timeout" to DoqFailureKind.TIMEOUT,
                )

            for ((wireKind, expected) in failures) {
                val client =
                    NativeDoqQuicClient(
                        CapturingBindings { """{"errorKind":"$wireKind","error":"reported failure"}""" },
                    )
                val error = runCatching { client.exchange(framedQuery) }.exceptionOrNull()
                assertEquals(expected, (error as? DoqExchangeException)?.kind)
                assertEquals("reported failure", error?.message)
            }
        }

    @Test
    fun bridgeFailuresAreSeparateFromNetworkFailures() =
        runTest {
            val query = byteArrayOf(0, 12) + ByteArray(12)
            val invalidReplies =
                listOf(
                    null,
                    "{",
                    """{"errorKind":"unexpected","error":"unexpected failure"}""",
                    """{"errorKind":"tls","responseBase64":"AAAAAAAAAAAAAAAA"}""",
                    """{"errorKind":"tls","error":"certificate","responseBase64":"AAAAAAAAAAAAAAAA"}""",
                )

            for (reply in invalidReplies) {
                val client = NativeDoqQuicClient(CapturingBindings { reply })
                val error = runCatching { client.exchange(query) }.exceptionOrNull()
                assertEquals(DoqFailureKind.JNI, (error as? DoqExchangeException)?.kind)
            }

            val missingLibrary = NativeDoqQuicClient(CapturingBindings { throw UnsatisfiedLinkError() })
            val error = runCatching { missingLibrary.exchange(query) }.exceptionOrNull()
            assertEquals(DoqFailureKind.JNI, (error as? DoqExchangeException)?.kind)
        }

    @Test
    fun nativeCancellationIsACoroutineCancellation() =
        runTest {
            val (_, framedQuery) = DoqWireCodec.buildQuery(TestDomain)
            val client = NativeDoqQuicClient(CapturingBindings { """{"errorKind":"cancelled","error":"cancelled"}""" })

            val error = runCatching { client.exchange(framedQuery) }.exceptionOrNull()
            assertEquals(CancellationException::class.java, error?.javaClass)
        }

    private suspend fun NativeDoqQuicClient.exchange(query: ByteArray): ByteArray =
        exchange("1.1.1.1", 853, "cloudflare-dns.com", query, 1_000)

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
