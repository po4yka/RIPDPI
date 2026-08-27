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
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before

abstract class SubscriptionRefreshTestSupport {
    protected lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    protected suspend fun fixture(
        now: Long,
        initialLifecycle: SubscriptionLifecycleState = SubscriptionLifecycleState.UNKNOWN,
        initialFailure: SubscriptionRefreshFailure? = null,
        initialRules: List<PackageRoutingRule> = emptyList(),
        initialMembers: List<ProxyProfile> = emptyList(),
        httpClient: OkHttpClient = OkHttpClient(),
    ): Fixture {
        val repository: ProxyGroupRepository = SharedPreferencesProxyGroupRepository(FakeBlobStore())
        repository.add(
            subscriptionGroup("subscription-group", 0, initialLifecycle, initialFailure).copy(
                packageRoutingRules = initialRules,
                members = initialMembers,
            ),
        )
        val publisher = SubscriptionRecordingPublisher()
        val coordinator =
            SubscriptionRefreshCoordinator(
                repository = repository,
                awgProfileRepository = AwgProfileRepository(FakeAwgDao(), FakeAwgCredentialStore()),
                signalPublisher = publisher,
                httpClient = httpClient,
                clockMillis = { now },
                testOnly = Unit,
            )
        return Fixture(repository, coordinator, publisher)
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
    )

    protected val trojanPayload = "trojan://fixture-password@relay.example.com:443#fixture"
    protected val ripdpiPayload =
        """
        {
          "outbounds": [
            {"type":"shadowsocks","tag":"Fresh","server":"fresh.example","server_port":443,
             "method":"aes-256-gcm","password":"fixture"}
          ],
          "ripdpi": {"schema_version":1,"amneziawg":[],"hysteria_extras":{},"expires":"2026-12-31T23:59:59Z"}
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
