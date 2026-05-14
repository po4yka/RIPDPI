package com.poyka.ripdpi.data.subscription

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-then-green TDD coverage for the post-consume short-circuit. Once a
 * bootstrap token has been consumed, both a manual refresh tap and an
 * auto-update worker run must short-circuit without touching the network.
 */
class BootstrapRefreshSkipTest {
    private fun trojanUri(tag: String) = "trojan://pass-$tag@$tag.example.com:443#$tag"

    @Test
    fun `second consume after a successful consume does not hit the network`() =
        runTest {
            MockWebServer().use { server ->
                // Only one response enqueued. A second network call would
                // either drain the empty queue or fail; either way the
                // request-count assertion catches a re-fetch.
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(trojanUri("a"))
                        .build(),
                )
                server.start()
                val url = server.url("/bootstrap/once").toString()
                val consumer = BootstrapConsumer(clockMillis = { 100L })

                val first = consumer.consume(url, groupId = "grp")
                assertTrue(first is BootstrapConsumeResult.Consumed)
                assertEquals(1, server.requestCount)

                // Manual refresh tap == another consume of the same URL.
                val second = consumer.consume(url, groupId = "grp")

                assertEquals(1, server.requestCount)
                assertTrue(second is BootstrapConsumeResult.AlreadyConsumed)
            }
        }

    @Test
    fun `second consume after a 410 already-consumed does not hit the network`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(410).build())
                server.start()
                val url = server.url("/bootstrap/gone").toString()
                val consumer = BootstrapConsumer(clockMillis = { 7L })

                val first = consumer.consume(url, groupId = "grp")
                assertTrue(first is BootstrapConsumeResult.AlreadyConsumed)
                assertEquals(1, server.requestCount)

                val second = consumer.consume(url, groupId = "grp")

                assertEquals(1, server.requestCount)
                assertTrue(second is BootstrapConsumeResult.AlreadyConsumed)
            }
        }
}
