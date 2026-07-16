package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-then-green TDD coverage for the bootstrap one-time subscription consume
 * flow. A bootstrap URL is single-use: the server deletes the on-disk hash file
 * after the first successful GET, so the client must consume it exactly once,
 * persist the resulting profiles, mark the token spent, and never re-fetch.
 *
 * Cases:
 *  - cold consume: 200 -> profiles parsed, `consumedAt` set.
 *  - concurrency: 30 simultaneous consume calls on one token -> exactly one
 *    HTTP request reaches the server and exactly one profile bundle is produced.
 */
class BootstrapConsumeOnceTest {
    private fun trojanUri(tag: String) = "trojan://pass-$tag@$tag.example.com:443#$tag"

    private fun payload(): String = listOf(trojanUri("a"), trojanUri("b")).joinToString("\n")

    @Test
    fun `cold consume parses profiles and stamps consumedAt`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(payload())
                        .build(),
                )
                server.start()
                val url = server.url("/bootstrap/abc123").toString()
                val consumer = BootstrapConsumer(clockMillis = { 1_700_000_000_000L })

                val result = consumer.consume(url, groupId = "grp-1")

                assertTrue(result is BootstrapConsumeResult.Consumed)
                val consumed = result as BootstrapConsumeResult.Consumed
                assertEquals(2, consumed.profiles.size)
                assertTrue(consumed.profiles.all { it is ProxyProfile.Trojan })
                assertEquals(1_700_000_000_000L, consumed.consumedAtMillis)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `sing-box consume returns package routes with subscription provenance`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "outbounds":[{"type":"trojan","tag":"n","server":"n.example",
                                "server_port":443,"password":"p"}],
                              "route":{"rules":[{"package_name":["com.bootstrap.app"],"outbound":"direct"}]}
                            }
                            """.trimIndent(),
                        ).build(),
                )
                server.start()
                val consumer = BootstrapConsumer(clockMillis = { 7L })

                val result = consumer.consume(server.url("/bootstrap/routes").toString(), "grp-routes")

                assertTrue(result is BootstrapConsumeResult.Consumed)
                result as BootstrapConsumeResult.Consumed
                val rule = result.packageRoutingRules.single()
                assertEquals("com.bootstrap.app", rule.packageName)
                assertEquals(PackageRoutingAction.BYPASS, rule.action)
                assertEquals(PackageRoutingRuleOrigin.Subscription("grp-routes"), rule.origin)
            }
        }

    @Test
    fun `AWG-only sing-box bootstrap is consumed with its profile`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(awgOnlyPayload())
                        .build(),
                )
                server.start()
                val consumer = BootstrapConsumer(clockMillis = { 9L })

                val result = consumer.consume(server.url("/bootstrap/awg").toString(), "grp-awg")

                assertTrue(result is BootstrapConsumeResult.Consumed)
                result as BootstrapConsumeResult.Consumed
                assertTrue(result.profiles.isEmpty())
                assertEquals(1, result.amneziaWgProfiles.size)
                assertEquals("fixture-awg", result.amneziaWgProfiles.single().displayName)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `thirty concurrent consume calls collapse into one request and one bundle`() =
        runTest {
            MockWebServer().use { server ->
                // Only one response is enqueued: if the mutex fails and N
                // requests fire, the later ones get an empty-queue 200 with no
                // body (or block), and the request count assertion fails.
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(payload())
                        .build(),
                )
                server.start()
                val url = server.url("/bootstrap/concurrent").toString()
                val consumer = BootstrapConsumer(clockMillis = { 42L })

                val results =
                    coroutineScope {
                        (1..30)
                            .map {
                                async { consumer.consume(url, groupId = "grp-c") }
                            }.awaitAll()
                    }

                assertEquals(1, server.requestCount)
                // Every caller observes the winner's Consumed result.
                assertTrue(results.all { it is BootstrapConsumeResult.Consumed })
                val bundles = results.map { (it as BootstrapConsumeResult.Consumed).profiles }
                assertTrue(bundles.all { it.size == 2 })
            }
        }

    @Test
    fun `token hash is stable and derived from host path and token`() {
        val hashA = bootstrapTokenHash("https://host.example.com/bootstrap/tok-1")
        val hashB = bootstrapTokenHash("https://host.example.com/bootstrap/tok-1")
        val hashC = bootstrapTokenHash("https://host.example.com/bootstrap/tok-2")

        assertNotNull(hashA)
        assertEquals(hashA, hashB)
        assertTrue(hashA != hashC)
        // Hash is hex sha256 -> 64 chars, and never contains the raw token.
        assertEquals(64, hashA.length)
        assertTrue(!hashA.contains("tok-1"))
    }

    private fun awgOnlyPayload(): String =
        """
        {
          "outbounds": [],
          "ripdpi": {
            "schema_version": 1,
            "amneziawg": [{
              "tag": "fixture-awg",
              "private_key": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
              "address": ["10.8.0.2/32"],
              "peer": {
                "public_key": "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                "endpoint": "192.0.2.10:51820",
                "allowed_ips": ["0.0.0.0/0"]
              }
            }],
            "hysteria_extras": {}
          }
        }
        """.trimIndent()
}
