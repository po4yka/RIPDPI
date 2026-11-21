package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.MaxSubscriptionPayloadBytes
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshFailureTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `http failures map to typed lifecycle and retry policy`() =
        runTest {
            val cases =
                listOf(
                    FailureCase(
                        403,
                        SubscriptionLifecycleState.SUSPENDED,
                        SubscriptionRefreshFailure.REVOKED,
                        SubscriptionRefreshRunResult.SUCCESS,
                    ),
                    FailureCase(
                        404,
                        SubscriptionLifecycleState.ACTIVE,
                        SubscriptionRefreshFailure.UNREACHABLE,
                        SubscriptionRefreshRunResult.SUCCESS,
                    ),
                    FailureCase(
                        410,
                        SubscriptionLifecycleState.UNAVAILABLE,
                        SubscriptionRefreshFailure.INVALIDATED,
                        SubscriptionRefreshRunResult.SUCCESS,
                    ),
                    FailureCase(
                        429,
                        SubscriptionLifecycleState.ACTIVE,
                        SubscriptionRefreshFailure.RATE_LIMITED,
                        SubscriptionRefreshRunResult.RETRY,
                    ),
                    FailureCase(
                        503,
                        SubscriptionLifecycleState.ACTIVE,
                        SubscriptionRefreshFailure.SERVER_ERROR,
                        SubscriptionRefreshRunResult.RETRY,
                    ),
                )
            for (case in cases) {
                server.enqueue(response(case.httpCode))
                val fixture = fixture(now = 2_000L, initialLifecycle = SubscriptionLifecycleState.ACTIVE)

                val result = fixture.coordinator.refreshAll()

                val subscription =
                    fixture.repository
                        .list()
                        .single()
                        .subscription!!
                assertEquals("HTTP ${case.httpCode}", case.runResult, result)
                assertEquals("HTTP ${case.httpCode}", case.lifecycle, subscription.lifecycleState)
                assertEquals("HTTP ${case.httpCode}", case.failure, subscription.lastRefreshFailure)
            }
        }

    @Test
    fun `invalid payload preserves lifecycle and does not immediately retry`() =
        runTest {
            server.enqueue(response(200, "not a subscription"))
            val fixture = fixture(now = 2_000L, initialLifecycle = SubscriptionLifecycleState.ACTIVE)

            val result = fixture.coordinator.refreshAll()

            val subscription =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
            assertEquals(SubscriptionRefreshRunResult.SUCCESS, result)
            assertEquals(SubscriptionLifecycleState.ACTIVE, subscription.lifecycleState)
            assertEquals(SubscriptionRefreshFailure.PARSE_ERROR, subscription.lastRefreshFailure)
            assertEquals(0L, subscription.lastUpdated)
        }

    @Test
    fun `declared oversized payload is terminal without buffering`() =
        runTest {
            server.enqueue(response(200, "x".repeat((MaxSubscriptionPayloadBytes + 1L).toInt())))
            val fixture = fixture(now = 2_000L, initialLifecycle = SubscriptionLifecycleState.ACTIVE)

            val result = fixture.coordinator.refreshAll()
            val subscription =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!

            assertEquals(SubscriptionRefreshRunResult.SUCCESS, result)
            assertEquals(SubscriptionRefreshFailure.PAYLOAD_TOO_LARGE, subscription.lastRefreshFailure)
            assertEquals(SubscriptionLifecycleState.UNAVAILABLE, subscription.lifecycleState)
            assertTrue(subscription.lastRefreshFailure?.isTerminal == true)
        }

    @Test
    fun `chunked oversized payload is stopped at max plus one`() =
        runTest {
            val payload = Buffer().write(ByteArray((MaxSubscriptionPayloadBytes + 1L).toInt()) { 'x'.code.toByte() })
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .chunkedBody(payload, 8_192)
                    .build(),
            )
            val fixture = fixture(now = 2_000L, initialLifecycle = SubscriptionLifecycleState.ACTIVE)

            fixture.coordinator.refreshAll()

            assertEquals(
                SubscriptionRefreshFailure.PAYLOAD_TOO_LARGE,
                fixture.repository
                    .list()
                    .single()
                    .subscription
                    ?.lastRefreshFailure,
            )
        }

    @Test
    fun `transport failure preserves lifecycle and requests retry`() =
        runTest {
            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            val fixture = fixture(now = 2_000L, initialLifecycle = SubscriptionLifecycleState.ACTIVE)

            val result = fixture.coordinator.refreshAll()

            val subscription =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
            assertEquals(SubscriptionRefreshRunResult.RETRY, result)
            assertEquals(SubscriptionLifecycleState.ACTIVE, subscription.lifecycleState)
            assertEquals(SubscriptionRefreshFailure.UNREACHABLE, subscription.lastRefreshFailure)
        }

    @Test
    fun `retry result aggregates across terminal and transient failures`() =
        runTest {
            server.enqueue(response(404))
            server.enqueue(response(503))
            val fixture = fixture(now = 2_000L, initialLifecycle = SubscriptionLifecycleState.ACTIVE)
            fixture.repository.add(
                subscriptionGroup(
                    id = "second-subscription",
                    order = 1,
                    lifecycleState = SubscriptionLifecycleState.ACTIVE,
                ),
            )

            val result = fixture.coordinator.refreshAll()

            assertEquals(SubscriptionRefreshRunResult.RETRY, result)
            assertEquals(
                setOf(SubscriptionRefreshFailure.UNREACHABLE, SubscriptionRefreshFailure.SERVER_ERROR),
                fixture.repository
                    .list()
                    .mapNotNull { it.subscription?.lastRefreshFailure }
                    .toSet(),
            )
        }

    private data class FailureCase(
        val httpCode: Int,
        val lifecycle: SubscriptionLifecycleState,
        val failure: SubscriptionRefreshFailure,
        val runResult: SubscriptionRefreshRunResult,
    )
}
