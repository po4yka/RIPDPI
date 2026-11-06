package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
abstract class SubscriptionRefreshTestSupport {
    private val databases = mutableListOf<com.poyka.ripdpi.data.testing.RoomProfileFixture>()
    protected lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        databases.forEach { it.close() }
        databases.clear()
    }

    protected suspend fun fixture(
        now: Long,
        initialLifecycle: SubscriptionLifecycleState = SubscriptionLifecycleState.UNKNOWN,
        initialFailure: SubscriptionRefreshFailure? = null,
        initialRules: List<PackageRoutingRule> = emptyList(),
        initialMembers: List<ProxyProfile> = emptyList(),
        httpClient: OkHttpClient = OkHttpClient(),
    ): Fixture {
        val persistence =
            com.poyka.ripdpi.data.testing
                .RoomProfileFixture(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    com.poyka.ripdpi.data
                        .testPauseAuthority(),
                ).also { databases += it }
        persistence.initialize()
        val repository = persistence.groups
        repository.add(
            subscriptionGroup("subscription-group", 0, initialLifecycle, initialFailure).copy(
                packageRoutingRules = initialRules,
                members = initialMembers,
            ),
        )
        val publisher = SubscriptionRecordingPublisher()
        val awgRepository = persistence.awgRepository
        val coordinator =
            SubscriptionRefreshCoordinator(
                repository = repository,
                awgProfileRepository = awgRepository,
                signalPublisher = publisher,
                httpClient = httpClient,
                clockMillis = { now },
                testOnly = Unit,
            )
        return Fixture(repository, coordinator, publisher, awgRepository)
    }

    protected fun subscriptionGroup(
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

    protected fun response(
        code: Int,
        body: String = "",
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build()

    protected data class Fixture(
        val repository: ProxyGroupRepository,
        val coordinator: SubscriptionRefreshCoordinator,
        val publisher: SubscriptionRecordingPublisher,
        val awgRepository: AwgProfileRepository,
    )

    protected val trojanPayload = "trojan://fixture-password@relay.example.com:443#fixture"
    protected val ripdpiPayload =
        """
        {
          "outbounds": [
            {"type":"shadowsocks","tag":"Fresh","server":"fresh.example","server_port":443,
             "method":"aes-256-gcm","password":"fixture"}
          ],
          "ripdpi": {"schema_version":2,"amneziawg":[],"hysteria_extras":{},"expires":"2026-12-31T23:59:59Z"}
        }
        """.trimIndent()
}

class SubscriptionRecordingPublisher : SubscriptionSignalPublisher {
    val publications = mutableListOf<List<ProxyGroup>>()

    override fun publish(
        groups: List<ProxyGroup>,
        nowEpochMillis: Long,
    ) {
        publications += groups
    }
}
