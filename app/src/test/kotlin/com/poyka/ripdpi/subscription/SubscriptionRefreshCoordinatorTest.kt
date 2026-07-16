package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupBlobStore
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SharedPreferencesProxyGroupRepository
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.data.subscription.MaxSubscriptionPayloadBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
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
    fun `manual refresh atomically persists token and credential expiry`() =
        runTest {
            val now = 1_700_000_000_000L
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .setHeader("Subscription-Userinfo", "expire=1893456000")
                    .body(RipdpiPayload)
                    .build(),
            )
            val fixture = fixture(now)

            val result = fixture.coordinator.refresh("subscription-group")
            val stored = fixture.repository.list().single()

            assertTrue(result is SubscriptionRefreshResult.Updated)
            assertEquals(1_798_761_599_000L, stored.subscription?.tokenExpiresAtEpochMillis)
            assertEquals(1_893_456_000L, stored.subscription?.expiryDate)
            assertEquals(now, stored.subscription?.lastUpdated)
            assertNull(stored.subscription?.lastRefreshFailure)
            assertEquals(1, stored.members.size)
        }

    @Test
    fun `successful refresh replaces package rules including empty`() =
        runTest {
            server.enqueue(response(200, singBoxPackageRoutePayload("direct")))
            server.enqueue(response(200, singBoxPackageRoutePayload("select")))
            server.enqueue(response(200, singBoxPackageRoutePayload(null)))
            val fixture = fixture(now = 3_000L)

            fixture.coordinator.refresh("subscription-group")
            assertEquals(
                PackageRoutingAction.BYPASS,
                fixture.repository
                    .list()
                    .single()
                    .packageRoutingRules
                    .single()
                    .action,
            )

            fixture.coordinator.refresh("subscription-group")
            assertEquals(
                PackageRoutingAction.VIA_TUN,
                fixture.repository
                    .list()
                    .single()
                    .packageRoutingRules
                    .single()
                    .action,
            )

            fixture.coordinator.refresh("subscription-group")
            assertTrue(
                fixture.repository
                    .list()
                    .single()
                    .packageRoutingRules
                    .isEmpty(),
            )
        }

    @Test
    fun `package route parse error preserves previously stored rules`() =
        runTest {
            val existingRule =
                PackageRoutingRule(
                    packageName = "com.previous.app",
                    action = PackageRoutingAction.BYPASS,
                    origin = PackageRoutingRuleOrigin.Subscription("subscription-group"),
                )
            server.enqueue(response(200, singBoxPackageRoutePayload("named-outbound")))
            val fixture = fixture(now = 3_000L, initialRules = listOf(existingRule))

            val result = fixture.coordinator.refresh("subscription-group")

            assertTrue(result is SubscriptionRefreshResult.Failed)
            result as SubscriptionRefreshResult.Failed
            assertEquals(SubscriptionRefreshFailure.PARSE_ERROR, result.failure)
            assertEquals(
                listOf(existingRule),
                fixture.repository
                    .list()
                    .single()
                    .packageRoutingRules,
            )
        }

    @Test
    fun `AWG-only refresh persists package rules with no group members`() =
        runTest {
            server.enqueue(response(200, AwgOnlyPackageRoutePayload))
            val staleMember =
                ProxyProfile.Trojan(
                    id = "stale-member",
                    groupId = "subscription-group",
                    displayName = "Stale relay",
                    server = "stale.example",
                    serverPort = 443,
                    password = "fixture",
                )
            val fixture = fixture(now = 3_000L, initialMembers = listOf(staleMember))

            val result = fixture.coordinator.refresh("subscription-group")

            assertTrue(result is SubscriptionRefreshResult.Updated)
            val stored = fixture.repository.list().single()
            assertTrue(stored.members.isEmpty())
            assertEquals("com.awg.app", stored.packageRoutingRules.single().packageName)
            assertEquals(PackageRoutingAction.VIA_TUN, stored.packageRoutingRules.single().action)
        }

    @Test
    fun `past userinfo expiry remains separate from token refresh state`() =
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
            assertEquals(SubscriptionLifecycleState.ACTIVE, subscription.lifecycleState)
            assertNull(subscription.lastRefreshFailure)
            assertEquals(1_700_000_000L, subscription.expiryDate)
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
        initialRules: List<PackageRoutingRule> = emptyList(),
        initialMembers: List<ProxyProfile> = emptyList(),
    ): Fixture {
        val repository: ProxyGroupRepository = SharedPreferencesProxyGroupRepository(FakeBlobStore())
        repository.add(
            subscriptionGroup("subscription-group", 0, initialLifecycle, initialFailure).copy(
                packageRoutingRules = initialRules,
                members = initialMembers,
            ),
        )
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

    private fun singBoxPackageRoutePayload(outbound: String?): String {
        val route =
            if (outbound == null) {
                ""
            } else {
                ""","route":{"rules":[{"package_name":["com.route.app"],"outbound":"$outbound"}]}"""
            }
        return buildString {
            append("{\"outbounds\":[{")
            append("\"type\":\"trojan\",\"tag\":\"node\",")
            append("\"server\":\"node.example\",\"server_port\":443,")
            append("\"password\":\"p\"}]")
            append(route)
            append('}')
        }
    }

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
        val RipdpiPayload =
            """
            {
              "outbounds": [
                {"type":"shadowsocks","tag":"Fresh","server":"fresh.example","server_port":443,
                 "method":"aes-256-gcm","password":"fixture"}
              ],
              "ripdpi": {"schema_version":1,"amneziawg":[],"hysteria_extras":{},"expires":"2026-12-31T23:59:59Z"}
            }
            """.trimIndent()
        val AwgOnlyPackageRoutePayload =
            """
            {
              "outbounds": [],
              "route": {"rules": [
                {"package_name": ["com.awg.app"], "outbound": "select"}
              ]},
              "ripdpi": {
                "schema_version": 1,
                "amneziawg": [{
                  "tag": "fixture-awg",
                  "private_key": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                  "address": ["10.8.0.2/32"],
                  "dns": ["1.1.1.1"],
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

    override suspend fun allProfiles(): List<AwgProfileEntity> = emptyList()

    override suspend fun getProfile(id: String): AwgProfileEntity? = null

    override suspend fun upsertProfile(profile: AwgProfileEntity) = Unit

    override suspend fun deleteProfile(profile: AwgProfileEntity) = Unit

    override suspend fun deleteAll() = Unit
}

private class FakeAwgCredentialStore : AwgCredentialStore {
    override suspend fun load(profileId: String): AwgSecrets? = null

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) = Unit

    override suspend fun clear(profileId: String) = Unit
}
