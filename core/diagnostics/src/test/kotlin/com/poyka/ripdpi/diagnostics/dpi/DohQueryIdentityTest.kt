package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.TimeUnit

class DohQueryIdentityTest {
    @Test
    fun wireAddressPostAndGetUseIdZeroAndRejectDifferentReplyId() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(405).build())
                server.enqueue(binaryReply("000181800001000000000000", AddressQuestion))
                server.start(InetAddress.getLoopbackAddress(), 0)

                val endpoints = listOf(server.url("/dns-query").toString())
                val result = DohWireAddressProbe(endpoints = endpoints).resolveResponse(Host)

                val post = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val get = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val postBytes = requireNotNull(post.body).toByteArray()
                val getBytes = Base64.getUrlDecoder().decode(requireNotNull(get.url.queryParameter("dns")))
                assertEquals("POST", post.method)
                assertEquals("GET", get.method)
                assertQuery(postBytes, AddressQuestion)
                assertArrayEquals(postBytes, getBytes)
                assertEquals(DnsResponseOutcome.MALFORMED, result.response?.outcome)
            }
        }

    @Test
    fun wireHttpsPostUsesZeroIdAndType65() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(binaryReply("000081800001000000000000", HttpsQuestion))
                server.start(InetAddress.getLoopbackAddress(), 0)

                DohWireHttpsRrQuery(endpoint = server.url("/dns-query").toString()).query(Host)

                val post = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("POST", post.method)
                assertQuery(requireNotNull(post.body).toByteArray(), HttpsQuestion)
            }
        }

    @Test
    fun availabilityPostUsesZeroIdAndTypeA() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(binaryReply("000081830001000000000000", AddressQuestion))
                server.start(InetAddress.getLoopbackAddress(), 0)

                val resolver = DnsServer("Loopback", DnsServerType.DOH_WIRE, server.url("/dns-query").toString())
                val sample = OkHttpDohWireAvailabilityProbe().probe(resolver, Host, timeoutMs = 2000)

                val post = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("POST", post.method)
                assertQuery(requireNotNull(post.body).toByteArray(), AddressQuestion)
                assertEquals(DnsResponseOutcome.NXDOMAIN, sample.response?.outcome)
            }
        }

    private fun assertQuery(
        query: ByteArray,
        question: String,
    ) {
        assertArrayEquals(byteArrayOf(0, 0), query.copyOfRange(0, 2))
        val expected = HexFormat.of().parseHex(question)
        assertArrayEquals(expected, query.copyOfRange(12, 12 + expected.size))
    }

    private fun binaryReply(
        header: String,
        question: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "application/dns-message")
            .body(Buffer().write(HexFormat.of().parseHex(header + question)))
            .build()

    private companion object {
        private const val Host = "audit.invalid"
        private const val AddressQuestion = "05617564697407696e76616c69640000010001"
        private const val HttpsQuestion = "05617564697407696e76616c69640000410001"
    }
}
