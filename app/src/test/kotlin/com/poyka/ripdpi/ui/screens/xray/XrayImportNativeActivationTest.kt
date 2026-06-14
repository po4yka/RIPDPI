package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.subscription.XrayConfigImportParser
import com.poyka.ripdpi.data.subscription.XrayConfigImportResult
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.ui.screens.proxyimport.NativeRelayProfileActivator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration coverage for the full Xray import path: parse an xray-core config
 * with [XrayConfigImportParser], then activate the translated [ProxyProfile] via
 * [NativeRelayProfileActivator] and assert it lands as the exact native relay
 * configuration the engine consumes (relay enabled, kind, endpoint, secrets).
 *
 * The resulting relay config is the same shape the native relay backend builder
 * is exercised against in `ripdpi-relay-core` (the `vless_reality` / `trojan`
 * `build_backend` tests) — so a translated profile yields a runnable backend
 * rather than a config that fails at connect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XrayImportNativeActivationTest {
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val pbk = "AbCdEf0123456789AbCdEf0123456789AbCdEf01234"

    private fun firstProfile(input: String): ProxyProfile {
        val result = XrayConfigImportParser.parse(input, groupId = "xray-import")
        assertTrue(result is XrayConfigImportResult.Translated)
        return (result as XrayConfigImportResult.Translated).profiles.first()
    }

    @Test
    fun `translated vless reality config activates a runnable native relay`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            val config =
                """
                { "outbounds": [ {
                  "tag": "tokyo", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                    "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
                  "streamSettings": { "network": "tcp", "security": "reality",
                    "realitySettings": { "publicKey": "$pbk", "serverName": "www.cloudflare.com", "shortId": "ab12" } }
                } ] }
                """.trimIndent()

            activator.activate(firstProfile(config))

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val credentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindVlessReality, settings.relayKind)
            assertEquals("edge.example.com", settings.relayServer)
            assertEquals("www.cloudflare.com", settings.relayServerName)
            assertEquals(pbk, settings.relayRealityPublicKey)
            assertEquals(RelayKindVlessReality, relayProfile?.kind)
            assertEquals(RelayVlessTransportRealityTcp, relayProfile?.vlessTransport)
            assertEquals(pbk, relayProfile?.realityPublicKey)
            assertEquals(uuid, credentials?.vlessUuid)
            assertEquals(1, repository.list().size)
            assertEquals(ProxyGroupType.BASIC, repository.list().single().type)
        }

    @Test
    fun `translated trojan config activates a runnable native relay`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            val config =
                """
                { "outbounds": [ { "protocol": "trojan", "settings": { "servers": [
                  { "address": "tj.example", "port": 443, "password": "tj-secret" } ] } } ] }
                """.trimIndent()

            activator.activate(firstProfile(config))

            val settings = settingsRepository.snapshot()
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindTrojan, settings.relayKind)
            assertEquals("tj.example", settings.relayServer)
            assertEquals("tj-secret", relayCredentialStore.load(DefaultRelayProfileId)?.trojanPassword)
        }
}

private class FakeProxyGroupRepository : ProxyGroupRepository {
    private val state = MutableStateFlow<List<ProxyGroup>>(emptyList())

    override suspend fun add(group: ProxyGroup) {
        state.value = state.value.filterNot { it.id == group.id } + group
    }

    override suspend fun update(group: ProxyGroup) {
        state.value = state.value.map { if (it.id == group.id) group else it }
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun list(): List<ProxyGroup> = state.value

    override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
}

private class FakeRelayProfileStore : RelayProfileStore {
    private val profiles = mutableMapOf<String, RelayProfileRecord>()

    override suspend fun load(profileId: String): RelayProfileRecord? = profiles[profileId]

    override suspend fun list(): List<RelayProfileRecord> = profiles.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        profiles[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        profiles.remove(profileId)
    }
}

private class FakeRelayCredentialStore : RelayCredentialStore {
    private val credentials = mutableMapOf<String, RelayCredentialRecord>()

    override suspend fun load(profileId: String): RelayCredentialRecord? = credentials[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        this.credentials[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        credentials.remove(profileId)
    }
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun snapshot(): AppSettings = settings.first()

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}
