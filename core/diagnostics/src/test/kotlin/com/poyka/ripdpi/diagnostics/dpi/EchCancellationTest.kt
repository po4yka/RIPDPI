package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class EchCancellationTest {
    @Test
    fun cancellationStopsDohDuringResponseBody() =
        runTest(timeout = 20.seconds) {
            MockWebServer().use { server ->
                val requestStarted = CountDownLatch(1)
                val callStopped = CompletableDeferred<Unit>()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            requestStarted.countDown()
                            return MockResponse
                                .Builder()
                                .body("delayed DNS body")
                                .bodyDelay(10, TimeUnit.SECONDS)
                                .build()
                        }
                    }
                server.start()
                val client =
                    OkHttpClient
                        .Builder()
                        .eventListener(
                            object : EventListener() {
                                override fun callFailed(
                                    call: Call,
                                    ioe: java.io.IOException,
                                ) {
                                    callStopped.complete(Unit)
                                }
                            },
                        ).build()
                val query = DohWireHttpsRrQuery(client, server.url("/dns-query").toString())
                val active = launch { query.query("cancel.example") }
                assertTrue(withContext(Dispatchers.IO) { requestStarted.await(5, TimeUnit.SECONDS) })

                active.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(2.seconds) { callStopped.await() }
                }
                assertTrue(active.isCancelled)
            }
        }
}
