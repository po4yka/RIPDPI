package com.poyka.ripdpi.data.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BootstrapConsumerFailureTest {
    @Test
    fun `a cancelled winner still settles the spent single-use token`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("trojan://secret@a.example.com:443#a")
                        .headersDelay(300, TimeUnit.MILLISECONDS)
                        .build(),
                )
                server.start()
                val url = server.url("/bootstrap/cancelled").toString()
                val consumer = BootstrapConsumer(clockMillis = { 9L })

                val winner = launch(Dispatchers.Default) { consumer.consume(url, "grp") }
                withContext(Dispatchers.IO) { server.takeRequest() }
                winner.cancelAndJoin()

                // The server spent the token; a later call must see that instead of awaiting a dead winner.
                val second = withTimeout(5_000) { consumer.consume(url, "grp") }

                assertTrue("expected AlreadyConsumed, got $second", second is BootstrapConsumeResult.AlreadyConsumed)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `a malformed url is a typed error and does not block the next call`() =
        runBlocking {
            val consumer = BootstrapConsumer(clockMillis = { 9L })
            val url = "https://exa mple.com/bootstrap/x"

            val first = withTimeout(5_000) { consumer.consume(url, "grp") }
            val second = withTimeout(5_000) { consumer.consume(url, "grp") }

            assertTrue("got $first", first is BootstrapConsumeResult.ParseError)
            assertTrue("got $second", second is BootstrapConsumeResult.ParseError)
        }
}
