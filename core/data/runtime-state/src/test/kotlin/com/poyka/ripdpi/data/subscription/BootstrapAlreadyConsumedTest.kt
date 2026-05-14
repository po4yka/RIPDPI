package com.poyka.ripdpi.data.subscription

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-then-green TDD coverage for HTTP 410 Gone on a bootstrap token's first
 * GET. The deployer's bootstrap service deletes the hash file after the first
 * successful fetch; a token that was already spent (or expired) answers 410.
 *
 * 410 must be a typed terminal state ([BootstrapConsumeResult.AlreadyConsumed]),
 * not a generic network error, and must not be retried.
 */
class BootstrapAlreadyConsumedTest {
    @Test
    fun `410 on first GET yields typed already-consumed result without retry`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(410)
                        .body("Gone")
                        .build(),
                )
                server.start()
                val url = server.url("/bootstrap/spent").toString()
                val consumer = BootstrapConsumer(clockMillis = { 999L })

                val result = consumer.consume(url, groupId = "grp-410")

                assertTrue(result is BootstrapConsumeResult.AlreadyConsumed)
                assertEquals(999L, (result as BootstrapConsumeResult.AlreadyConsumed).consumedAtMillis)
                // Exactly one request, no retry.
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `410 result has zero profiles`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(410).build())
                server.start()
                val url = server.url("/bootstrap/spent2").toString()
                val consumer = BootstrapConsumer(clockMillis = { 1L })

                val result = consumer.consume(url, groupId = "grp")

                assertTrue(result is BootstrapConsumeResult.AlreadyConsumed)
            }
        }

    @Test
    fun `non-410 server error is a typed network error not already-consumed`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(503).build())
                server.start()
                val url = server.url("/bootstrap/down").toString()
                val consumer = BootstrapConsumer(clockMillis = { 1L })

                val result = consumer.consume(url, groupId = "grp")

                assertTrue(result is BootstrapConsumeResult.NetworkError)
            }
        }
}
