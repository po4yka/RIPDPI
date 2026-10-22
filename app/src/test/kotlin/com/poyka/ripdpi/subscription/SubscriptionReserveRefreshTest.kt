package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.testMutationPreparationSource
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
    fun `accepted refresh replaces removed reserves and reports only usable members`() =
        runTest {
            server.enqueue(response(200, "$trojanPayload\n$unsupportedPayload"))
            val fixture = fixture(now = 3_000L, initialMembers = listOf(reserve("removed")))

            val result = fixture.coordinator.refresh("subscription-group")
            val stored = fixture.repository.list().single()

            assertEquals(SubscriptionRefreshResult.Updated(1), result)
            assertEquals(1, stored.members.size)
            assertEquals("relay.example.com", (stored.members.single() as ProxyProfile.Trojan).server)
            assertEquals(3_000L, stored.subscription!!.lastUpdated)
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
            boundary.beforeDelivery = { fixture.repository.update(replacement) }

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh(replacement.id))
            assertEquals(replacement, fixture.repository.list().single())
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
            boundary.beforeDelivery = { fixture.repository.update(replacement) }

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh(replacement.id))
            assertEquals(replacement, fixture.repository.list().single())
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

    private val unsupportedPayload =
        "tuic://22222222-2222-2222-2222-222222222222:fixture@unsupported.example:443#unsupported"
}
