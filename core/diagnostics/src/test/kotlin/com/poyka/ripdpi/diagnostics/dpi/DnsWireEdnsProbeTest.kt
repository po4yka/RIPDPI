package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsWireEdnsProbeTest {
    @Test
    fun binaryDohCollectsEdeFromAnEdnsAwareServer() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val query = requireNotNull(request.body).toByteArray()
                            val opt = hex("00002904d0000000000000")
                            val requestHasOpt =
                                query.size >= 12 + opt.size && query[11] == 1.toByte() &&
                                    query.takeLast(opt.size).toByteArray().contentEquals(opt)
                            if (!requestHasOpt) return MockResponse.Builder().code(400).build()
                            val response =
                                query.copyOf(query.size - opt.size).also {
                                    it[2] = 0x81.toByte()
                                    it[3] = 0x85.toByte()
                                } + hex("00002904d0000000000006000f0002000f")
                            return MockResponse
                                .Builder()
                                .addHeader("Content-Type", "application/dns-message")
                                .body(Buffer().write(response))
                                .build()
                        }
                    }
                server.start()
                val probe = DohWireAddressProbe(endpoints = listOf(server.url("/dns-query").toString()))
                val response = probe.resolveResponse("example.invalid").response

                assertEquals(5, response?.rcode)
                assertEquals(listOf(15), response?.observedExtendedDnsErrorCodes)
            }
        }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
