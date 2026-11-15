package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class GeoIpHttpTest {
    @Test
    fun `GeoIP HTTP boundary parses a successful JSON response`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = """{"ip":"8.8.8.8"}"""))
                val json = GeoIpChecker.fetchJson(server.url("/geo").toString(), DetectionResolverConfig())
                assertEquals("8.8.8.8", json.getString("ip"))
                assertEquals("application/json", server.takeRequest().headers["Accept"])
            }
        }

    @Test
    fun `GeoIP HTTP boundary rejects an error status even with valid JSON`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(code = 503, body = """{"ip":"8.8.8.8"}"""))
                val error =
                    try {
                        GeoIpChecker.fetchJson(server.url("/geo").toString(), DetectionResolverConfig())
                        error("HTTP error was accepted")
                    } catch (expected: IOException) {
                        expected
                    }
                assertEquals("GeoIP HTTP 503", error.message)
            }
        }

    @Test
    fun `GeoIP HTTP boundary rejects malformed JSON`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = "<html>not JSON</html>"))
                val error =
                    try {
                        GeoIpChecker.fetchJson(server.url("/geo").toString(), DetectionResolverConfig())
                        error("Malformed JSON was accepted")
                    } catch (expected: JSONException) {
                        expected
                    }
                assertTrue(error.message.orEmpty().isNotBlank())
            }
        }

    @Test
    fun `GeoIP response accepts the byte limit and rejects one extra byte`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                val accepted = jsonBody(MaxGeoResponseBytes)
                val rejected = jsonBody(MaxGeoResponseBytes + 1)
                assertEquals(MaxGeoResponseBytes, accepted.toByteArray(Charsets.UTF_8).size)
                assertEquals(MaxGeoResponseBytes + 1, rejected.toByteArray(Charsets.UTF_8).size)
                server.enqueue(MockResponse(body = accepted))
                server.enqueue(MockResponse(body = rejected))
                val json = GeoIpChecker.fetchJson(server.url("/geo").toString(), DetectionResolverConfig())
                assertEquals(MaxGeoResponseBytes, json.toString().toByteArray(Charsets.UTF_8).size)
                val error =
                    try {
                        GeoIpChecker.fetchJson(server.url("/geo").toString(), DetectionResolverConfig())
                        error("Oversized JSON response was accepted")
                    } catch (expected: IOException) {
                        expected
                    }
                assertTrue(error.message.orEmpty().contains("exceeds $MaxGeoResponseBytes bytes"))
            }
        }

    @Test
    fun `GeoIP cancellation closes an incomplete response body`() =
        runBlocking<Unit> {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                val headersSent = CompletableDeferred<Unit>()
                try {
                    val closed =
                        executor.submit<Int> {
                            server.accept().use { socket ->
                                socket.soTimeout = 3_000
                                val input = socket.getInputStream().bufferedReader()
                                while (!input.readLine().isNullOrEmpty()) { /* Consume the request headers. */ }
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
                                    flush()
                                }
                                headersSent.complete(Unit)
                                socket.getInputStream().read()
                            }
                        }
                    val result =
                        async(Dispatchers.IO) {
                            GeoIpChecker.fetchJson(
                                "http://127.0.0.1:${server.localPort}/geo",
                                DetectionResolverConfig(),
                            )
                        }
                    withTimeout(2_000) { headersSent.await() }

                    withTimeout(1_000) { result.cancelAndJoin() }

                    assertTrue(result.isCancelled)
                    assertEquals(-1, closed.get(1, TimeUnit.SECONDS))
                } finally {
                    executor.shutdownNow()
                }
            }
        }

    private fun jsonBody(size: Int): String {
        val prefix = "{\"padding\":\""
        val suffix = "\"}"
        return prefix + "x".repeat(size - prefix.length - suffix.length) + suffix
    }
}

private const val MaxGeoResponseBytes = 64 * 1024
