package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class EchCancellationTest {
    @Test
    fun cancellationStopsDohDuringResponseBody() =
        runBlocking {
            MockWebServer().use { server ->
                val bodyStarted = CompletableDeferred<Unit>()
                val callStopped = CompletableDeferred<Unit>()
                val reply = HexFormat.of().parseHex("0000818000010000000000000663616e63656c076578616d706c650000410001")
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/dns-message")
                        .body(Buffer().write(reply))
                        .throttleBody(1, 5, TimeUnit.SECONDS)
                        .build(),
                )
                server.start()
                val client =
                    OkHttpClient
                        .Builder()
                        .eventListener(
                            object : EventListener() {
                                override fun responseBodyStart(call: Call) {
                                    bodyStarted.complete(Unit)
                                }

                                override fun callFailed(
                                    call: Call,
                                    ioe: IOException,
                                ) {
                                    callStopped.complete(Unit)
                                }
                            },
                        ).build()
                val query = DohWireHttpsRrQuery(client, server.url("/dns-query").toString())
                val active = launch { query.query("cancel.example") }

                withTimeout(3.seconds) { bodyStarted.await() }
                withTimeout(2.seconds) {
                    active.cancelAndJoin()
                    callStopped.await()
                }
                assertTrue(active.isCancelled)
            }
        }
}
