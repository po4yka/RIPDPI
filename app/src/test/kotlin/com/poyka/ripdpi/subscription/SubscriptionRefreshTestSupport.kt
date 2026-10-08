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
import kotlinx.coroutines.flow.MutableStateFlow
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
        val repository: ProxyGroupRepository =
            SharedPreferencesProxyGroupRepository(
                FakeBlobStore(),
                com.poyka.ripdpi.data
                    .testMutationPreparationSource(),
            )
        repository.add(
            subscriptionGroup("subscription-group", 0, initialLifecycle, initialFailure).copy(
                packageRoutingRules = initialRules,
                members = initialMembers,
            ),
        )
        val publisher = SubscriptionRecordingPublisher()
        val awgDao = FakeAwgDao()
        val awgCredentials = FakeAwgCredentialStore()
        val awgRepository =
            AwgProfileRepository(
                awgDao,
                awgCredentials,
                com.poyka.ripdpi.data.awg
                    .TestDirectAwgProfileMutationCoordinator(awgDao, awgCredentials),
            )
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
    private val rows = MutableStateFlow<List<AwgProfileEntity>>(emptyList())

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = rows

    override suspend fun allProfiles(): List<AwgProfileEntity> = rows.value

    override suspend fun getProfile(id: String): AwgProfileEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun upsertProfile(profile: AwgProfileEntity) {
        rows.value = rows.value.filterNot { it.id == profile.id } + profile
    }

    override suspend fun deleteProfile(profile: AwgProfileEntity) {
        rows.value = rows.value.filterNot { it.id == profile.id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

private class FakeAwgCredentialStore : AwgCredentialStore {
    private val values = mutableMapOf<String, AwgSecrets>()

    override suspend fun load(profileId: String): AwgSecrets? = values[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        values[profileId] = secrets
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }
}
