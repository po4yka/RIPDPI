package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRecoveryTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `manual refresh recovers every cached terminal failure at the same URL`() =
        runTest {
            for (failure in SubscriptionRefreshFailure.entries.filter { it.isTerminal }) {
                val fixture = fixture(2_000L, SubscriptionLifecycleState.UNAVAILABLE, failure)
                server.enqueue(response(200, trojanPayload))

                assertTrue(fixture.coordinator.refresh("subscription-group") is SubscriptionRefreshResult.Updated)

                val stored = fixture.repository.list().single()
                assertEquals(SubscriptionLifecycleState.ACTIVE, stored.subscription?.lifecycleState)
                assertNull(stored.subscription?.lastRefreshFailure)
                assertEquals(1, stored.members.size)
                assertEquals(server.url("/subscription").toString(), stored.subscription?.link)
            }
            assertEquals(4, server.requestCount)
        }

    @Test
    fun `manual renewal replaces stale token expiry with future or absent expiry`() =
        runTest {
            for (payload in listOf(ripdpiPayload, trojanPayload)) {
                val fixture = fixture(2_000L)
                fixture.repository.updateSubscription("subscription-group") {
                    it.copy(tokenExpiresAtEpochMillis = 1_000L)
                }
                server.enqueue(response(200, payload))

                assertTrue(fixture.coordinator.refresh("subscription-group") is SubscriptionRefreshResult.Updated)

                val expiry =
                    fixture.repository
                        .list()
                        .single()
                        .subscription
                        ?.tokenExpiresAtEpochMillis
                assertEquals(if (payload == ripdpiPayload) 1_798_761_599_000L else null, expiry)
                val request = server.takeRequest()
                assertNull(request.headers["If-None-Match"])
                assertNull(request.headers["If-Modified-Since"])
            }
        }

    @Test
    fun `background refresh still skips expired and terminal subscriptions`() =
        runTest {
            val fixture = fixture(2_000L, initialFailure = SubscriptionRefreshFailure.REVOKED)
            assertEquals(SubscriptionRefreshRunResult.SUCCESS, fixture.coordinator.refreshAll())
            fixture.repository.updateSubscription("subscription-group") {
                it.copy(lastRefreshFailure = null, tokenExpiresAtEpochMillis = 1_000L)
            }
            assertEquals(SubscriptionRefreshRunResult.SUCCESS, fixture.coordinator.refreshAll())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `manual recovery still rejects current expired content and terminal HTTP responses`() =
        runTest {
            for (code in listOf(200, 403, 410, 304)) {
                val fixture = fixture(2_000L, initialFailure = SubscriptionRefreshFailure.EXPIRED)
                fixture.repository.updateSubscription("subscription-group") {
                    it.copy(tokenExpiresAtEpochMillis = 1_000L)
                }
                server.enqueue(
                    response(
                        code,
                        if (code ==
                            200
                        ) {
                            ripdpiPayload.replace("2026-12-31T23:59:59Z", "1970-01-01T00:00:01Z")
                        } else {
                            ""
                        },
                    ),
                )

                assertTrue(fixture.coordinator.refresh("subscription-group") is SubscriptionRefreshResult.Failed)
                assertEquals(
                    0L,
                    fixture.repository
                        .list()
                        .single()
                        .subscription
                        ?.lastUpdated,
                )
                assertEquals(
                    1_000L,
                    fixture.repository
                        .list()
                        .single()
                        .subscription
                        ?.tokenExpiresAtEpochMillis,
                )
            }
            assertEquals(4, server.requestCount)
        }

    @Test
    fun `failed manual recheck keeps automatic retries stopped until recovery succeeds`() =
        runTest {
            val fixture = fixture(2_000L, initialFailure = SubscriptionRefreshFailure.REVOKED)
            server.enqueue(response(503))

            assertEquals(
                SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.SERVER_ERROR, true),
                fixture.coordinator.refresh("subscription-group"),
            )
            assertEquals(
                SubscriptionRefreshFailure.REVOKED,
                fixture.repository
                    .list()
                    .single()
                    .subscription
                    ?.lastRefreshFailure,
            )
            assertEquals(SubscriptionRefreshRunResult.SUCCESS, fixture.coordinator.refreshAll())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `manual recovery never replays a bootstrap with expired metadata`() =
        runTest {
            val fixture = fixture(2_000L, initialFailure = SubscriptionRefreshFailure.EXPIRED)
            fixture.repository.updateSubscription("subscription-group") {
                it.copy(kind = SubscriptionKind.BOOTSTRAP, consumedAt = 500L, tokenExpiresAtEpochMillis = 1_000L)
            }
            assertEquals(
                SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.INVALIDATED, false),
                fixture.coordinator.refresh("subscription-group"),
            )
            assertEquals(0, server.requestCount)
        }
}
