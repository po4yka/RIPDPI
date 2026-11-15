package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsHttpResponseTest {
    @Test
    fun wireReadsOnlyTheAdvertisedMediaTypeAndDecodedLimit() {
        MockWebServer().use { server ->
            server.start()
            val limit = DnsMessages.MAX_MESSAGE_BYTES
            server.enqueue(response("application/dns-message", Buffer().write(ByteArray(limit))))
            server.enqueue(response("application/dns-message", Buffer().write(ByteArray(limit + 1))))
            server.enqueue(response("text/plain", Buffer().writeByte(0)))
            val request = Request.Builder().url(server.url("/dns-query")).build()
            val client = OkHttpClient()
            assertEquals(
                limit,
                client
                    .newCall(request)
                    .execute()
                    .use(Response::readDnsWireBody)
                    .size,
            )
            repeat(2) {
                assertThrows(MalformedDnsHttpResponse::class.java) {
                    client.newCall(request).execute().use(Response::readDnsWireBody)
                }
            }
        }
    }

    @Test
    fun jsonAcceptsVendorMediaTypesAndChecksDecodedGzipSize() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("application/dns-json", Buffer().writeUtf8("{}")))
            server.enqueue(response("application/json; charset=utf-8", Buffer().writeUtf8("{}")))
            server.enqueue(response("text/plain", Buffer().writeUtf8("{}")))
            val gzip = Buffer()
            GzipSink(gzip).buffer().use { it.write(ByteArray(MaxDnsJsonBodyBytes + 1)) }
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/dns-json")
                    .addHeader("Content-Encoding", "gzip")
                    .chunkedBody(gzip, 4096)
                    .build(),
            )
            val request = Request.Builder().url(server.url("/resolve")).build()
            val client = OkHttpClient()
            repeat(2) {
                assertEquals("{}", client.newCall(request).execute().use(Response::readDnsJsonBody))
            }
            repeat(2) {
                assertThrows(MalformedDnsHttpResponse::class.java) {
                    client.newCall(request).execute().use(Response::readDnsJsonBody)
                }
            }
        }
    }

    @Test
    fun httpsRecordLookupRejectsDnsWireUnderTheWrongMediaType() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val reply = requireNotNull(request.body).toByteArray()
                            reply[2] = 0x81.toByte()
                            reply[3] = 0x80.toByte()
                            return response("text/plain", Buffer().write(reply))
                        }
                    }
                server.start()

                val result = DohWireHttpsRrQuery(endpoint = server.url("/dns-query").toString()).query("audit.invalid")

                assertEquals(HttpsRrOutcome.MALFORMED, result.outcome)
            }
        }

    private fun response(
        type: String,
        body: Buffer,
    ): MockResponse =
        MockResponse
            .Builder()
            .addHeader("Content-Type", type)
            .chunkedBody(body, 4096)
            .build()
}
