package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshDeliveryTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `transient mirror failure retains retry when final endpoint cannot parse`() =
        runTest {
            server.enqueue(response(503))
            server.enqueue(response(200, "invalid payload"))
            val member =
                ProxyProfile.Trojan(
                    "retained",
                    "subscription-group",
                    "retained",
                    "relay.example",
                    443,
                    "fixture",
                )
            val fixture = fixture(now = 3_000L, initialMembers = listOf(member), httpClient = localMirrorClient())
            fixture.repository.updateSubscription("subscription-group") {
                it.copy(
                    mirrors =
                        SubscriptionMirrorSet(
                            listOf(
                                SubscriptionMirror(
                                    "direct",
                                    server
                                        .url("/direct")
                                        .newBuilder()
                                        .scheme("https")
                                        .build()
                                        .toString(),
                                ),
                            ),
                        ),
                )
            }

            assertEquals(SubscriptionRefreshRunResult.RETRY, fixture.coordinator.refreshAll())
            assertEquals(
                listOf(member),
                fixture.repository
                    .list()
                    .single()
                    .members,
            )
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `fresh expired mirror payload is terminal and retains last good members`() =
        runTest {
            server.enqueue(response(200, ripdpiPayload.replace("2026-12-31T23:59:59Z", "1970-01-01T00:00:01Z")))
            server.enqueue(response(200, trojanPayload))
            val member =
                ProxyProfile.Trojan(
                    "retained",
                    "subscription-group",
                    "retained",
                    "relay.example",
                    443,
                    "fixture",
                )
            val fixture = fixture(now = 3_000L, initialMembers = listOf(member), httpClient = localMirrorClient())
            fixture.repository.updateSubscription("subscription-group") {
                it.copy(
                    mirrors =
                        SubscriptionMirrorSet(
                            listOf(
                                SubscriptionMirror(
                                    "direct",
                                    server
                                        .url("/direct")
                                        .newBuilder()
                                        .scheme("https")
                                        .build()
                                        .toString(),
                                ),
                            ),
                        ),
                )
            }

            assertEquals(
                SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.EXPIRED, false),
                fixture.coordinator.refresh("subscription-group"),
            )
            assertEquals(
                listOf(member),
                fixture.repository
                    .list()
                    .single()
                    .members,
            )
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `refresh persists explicit Cloudflare classification across randomized member ids`() =
        runTest {
            val payload = """{"outbounds":[
          {"type":"selector","tag":"select","outbounds":["edge","origin","auto"],"default":"edge"},
          {"type":"urltest","tag":"auto","url":"https://probe.example","interval":"10s"},
          {"type":"trojan","tag":"edge","server":"edge.example","server_port":443,"password":"fixture"},
          {"type":"trojan","tag":"origin","server":"origin.example","server_port":443,"password":"fixture"}
          ],"ripdpi":{"schema_version":1,"cloudflare_outbound_tags":["edge"]}}"""
            val fixture = fixture(now = 3_000L)
            server.enqueue(response(200, payload))
            fixture.coordinator.refresh("subscription-group")
            val first = fixture.repository.list().single()
            val edgeId = first.members.single { it.displayName == "edge" }.id
            assertTrue(first.isSelector)
            assertEquals(setOf(edgeId), first.cloudflareMemberIds)
            server.enqueue(response(200, payload))
            fixture.coordinator.refresh("subscription-group")
            assertEquals(
                setOf(edgeId),
                fixture.repository
                    .list()
                    .single()
                    .cloudflareMemberIds,
            )
            server.enqueue(response(200, payload.replace(",\"cloudflare_outbound_tags\":[\"edge\"]", "")))
            fixture.coordinator.refresh("subscription-group")
            assertEquals(
                setOf(edgeId),
                fixture.repository
                    .list()
                    .single()
                    .cloudflareMemberIds,
            )
            server.enqueue(response(200, payload.replace("[\"edge\"]", "[]")))
            fixture.coordinator.refresh("subscription-group")
            assertTrue(
                fixture.repository
                    .list()
                    .single()
                    .cloudflareMemberIds
                    .isEmpty(),
            )
        }

    @Test
    fun `invalid persisted mirror policy is rejected before any request`() =
        runTest {
            val secureUrl =
                server
                    .url("/device")
                    .newBuilder()
                    .scheme("https")
                    .build()
                    .toString()
            val invalidPolicies =
                listOf(
                    listOf(SubscriptionMirror("insecure", server.url("/device").toString(), "fixture-token")),
                    (0..8).map { SubscriptionMirror("mirror-$it", "$secureUrl/$it") },
                    listOf(SubscriptionMirror("one", secureUrl), SubscriptionMirror("two", secureUrl)),
                    listOf(SubscriptionMirror("invalid-token", secureUrl, "fixture\ninvalid")),
                )
            for (mirrors in invalidPolicies) {
                server.enqueue(response(200, trojanPayload))
                val fixture = fixture(now = 3_000L, httpClient = localMirrorClient())
                fixture.repository.updateSubscription(
                    "subscription-group",
                ) { it.copy(mirrors = SubscriptionMirrorSet(mirrors)) }

                val result = fixture.coordinator.refresh("subscription-group")

                assertEquals(0, server.requestCount)
                assertEquals(SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.PARSE_ERROR, false), result)
            }
        }

    @Test
    fun `manual bootstrap refresh never consumes the URL again`() =
        runTest {
            server.enqueue(response(200, trojanPayload))
            val fixture = fixture(now = 3_000L)
            fixture.repository.updateSubscription("subscription-group") {
                it.copy(kind = SubscriptionKind.BOOTSTRAP, consumedAt = 1_000L)
            }

            val result = fixture.coordinator.refresh("subscription-group")

            assertEquals(0, server.requestCount)
            assertEquals(SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.INVALIDATED, false), result)
            assertNull(
                fixture.repository
                    .list()
                    .single()
                    .subscription
                    ?.lastRefreshFailure,
            )
        }

    @Test
    fun `mirror redirect cannot forward endpoint credential to another path`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .setHeader("Location", "/other-device")
                    .build(),
            )
            server.enqueue(response(200, trojanPayload))
            val fixture = fixture(now = 3_000L, httpClient = localMirrorClient())
            fixture.repository.updateSubscription("subscription-group") {
                it.copy(
                    mirrors =
                        SubscriptionMirrorSet(
                            listOf(
                                SubscriptionMirror(
                                    "direct",
                                    server
                                        .url("/direct-device")
                                        .newBuilder()
                                        .scheme("https")
                                        .build()
                                        .toString(),
                                    "fixture-direct-token",
                                ),
                            ),
                        ),
                )
            }

            val result = fixture.coordinator.refresh("subscription-group")

            assertTrue(result is SubscriptionRefreshResult.Updated)
            assertEquals("/direct-device", server.takeRequest().url.encodedPath)
            val fallback = server.takeRequest()
            assertEquals("/subscription", fallback.url.encodedPath)
            assertNull(fallback.headers["Authorization"])
        }

    // Only the test transport maps validated HTTPS URLs to this local HTTP server.
    private fun localMirrorClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    request
                        .newBuilder()
                        .url(
                            request.url
                                .newBuilder()
                                .scheme("http")
                                .build(),
                        ).build(),
                )
            }.build()

    @Test
    fun `refresh imports scoped mirrors and next refresh uses direct endpoint first`() =
        runTest {
            val directUrl =
                server
                    .url("/direct-device")
                    .newBuilder()
                    .scheme("https")
                    .build()
            val cloudflareUrl =
                server
                    .url("/cloudflare-device")
                    .newBuilder()
                    .scheme("https")
                    .build()
            val payload = """{"outbounds":[{"type":"trojan","tag":"relay","server":"relay.example",
            "server_port":443,"password":"fixture-password"}],"ripdpi":{"schema_version":1,
            "subscription_mirrors":[
            {"id":"cf","url":"$cloudflareUrl","token":"fixture-cf-token","transport":"cloudflare"},
            {"id":"direct","url":"$directUrl","token":"fixture-direct-token","transport":"direct"}]}}"""
            server.enqueue(response(200, payload))
            server.enqueue(response(200, trojanPayload))
            val fixture = fixture(now = 3_000L, httpClient = localMirrorClient())

            fixture.coordinator.refresh("subscription-group")
            val result = fixture.coordinator.refresh("subscription-group")

            assertTrue(result is SubscriptionRefreshResult.Updated)
            assertEquals("/subscription", server.takeRequest().url.encodedPath)
            val refreshed = server.takeRequest()
            assertEquals("/direct-device", refreshed.url.encodedPath)
            assertEquals("Bearer fixture-direct-token", refreshed.headers["Authorization"])
            assertEquals(2, server.requestCount)
        }
}
