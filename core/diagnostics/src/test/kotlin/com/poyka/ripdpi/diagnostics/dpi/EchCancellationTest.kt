package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class EchCancellationTest {
    @Test
    fun cancellationStopsDohDuringResponseBody() =
        runBlocking {
            MockWebServer().use { server ->
                val bodyStarted = CompletableDeferred<Unit>()
                val readCancelled = CompletableDeferred<Boolean>()
                val reply = HexFormat.of().parseHex("0000818000010000000000000663616e63656c076578616d706c650000410001")
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/dns-message")
                        .body(Buffer().write(reply))
                        .bodyDelay(8, TimeUnit.SECONDS)
                        .build(),
                )
                server.start()
                val client =
                    OkHttpClient
                        .Builder()
                        .addNetworkInterceptor { chain ->
                            val response = chain.proceed(chain.request())
                            val body = response.body
                            val observedSource =
                                object : ForwardingSource(body.source()) {
                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        bodyStarted.complete(Unit)
                                        return try {
                                            super.read(sink, byteCount)
                                        } finally {
                                            readCancelled.complete(chain.call().isCanceled())
                                        }
                                    }
                                }.buffer()
                            response
                                .newBuilder()
                                .body(
                                    object : ResponseBody() {
                                        override fun contentType() = body.contentType()

                                        override fun contentLength() = body.contentLength()

                                        override fun source() = observedSource
                                    },
                                ).build()
                        }.build()
                val query = DohWireHttpsRrQuery(client, server.url("/dns-query").toString())
                val active = launch { query.query("cancel.example") }

                withTimeout(3.seconds) { bodyStarted.await() }
                withTimeout(2.seconds) {
                    active.cancelAndJoin()
                    assertTrue(readCancelled.await())
                }
                assertTrue(active.isCancelled)
            }
        }
}
