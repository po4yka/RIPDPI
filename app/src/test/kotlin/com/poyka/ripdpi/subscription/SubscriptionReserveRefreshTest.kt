package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.testMutationPreparationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SubscriptionReserveRefreshTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `unsupported response preserves the accepted reserves and update timestamp`() =
        runTest {
            server.enqueue(response(200, unsupportedPayload))
            val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("saved")))
            val original =
                fixture.repository.list().single().let {
                    it.copy(
                        failover = SelectorFailover("https://probe.example/", 60, 50),
                        subscription = it.subscription!!.copy(lastUpdated = 1_000L),
                    )
                }
            fixture.repository.update(original)

            val result = fixture.coordinator.refresh(original.id)
            val stored = fixture.repository.list().single()

            assertEquals(SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.PARSE_ERROR, false), result)
            assertEquals(original.members, stored.members)
            assertEquals(original.failover, stored.failover)
            assertEquals(1_000L, stored.subscription!!.lastUpdated)
            assertEquals(SubscriptionRefreshFailure.PARSE_ERROR, stored.subscription!!.lastRefreshFailure)
        }

    @Test
    fun `fully supported refresh replaces removed reserves`() =
        runTest {
            server.enqueue(response(200, trojanPayload))
            val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("removed")))

            val result = fixture.coordinator.refresh("subscription-group")
            val stored = fixture.repository.list().single()

            assertEquals(SubscriptionRefreshResult.Updated(1), result)
            assertEquals(1, stored.members.size)
            assertEquals("relay.example.com", (stored.members.single() as ProxyProfile.Trojan).server)
            assertEquals(3_000L, stored.subscription!!.lastUpdated)
        }

    @Test
    fun `malformed VLESS fields in sing-box and Clash preserve the accepted group`() =
        runTest {
            val payloads =
                listOf(
                    realitySingBoxPayload.replace(validUuid, "malformed-uuid"),
                    realitySingBoxPayload.replace(validKey, "malformed-key"),
                    realityClashPayload.replace(validUuid, "malformed-uuid"),
                    realityClashPayload.replace(validKey, "malformed-key"),
                )
            payloads.forEach { assertRejectedRefreshPreservesGroup(it) }
        }

    @Test
    fun `unavailable native VLESS XHTTP combinations preserve the accepted group`() =
        runTest {
            val tlsAuto =
                """{"outbounds":[{"type":"vless","tag":"tls-auto","server":"relay.example","server_port":443,
                  "uuid":"$validUuid","flow":"","tls":{"enabled":true,"server_name":"target.example"},
                  "transport":{"type":"xhttp","path":"/supported","mode":"auto"}}]}"""
            val xhttp = """"transport":{"type":"xhttp","path":"/supported","mode":"stream-one"},"""
            val realityVision = realitySingBoxPayload.replace("\"server_port\":443,", "\"server_port\":443,$xhttp")
            listOf(tlsAuto, realityVision).forEach { assertRejectedRefreshPreservesGroup(it) }
        }

    private suspend fun assertRejectedRefreshPreservesGroup(payload: String) {
        val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("saved")))
        fixture.repository.update(
            fixture.repository.list().single().let {
                it.copy(
                    isSelector = true,
                    failover = SelectorFailover("https://probe.example/", 60, 50),
                    cloudflareMemberIds = setOf("saved"),
                    subscription = it.subscription!!.copy(lastUpdated = 1_000L),
                )
            },
        )
        val before = fixture.repository.list().single()
        val beforeSubscription = checkNotNull(before.subscription)
        server.enqueue(response(200, payload))

        val result = fixture.coordinator.refresh(before.id)
        val after = fixture.repository.list().single()

        assertEquals(SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.PARSE_ERROR, false), result)
        assertEquals(
            before,
            after.copy(subscription = before.subscription, ownershipGeneration = before.ownershipGeneration),
        )
        assertEquals(
            beforeSubscription,
            after.subscription?.copy(
                lastRefreshAttemptAtEpochMillis = beforeSubscription.lastRefreshAttemptAtEpochMillis,
                lastRefreshFailure = beforeSubscription.lastRefreshFailure,
            ),
        )
        assertTrue(
            fixture.awgRepository
                .observeProfiles()
                .first()
                .isEmpty(),
        )
    }

    @Test
    fun `response from a replaced source cannot overwrite the current catalogue`() =
        runTest {
            server.enqueue(response(200, trojanPayload))
            val boundary = ResponseBoundary()
            val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("saved")), httpClient = boundary.client)
            val replacement =
                fixture.repository.list().single().let {
                    it.copy(
                        members = listOf(reserve("replacement")),
                        subscription = it.subscription!!.copy(link = server.url("/replacement").toString()),
                    )
                }
            var storedReplacement: ProxyGroup? = null
            boundary.beforeDelivery = {
                fixture.repository.update(replacement)
                storedReplacement = fixture.repository.list().single()
            }

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh(replacement.id))
            assertEquals(checkNotNull(storedReplacement), fixture.repository.list().single())
        }

    @Test
    fun `failure from a replaced source cannot mark the current subscription invalid`() =
        runTest {
            server.enqueue(response(410))
            val boundary = ResponseBoundary()
            val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("saved")), httpClient = boundary.client)
            val replacement =
                fixture.repository.list().single().let {
                    it.copy(subscription = it.subscription!!.copy(link = server.url("/replacement").toString()))
                }
            var storedReplacement: ProxyGroup? = null
            boundary.beforeDelivery = {
                fixture.repository.update(replacement)
                storedReplacement = fixture.repository.list().single()
            }

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh(replacement.id))
            assertEquals(checkNotNull(storedReplacement), fixture.repository.list().single())
        }

    @Test
    fun `an older response cannot overwrite a refresh that already installed its reserves`() =
        runTest {
            server.enqueue(response(200, trojanPayload))
            server.enqueue(response(200, "trojan://fresh-password@newer.example:443#newer"))
            val boundary = ResponseBoundary()
            val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("saved")), httpClient = boundary.client)
            boundary.beforeDelivery = {
                assertEquals(SubscriptionRefreshResult.Updated(1), fixture.coordinator.refresh("subscription-group"))
            }

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh("subscription-group"))
            val member =
                fixture.repository
                    .list()
                    .single()
                    .members
                    .single() as ProxyProfile.Trojan
            assertEquals("newer.example", member.server)
            assertEquals("fresh-password", member.password)
        }

    @Test
    fun `response arriving after deletion does not recreate the group or report success`() =
        runTest {
            server.enqueue(response(200, trojanPayload))
            val boundary = ResponseBoundary()
            val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("saved")), httpClient = boundary.client)
            boundary.beforeDelivery = {
                fixture.repository.delete(
                    testMutationPreparationSource().captureMutation(ProfileMutationOrigin.ExplicitDeletion),
                    "subscription-group",
                )
            }

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh("subscription-group"))
            assertTrue(fixture.repository.list().isEmpty())
        }

    private fun reserve(id: String) =
        ProxyProfile.Trojan(
            id = id,
            groupId = "subscription-group",
            displayName = id,
            server = "$id.example",
            serverPort = 443,
            password = "fixture-password",
        )

    /** Interleave a mutation after a real HTTP response but before the coordinator can install it. */
    private class ResponseBoundary {
        var beforeDelivery: suspend () -> Unit = {}
        private val delivered = AtomicBoolean(false)
        val client =
            OkHttpClient
                .Builder()
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (delivered.compareAndSet(false, true)) runBlocking { beforeDelivery() }
                    response
                }.build()
    }

    private val validUuid = "550e8400-e29b-41d4-a716-446655440000"
    private val validKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val realitySingBoxPayload =
        """{"outbounds":[{"type":"vless","tag":"reality","server":"relay.example","server_port":443,
          "uuid":"$validUuid","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"target.example",
          "reality":{"enabled":true,"public_key":"$validKey","short_id":"abcd"}}}]}"""
    private val realityClashPayload =
        """proxies:
          |  - name: reality
          |    type: vless
          |    server: relay.example
          |    port: 443
          |    uuid: $validUuid
          |    sni: target.example
          |    flow: xtls-rprx-vision
          |    reality-opts: {public-key: $validKey, short-id: abcd}
        """.trimMargin()

    private val unsupportedPayload =
        "tuic://22222222-2222-2222-2222-222222222222:fixture@unsupported.example:443#unsupported"
}
