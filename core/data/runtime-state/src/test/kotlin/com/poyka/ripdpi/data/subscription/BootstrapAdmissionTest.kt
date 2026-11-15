package com.poyka.ripdpi.data.subscription

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BootstrapAdmissionTest {
    @Test
    fun `partial URI lists and rejected sing-box nodes do not become consumed profiles`() =
        runBlocking {
            val valid =
                """{"type":"shadowsocks","tag":"valid","server":"valid.example","server_port":443,
                    "method":"aes-128-gcm","password":"fixture"}"""
            val payloads =
                listOf(
                    "trojan://fixture@valid.example:443#valid\ninvalid-line",
                    """{"outbounds":[$valid,{"type":"tuic","tag":"unsupported"}]}""",
                    """{"outbounds":[$valid,{"type":"trojan","tag":"unsupported","server":"other.example",
                        "server_port":443,"password":"fixture","transport":{"type":"ws"}}]}""",
                    """{"outbounds":[${valid.replace("443", "70000")}]}""",
                )
            for (payload in payloads) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse.Builder().body(payload).build())
                    server.start()
                    val consumer = BootstrapConsumer(clockMillis = { 7L })
                    val url = server.url("/bootstrap/admission").toString()

                    val result = consumer.consume(url, "group")

                    assertTrue("expected ParseError, got $result", result is BootstrapConsumeResult.ParseError)
                    assertEquals(result, consumer.consume(url, "group"))
                    assertEquals(1, server.requestCount)
                }
            }
        }

    @Test
    fun `a slow delivered body has a finite budget and is not requested again`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body("trojan://fixture@node.example:443#node")
                        .bodyDelay(500, TimeUnit.MILLISECONDS)
                        .build(),
                )
                server.start()
                val consumer =
                    BootstrapConsumer(
                        OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build(),
                        clockMillis = { 8L },
                    )
                val url = server.url("/bootstrap/slow").toString()
                val result = withTimeout(2_000) { consumer.consume(url, "group") }
                assertTrue("expected terminal ParseError, got $result", result is BootstrapConsumeResult.ParseError)
                assertEquals(8L, (result as BootstrapConsumeResult.ParseError).consumedAtMillis)
                assertEquals(result, consumer.consume(url, "group"))
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `single-use delivery does not follow a redirect to another endpoint`() =
        runBlocking {
            MockWebServer().use { destination ->
                destination.enqueue(MockResponse.Builder().body("trojan://fixture@node.example:443#node").build())
                destination.start()
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse
                            .Builder()
                            .code(302)
                            .addHeader("Location", destination.url("/redirected"))
                            .build(),
                    )
                    server.start()
                    val result = BootstrapConsumer().consume(server.url("/bootstrap/redirect").toString(), "group")
                    assertTrue("expected HTTP rejection, got $result", result is BootstrapConsumeResult.NetworkError)
                    assertEquals(1, server.requestCount)
                    assertEquals(0, destination.requestCount)
                }
            }
        }

    @Test
    fun `a concurrent import into another group never receives foreign owned members`() =
        runBlocking {
            MockWebServer().use { server ->
                val release = CountDownLatch(1)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            check(release.await(5, TimeUnit.SECONDS))
                            return MockResponse.Builder().body("trojan://fixture@node.example:443#node").build()
                        }
                    }
                server.start()
                val consumer = BootstrapConsumer(clockMillis = { 9L })
                val url = server.url("/bootstrap/group-race").toString()
                val winner = async(Dispatchers.IO) { consumer.consume(url, "winner") }
                checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val joiner = async(start = CoroutineStart.UNDISPATCHED) { consumer.consume(url, "joiner") }
                release.countDown()
                val first = winner.await() as BootstrapConsumeResult.Consumed
                assertTrue(first.profiles.all { it.groupId == "winner" })
                assertEquals(BootstrapConsumeResult.AlreadyConsumed(9L), joiner.await())
                assertEquals(1, server.requestCount)
            }
        }
}
