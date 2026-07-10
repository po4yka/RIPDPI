package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupBlobStore
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.SharedPreferencesProxyGroupRepository
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubscriptionRefreshCoordinatorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun `valid payload persists members accounting and active lifecycle`() =
        runTest {
            val now = 1_800_000_000_000L
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .setHeader("Subscription-Userinfo", "upload=100; download=200; total=1000; expire=1900000000")
                    .body(TrojanPayload)
                    .build(),
            )
            val fixture = fixture(now)

            val result = fixture.coordinator.refreshAll()

            val subscription =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
            assertEquals(SubscriptionRefreshRunResult.SUCCESS, result)
            assertEquals(SubscriptionLifecycleState.ACTIVE, subscription.lifecycleState)
            assertNull(subscription.lastRefreshFailure)
            assertEquals(now, subscription.lastUpdated)
            assertEquals(now, subscription.lastRefreshAttemptAtEpochMillis)
            assertEquals(300L, subscription.bytesUsed)
            assertEquals(700L, subscription.bytesRemaining)
            assertEquals(1_900_000_000L, subscription.expiryDate)
            assertEquals(
                1,
                fixture.repository
                    .list()
                    .single()
                    .members.size,
            )
        }

    @Test
    fun `past userinfo expiry is terminal even with a valid payload`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .setHeader("Subscription-Userinfo", "expire=1700000000")
                    .body(TrojanPayload)
                    .build(),
            )
            val fixture = fixture(now = 1_800_000_000_000L)

            fixture.coordinator.refreshAll()

            val subscription =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
            assertEquals(SubscriptionLifecycleState.EXPIRED, subscription.lifecycleState)
            assertEquals(SubscriptionRefreshFailure.EXPIRED, subscription.lastRefreshFailure)
        }

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
                        SubscriptionLifecycleState.UNAVAILABLE,
                        SubscriptionRefreshFailure.UNAVAILABLE,
                        SubscriptionRefreshRunResult.SUCCESS,
                    ),
                    FailureCase(
                        410,
                        SubscriptionLifecycleState.EXPIRED,
                        SubscriptionRefreshFailure.EXPIRED,
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
            assertEquals(SubscriptionRefreshFailure.INVALID_PAYLOAD, subscription.lastRefreshFailure)
            assertEquals(0L, subscription.lastUpdated)
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
            assertEquals(SubscriptionRefreshFailure.NETWORK_ERROR, subscription.lastRefreshFailure)
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
                setOf(SubscriptionRefreshFailure.UNAVAILABLE, SubscriptionRefreshFailure.SERVER_ERROR),
                fixture.repository
                    .list()
                    .mapNotNull { it.subscription?.lastRefreshFailure }
                    .toSet(),
            )
        }

    @Test
    fun `valid refresh recovers a prior terminal state and publishes current groups`() =
        runTest {
            server.enqueue(response(200, TrojanPayload))
            val fixture =
                fixture(
                    now = 2_000L,
                    initialLifecycle = SubscriptionLifecycleState.UNAVAILABLE,
                    initialFailure = SubscriptionRefreshFailure.UNAVAILABLE,
                )

            fixture.coordinator.refreshAll()

            val subscription =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
            assertEquals(SubscriptionLifecycleState.ACTIVE, subscription.lifecycleState)
            assertNull(subscription.lastRefreshFailure)
            assertEquals(1, fixture.publisher.publications.size)
            assertEquals(
                SubscriptionLifecycleState.ACTIVE,
                fixture.publisher.publications
                    .single()
                    .single()
                    .subscription
                    ?.lifecycleState,
            )
        }

    private suspend fun fixture(
        now: Long,
        initialLifecycle: SubscriptionLifecycleState = SubscriptionLifecycleState.UNKNOWN,
        initialFailure: SubscriptionRefreshFailure? = null,
    ): Fixture {
        val repository: ProxyGroupRepository = SharedPreferencesProxyGroupRepository(FakeBlobStore())
        repository.add(subscriptionGroup("subscription-group", 0, initialLifecycle, initialFailure))
        val publisher = RecordingPublisher()
        val coordinator =
            SubscriptionRefreshCoordinator(
                repository = repository,
                awgProfileRepository = AwgProfileRepository(FakeAwgDao(), FakeAwgCredentialStore()),
                signalPublisher = publisher,
                httpClient = OkHttpClient(),
                clockMillis = { now },
                testOnly = Unit,
            )
        return Fixture(repository, coordinator, publisher)
    }

    private fun subscriptionGroup(
        id: String,
        order: Int,
        lifecycleState: SubscriptionLifecycleState,
        failure: SubscriptionRefreshFailure? = null,
    ): ProxyGroup =
        ProxyGroup(
            id = id,
            name = "Fixture subscription",
            type = ProxyGroupType.SUBSCRIPTION,
            order = order,
            isSelector = false,
            subscription =
                Subscription(
                    link = server.url("/subscription").toString(),
                    autoUpdate = true,
                    autoUpdateDelay = 60L,
                    lifecycleState = lifecycleState,
                    lastRefreshFailure = failure,
                ),
        )

    private fun response(
        code: Int,
        body: String = "",
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build()

    private data class Fixture(
        val repository: ProxyGroupRepository,
        val coordinator: SubscriptionRefreshCoordinator,
        val publisher: RecordingPublisher,
    )

    private data class FailureCase(
        val httpCode: Int,
        val lifecycle: SubscriptionLifecycleState,
        val failure: SubscriptionRefreshFailure,
        val runResult: SubscriptionRefreshRunResult,
    )

    private companion object {
        const val TrojanPayload = "trojan://fixture-password@relay.example.com:443#fixture"
    }
}

private class RecordingPublisher : SubscriptionSignalPublisher {
    val publications = mutableListOf<List<ProxyGroup>>()

    override fun publish(
        groups: List<ProxyGroup>,
        nowEpochMillis: Long,
    ) {
        publications += groups
    }
}

private class FakeBlobStore : ProxyGroupBlobStore {
    private var value: String? = null

    override fun read(): String? = value

    override fun write(json: String) {
        value = json
    }

    override fun clear() {
        value = null
    }
}

private class FakeAwgDao : AwgProfileDao {
    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = flowOf(emptyList())

    override suspend fun getProfile(id: String): AwgProfileEntity? = null

    override suspend fun upsertProfile(profile: AwgProfileEntity) = Unit

    override suspend fun deleteProfile(profile: AwgProfileEntity) = Unit
}

private class FakeAwgCredentialStore : AwgCredentialStore {
    override suspend fun load(profileId: String): AwgSecrets? = null

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) = Unit

    override suspend fun clear(profileId: String) = Unit
}
