package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class IfconfigClientTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val dispatchers = AppCoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)

    @Test
    fun explicitHttpProxyReceivesTheRequest() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("192.0.2.1\n").build())
                server.start(loopback, 0)
                val target =
                    server
                        .url("/ip")
                        .newBuilder()
                        .host("target.invalid")
                        .build()
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.port))

                val result = IfconfigClient.fetchIp(dispatchers, 3_000, proxy, target)

                assertEquals("192.0.2.1", result.getOrThrow())
                assertEquals("GET $target HTTP/1.1", server.takeRequest(1, TimeUnit.SECONDS)?.requestLine)
            }
        }

    @Test
    fun oversizedResponseFails() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("x".repeat(257)).build())
                server.start(loopback, 0)

                val result = IfconfigClient.fetchIp(dispatchers, 3_000, Proxy.NO_PROXY, server.url("/ip"))

                assertEquals("IP response too large", result.exceptionOrNull()?.message)
            }
        }

    @Test
    fun tricklingResponseCannotExtendTheCallDeadline() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body("x".repeat(50))
                        .throttleBody(1, 100, TimeUnit.MILLISECONDS)
                        .build(),
                )
                server.start(loopback, 0)

                val result =
                    withTimeout(3_000) {
                        IfconfigClient.fetchIp(dispatchers, 1_000, Proxy.NO_PROXY, server.url("/ip"))
                    }

                assertTrue(result.isFailure)
            }
        }

    @Test
    fun cancelClosesTheActiveBodyCall() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body("192.0.2.1")
                        .throttleBody(1, 8, TimeUnit.SECONDS)
                        .build(),
                )
                server.start(loopback, 0)
                val bodyStarted = CompletableDeferred<Unit>()
                val cancelledRead = CompletableDeferred<Boolean>()
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
                                    cancelledRead.complete(call.isCanceled())
                                }
                            },
                        ).build()

                val request =
                    launch {
                        IfconfigClient.fetchIp(dispatchers, 10_000, Proxy.NO_PROXY, server.url("/ip"), client)
                    }

                withTimeout(3_000) { bodyStarted.await() }
                withTimeout(2_000) {
                    request.cancelAndJoin()
                    assertTrue(cancelledRead.await())
                }
                assertTrue(request.isCancelled)
            }
        }
}
