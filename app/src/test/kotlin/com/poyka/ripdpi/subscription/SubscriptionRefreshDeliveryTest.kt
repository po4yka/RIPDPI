package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.data.subscription.migrationSha256
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
          ],"ripdpi":{"schema_version":2,"cloudflare_outbound_tags":["edge"]}}"""
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
            "server_port":443,"password":"fixture-password"}],"ripdpi":{"schema_version":2,
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

    @Test
    fun `owned migration payload refreshes through authenticated mirror before stopped original`() =
        runTest {
            val mirrorUrl =
                server
                    .url("/mirror")
                    .newBuilder()
                    .scheme("https")
                    .build()
                    .toString()
            val updatedPayload = ownedMigrationPayload(mirrorUrl, updated = true)
            server.enqueue(response(200, ownedMigrationPayload(mirrorUrl, updated = false)))
            server.enqueue(response(200, updatedPayload))
            server.enqueue(response(503))
            val fixture = fixture(now = 3_000L, httpClient = localMirrorClient())
            assertTrue(fixture.coordinator.refresh("subscription-group") is SubscriptionRefreshResult.Updated)
            val imported = fixture.repository.list().single()
            assertEquals(listOf("old", "reserve"), imported.members.map { it.displayName })

            val result = fixture.coordinator.refresh("subscription-group")

            assertTrue("Independent refresh result=$result", result is SubscriptionRefreshResult.Updated)
            val updated = fixture.repository.list().single()
            assertEquals(listOf("reserve", "replacement"), updated.members.map { it.displayName })
            val delivery = requireNotNull(updated.subscription?.migration)
            assertEquals(listOf("replacement"), delivery.metadata.targetTags)
            assertEquals(migrationSha256(updatedPayload.toByteArray()), delivery.payloadSha256)
            assertNull(delivery.pathTest)
            assertEquals("/subscription", server.takeRequest().url.encodedPath)
            val request = server.takeRequest()
            assertEquals("/mirror", request.url.encodedPath)
            assertEquals(listOf("Bearer ${"a".repeat(64)}"), request.headers.values("Authorization"))
            assertEquals(2, server.requestCount)
        }

    /** Same fields and topology as the owned Android runner's two migration revisions. */
    private fun ownedMigrationPayload(
        mirrorUrl: String,
        updated: Boolean,
    ): String {
        val tags = if (updated) listOf("reserve", "replacement") else listOf("old", "reserve")
        val outbounds =
            tags.joinToString { tag ->
                val number = listOf("old", "reserve", "replacement").indexOf(tag) + 1
                """{"type":"vless","tag":"$tag","server":"127.0.0.1","server_port":${4430 + number},
                "uuid":"00000000-0000-0000-0000-00000000000$number","flow":"xtls-rprx-vision",
                "tls":{"enabled":true,"server_name":"fixture.test","utls":{"enabled":true,"fingerprint":"chrome"},
                "reality":{"enabled":true,"public_key":"${"A".repeat(43)}","short_id":"ab12"}}}"""
            }
        val migration =
            if (updated) {
                """, "migration":{"schema_version":1,"migration_id":"00000000-0000-0000-0000-000000000010",
                "delivery_id":"00000000-0000-0000-0000-000000000011","receipt_key":"${"b".repeat(64)}",
                "target_tags":["replacement"],"awg_previous_tags":{}}"""
            } else {
                ""
            }
        return """{"outbounds":[{"type":"selector","tag":"select",
        "outbounds":["${tags[0]}","${tags[1]}"],"default":"${tags[0]}"},$outbounds],
        "ripdpi":{"schema_version":2,"subscription_mirrors":[{"id":"independent","transport":"direct",
        "url":"$mirrorUrl","token":"${"a".repeat(64)}"}]$migration}}"""
    }
}
