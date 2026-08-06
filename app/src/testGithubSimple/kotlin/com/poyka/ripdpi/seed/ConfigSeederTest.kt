package com.poyka.ripdpi.seed

import android.app.Application
import android.content.Context
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Synthetic RIPDPI sing-box bundle with fake values only.
 * Contains VLESS-REALITY, VLESS/xHTTP, Hysteria2, standard service outbounds,
 * and one ripdpi.amneziawg entry.
 * No real keys, UUIDs, or server addresses.
 */
private val FAKE_BUNDLE =
    """
    {
      "outbounds": [
        {
          "type": "vless",
          "tag": "test-reality",
          "server": "1.2.3.4",
          "server_port": 443,
          "uuid": "00000000-0000-0000-0000-000000000001",
          "flow": "xtls-rprx-vision",
          "tls": {
            "enabled": true,
            "server_name": "example.com",
            "utls": { "enabled": true, "fingerprint": "chrome" },
            "reality": {
              "enabled": true,
              "public_key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
              "short_id": "deadbeef"
            }
          }
        },
        {
          "type": "vless",
          "tag": "test-xhttp",
          "server": "1.2.3.5",
          "server_port": 443,
          "uuid": "00000000-0000-0000-0000-000000000002",
          "tls": { "enabled": true, "server_name": "xhttp.example.com" },
          "transport": { "type": "xhttp", "path": "/fixture", "host": "xhttp.example.com" }
        },
        {
          "type": "hysteria2",
          "tag": "test-hysteria",
          "server": "1.2.3.4",
          "server_port": 8443,
          "password": "fakepwd"
        },
        { "type": "direct", "tag": "direct" },
        { "type": "block", "tag": "block" },
        { "type": "dns", "tag": "dns-out" }
      ],
      "ripdpi": {
        "schema_version": 1,
        "amneziawg": [
          {
            "tag": "test-awg",
            "private_key": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
            "address": ["10.8.0.2/32"],
            "dns": ["1.1.1.1"],
            "mtu": 1330,
            "peer": {
              "public_key": "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
              "endpoint": "1.2.3.4:51820",
              "allowed_ips": ["0.0.0.0/0"],
              "persistent_keepalive": 25
            },
            "jc": 4,
            "jmin": 40,
            "jmax": 70,
            "s1": 0,
            "s2": 0,
            "h1": 1,
            "h2": 2,
            "h3": 3,
            "h4": 4
          }
        ]
      },
      "route": {"rules": [
        {"package_name": ["com.simple.bypass"], "outbound": "direct"}
      ]}
    }
    """.trimIndent()

private val UPDATED_FAKE_BUNDLE =
    FAKE_BUNDLE
        .replace("\"server_port\": 8443", "\"server_port\": 9443")
        .replace("\"password\": \"fakepwd\"", "\"password\": \"updated-fakepwd\"")
        .replace("\"private_key\": \"BBBB", "\"private_key\": \"DDDD")
        .replace("\"endpoint\": \"1.2.3.4:51820\"", "\"endpoint\": \"5.6.7.8:51821\"")

private val NO_AWG_BUNDLE =
    FAKE_BUNDLE.replace(
        Regex("""(?s)"amneziawg": \[\s*\{.*?\}\s*]"""),
        "\"amneziawg\": []",
    )

private val NO_XHTTP_BUNDLE =
    FAKE_BUNDLE.replace(
        Regex("""(?s)\s*\{\s*"type": "vless",\s*"tag": "test-xhttp".*?\},(?=\s*\{\s*"type": "hysteria2")"""),
        "",
    )

private val INVALID_HYSTERIA_PORT_BUNDLE =
    FAKE_BUNDLE.replace(
        "\"server_port\": 8443",
        "\"server_port\": 70000",
    )

private val INVALID_AWG_KEY_BUNDLE =
    FAKE_BUNDLE.replace(
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
        "not-a-wireguard-key",
    )

private val URL_SAFE_AWG_KEY_BUNDLE =
    FAKE_BUNDLE.replace(
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
        "_".repeat(42) + "8=",
    )

private val UNPADDED_AWG_KEY_BUNDLE =
    FAKE_BUNDLE.replace(
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA",
    )

private val NONCANONICAL_AWG_KEY_BUNDLE =
    FAKE_BUNDLE.replace(
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
    )

private val MISSING_AWG_ADDRESS_BUNDLE =
    FAKE_BUNDLE.replace(
        "\"address\": [\"10.8.0.2/32\"]",
        "\"address\": []",
    )

private val INVALID_AWG_ADDRESS_BUNDLE =
    FAKE_BUNDLE.replace(
        "10.8.0.2/32",
        "not-a-cidr",
    )

private val MULTI_RELAY_BUNDLE =
    """
    {
      "outbounds": [
        {
          "type": "vless", "tag": "reality-primary", "server": "192.0.2.10", "server_port": 443,
          "uuid": "00000000-0000-0000-0000-000000000001", "flow": "xtls-rprx-vision",
          "tls": { "enabled": true, "server_name": "primary.example.test",
            "reality": { "enabled": true,
              "public_key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "short_id": "11112222" } }
        },
        {
          "type": "vless", "tag": "reality-fallback", "server": "192.0.2.10", "server_port": 2053,
          "uuid": "00000000-0000-0000-0000-000000000001", "flow": "xtls-rprx-vision",
          "tls": { "enabled": true, "server_name": "fallback.example.test",
            "reality": { "enabled": true,
              "public_key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "short_id": "11112222" } }
        },
        {
          "type": "vless", "tag": "xhttp", "server": "192.0.2.20", "server_port": 443,
          "uuid": "00000000-0000-0000-0000-000000000001",
          "tls": { "enabled": true, "server_name": "xhttp.example.test" },
          "transport": { "type": "xhttp", "path": "/fixture", "host": "xhttp.example.test" }
        },
        {
          "type": "hysteria2", "tag": "hysteria", "server": "192.0.2.30", "server_port": 443,
          "password": "fixture-value", "obfs": { "type": "salamander", "password": "fixture-obfs-value" }
        },
        { "type": "selector", "tag": "select",
          "outbounds": ["reality-primary", "reality-fallback", "xhttp", "hysteria", "auto"] },
        { "type": "urltest", "tag": "auto",
          "outbounds": ["reality-primary", "reality-fallback", "xhttp", "hysteria"],
          "url": "https://probe.example/generate_204" }
      ],
      "ripdpi": {
        "schema_version": 1,
        "amneziawg": [
          {
            "tag": "test-awg",
            "private_key": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
            "address": ["10.8.0.2/32"],
            "peer": {
              "public_key": "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
              "endpoint": "192.0.2.40:51820",
              "allowed_ips": ["0.0.0.0/0"]
            },
            "jc": 4, "jmin": 40, "jmax": 70,
            "s1": 0, "s2": 0, "h1": 1, "h2": 2, "h3": 3, "h4": 4
          }
        ]
      }
    }
    """.trimIndent()

private val HYSTERIA_ONLY_BUNDLE =
    """
    {
      "outbounds": [
        {
          "type": "hysteria2",
          "tag": "diagnostic-only",
          "server": "192.0.2.30",
          "server_port": 443,
          "password": "fixture-value"
        }
      ]
    }
    """.trimIndent()

@RunWith(RobolectricTestRunner::class)
class ConfigSeederTest {
    private lateinit var application: Application
    private lateinit var proxyGroupRepository: RecordingProxyGroupRepository
    private lateinit var relayProfileStore: FakeRelayProfileStore
    private lateinit var relayCredentialStore: FakeRelayCredentialStore
    private lateinit var relaySettings: FakeAppSettingsRepository
    private lateinit var relayProfileActivator: RelayProfileActivator
    private lateinit var awgProfileRepository: AwgProfileRepository
    private lateinit var awgDao: InMemoryAwgProfileDao
    private lateinit var awgCredentials: InMemoryAwgCredentialStore

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application
            .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        proxyGroupRepository = RecordingProxyGroupRepository()
        relayProfileStore = FakeRelayProfileStore()
        relayCredentialStore = FakeRelayCredentialStore()
        relaySettings = FakeAppSettingsRepository()
        relayProfileActivator =
            RelayProfileActivator(
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                settingsRepository = relaySettings,
            )
        awgDao = InMemoryAwgProfileDao()
        awgCredentials = InMemoryAwgCredentialStore()
        awgProfileRepository = AwgProfileRepository(dao = awgDao, credentialStore = awgCredentials)
    }

    private fun makeSeeder(bundleJson: String? = FAKE_BUNDLE): TestableConfigSeeder =
        TestableConfigSeeder(
            context = application,
            proxyGroupRepository = proxyGroupRepository,
            relayProfileActivator = relayProfileActivator,
            awgProfileRepository = awgProfileRepository,
            settingsRepository = relaySettings,
            bundleJson = bundleJson,
        )

    @Test
    fun `first seed adds group, activates relay profile, saves AWG profile and sets flag`() =
        runTest {
            val seeder = makeSeeder(FAKE_BUNDLE)

            seeder.seed()

            // Group was persisted
            assertEquals(1, proxyGroupRepository.addedGroups.size)
            assertEquals(ProxyGroupType.BASIC, proxyGroupRepository.addedGroups.single().type)
            assertEquals(
                PackageRoutingAction.BYPASS,
                proxyGroupRepository.addedGroups
                    .single()
                    .packageRoutingRules
                    .single()
                    .action,
            )
            // All required transport classes were persisted.
            assertEquals(3, relayProfileStore.list().size)
            assertEquals(
                TlsFingerprintProfileFirefoxStable,
                relayProfileStore.list().single { it.kind == RelayKindHysteria2 }.vlessFingerprint,
            )
            // AWG profile was saved
            assertEquals(1, awgDao.rows.value.size)
            assertEquals(
                SIMPLE_SEED_AWG_PROFILE_ID,
                awgDao.rows.value
                    .single()
                    .id,
            )
            assertEquals(
                "test-awg",
                awgDao.rows.value
                    .single()
                    .name,
            )
            // Seeded flag was set
            assertTrue(seeder.isSeeded())
        }

    @Test
    fun `all relay profiles survive seeding under distinct ids`() =
        runTest {
            val seeder = makeSeeder(FAKE_BUNDLE)

            seeder.seed()

            // RelayProfileActivator defaults to a single shared "default" store slot; the
            // seeder overrides it with a per-kind id so VLESS+REALITY is NOT overwritten by
            // Hysteria2 and xHTTP are relay-activatable too. All must coexist for failover.
            val relayProfiles = relayProfileStore.list()
            assertEquals("All relay kinds must persist", 3, relayProfiles.size)
            assertEquals(
                setOf(RelayKindVlessReality, RelayKindVless, RelayKindHysteria2),
                relayProfiles.map { it.kind }.toSet(),
            )
            assertEquals(
                "Store ids must be stable across release obfuscation",
                setOf(
                    "simple-seed-VlessReality",
                    "simple-seed-Vless",
                    "simple-seed-Hysteria2",
                ),
                relayProfiles.map { it.id }.toSet(),
            )
            // VLESS+REALITY is activated last so it is the initial active transport (priority 0).
            assertEquals(RelayKindVlessReality, relaySettings.snapshot().relayKind)
            assertTrue(seeder.isSeeded())
        }

    @Test
    fun `all declared relay candidates survive and primary reality stays selected`() =
        runTest {
            makeSeeder(MULTI_RELAY_BUNDLE).seed()

            val relayProfiles = relayProfileStore.list()
            assertEquals(4, relayProfiles.size)
            assertEquals(
                setOf(
                    "simple-seed-VlessReality",
                    "simple-seed-VlessReality-2",
                    "simple-seed-Vless",
                    "simple-seed-Hysteria2",
                ),
                relayProfiles.map(RelayProfileRecord::id).toSet(),
            )
            assertEquals(
                listOf(443, 2053),
                relayProfiles
                    .filter { it.kind == RelayKindVlessReality }
                    .map(RelayProfileRecord::serverPort)
                    .sorted(),
            )
            val selected = relayProfileStore.load(relaySettings.snapshot().relayProfileId)
            assertEquals(RelayKindVlessReality, selected?.kind)
            assertEquals(443, selected?.serverPort)
        }

    @Test
    fun `subsequent startup restores primary reality without duplicating diagnostic profiles`() =
        runTest {
            val seeder = makeSeeder(FAKE_BUNDLE)

            seeder.seed()
            relaySettings.update {
                setRelayEnabled(false)
                setRelayKind(RelayKindHysteria2)
                setRelayProfileId("diagnostic-hysteria")
                setSimpleFailoverAwgProfileId("diagnostic-awg")
                setRipdpiMode(Mode.Proxy.preferenceValue)
            }
            seeder.seed()

            assertEquals(1, proxyGroupRepository.addedGroups.size)
            assertEquals(1, awgDao.rows.value.size)
            assertEquals(3, relayProfileStore.list().size)
            val settings = relaySettings.snapshot()
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindVlessReality, settings.relayKind)
            assertEquals(
                relayProfileStore.list().single { it.kind == RelayKindVlessReality }.id,
                settings.relayProfileId,
            )
            assertEquals("", settings.simpleFailoverAwgProfileId)
            assertEquals(Mode.VPN.preferenceValue, settings.ripdpiMode)
        }

    @Test
    fun `subsequent startup refreshes bundled Hysteria and AWG reserves`() =
        runTest {
            makeSeeder(FAKE_BUNDLE).seed()

            makeSeeder(UPDATED_FAKE_BUNDLE).seed()

            val hysteria = relayProfileStore.list().single { it.kind == RelayKindHysteria2 }
            assertEquals(9443, hysteria.serverPort)
            assertEquals("updated-fakepwd", relayCredentialStore.load(hysteria.id)?.hysteriaPassword)
            val awg = requireNotNull(awgProfileRepository.load(SIMPLE_SEED_AWG_PROFILE_ID)).request
            assertEquals("5.6.7.8", awg.endpointHost)
            assertEquals(51821, awg.endpointPort)
            assertTrue(awg.privateKey.startsWith("DDDD"))
        }

    @Test
    fun `legacy seeded flag reruns migration without duplicating AWG`() =
        runTest {
            val seeder = makeSeeder(FAKE_BUNDLE)
            seeder.seed()
            application
                .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(SEED_KEY_VERSION)
                .putBoolean(SEED_KEY_SEEDED, true)
                .commit()

            seeder.seed()

            assertEquals(3, relayProfileStore.list().size)
            assertEquals(1, awgDao.rows.value.size)
            assertTrue(
                application
                    .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(SEED_KEY_VERSION, 0) > 0,
            )
        }

    @Test
    fun `version three seed adds stable fallback without deleting legacy AWG`() =
        runTest {
            val seeder = makeSeeder(FAKE_BUNDLE)
            seeder.seed()
            val seededRequest = requireNotNull(awgProfileRepository.load(SIMPLE_SEED_AWG_PROFILE_ID)).request
            awgProfileRepository.delete(SIMPLE_SEED_AWG_PROFILE_ID)
            awgProfileRepository.save(
                name = "test-awg",
                request = seededRequest,
                existingId = "awg-legacy-random-id",
            )
            application
                .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(SEED_KEY_VERSION, 3)
                .commit()

            seeder.seed()

            assertEquals(2, awgDao.rows.value.size)
            assertTrue(awgDao.rows.value.any { it.id == SIMPLE_SEED_AWG_PROFILE_ID })
            assertNotNull(awgProfileRepository.load("awg-legacy-random-id"))
        }

    @Test
    fun `version two seed migrates package routes without replacing group state`() =
        runTest {
            val existing =
                ProxyGroup(
                    id = SIMPLE_SEED_GROUP_ID,
                    name = "Existing Simple",
                    type = ProxyGroupType.BASIC,
                    order = 7,
                    isSelector = false,
                )
            proxyGroupRepository.add(existing)
            application
                .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(SEED_KEY_VERSION, 2)
                .commit()

            makeSeeder(FAKE_BUNDLE).seed()

            val stored = proxyGroupRepository.addedGroups.single()
            assertEquals("Existing Simple", stored.name)
            assertEquals(7, stored.order)
            assertEquals("com.simple.bypass", stored.packageRoutingRules.single().packageName)
            assertEquals(1, awgDao.rows.value.size)
        }

    @Test
    fun `service outbounds in required bundle do not block seed`() =
        runTest {
            val seeder = makeSeeder(FAKE_BUNDLE)

            seeder.seed()

            assertTrue(seeder.isSeeded())
            assertEquals(3, relayProfileStore.list().size)
            assertEquals(1, awgDao.rows.value.size)
        }

    @Test
    fun `missing asset fails seed without mutating storage`() =
        runTest {
            val seeder = makeSeeder(bundleJson = null)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle without required AWG reserve fails before mutation`() =
        runTest {
            val seeder = makeSeeder(NO_AWG_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle without TCP-diverse xHTTP reserve fails before mutation`() =
        runTest {
            val seeder = makeSeeder(NO_XHTTP_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle with invalid Hysteria port fails before mutation`() =
        runTest {
            val seeder = makeSeeder(INVALID_HYSTERIA_PORT_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle with invalid AWG key fails before mutation`() =
        runTest {
            val seeder = makeSeeder(INVALID_AWG_KEY_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle with URL-safe-only AWG key fails before mutation`() =
        runTest {
            val seeder = makeSeeder(URL_SAFE_AWG_KEY_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle with unpadded AWG key fails before mutation`() =
        runTest {
            val seeder = makeSeeder(UNPADDED_AWG_KEY_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle with noncanonical AWG key fails before mutation`() =
        runTest {
            val seeder = makeSeeder(NONCANONICAL_AWG_KEY_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle with missing AWG address fails before mutation`() =
        runTest {
            val seeder = makeSeeder(MISSING_AWG_ADDRESS_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle with invalid AWG address fails before mutation`() =
        runTest {
            val seeder = makeSeeder(INVALID_AWG_ADDRESS_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertTrue(awgDao.rows.value.isEmpty())
        }

    @Test
    fun `bundle without VLESS Reality does not seed a diagnostic transport as runtime`() =
        runTest {
            val seeder = makeSeeder(HYSTERIA_ONLY_BUNDLE)

            val failure = runCatching { seeder.seed() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(seeder.isSeeded())
            assertTrue(proxyGroupRepository.addedGroups.isEmpty())
            assertTrue(relayProfileStore.list().isEmpty())
            assertFalse(relaySettings.snapshot().relayEnabled)
        }

    @Test
    fun `missing asset followed by present asset seeds successfully`() =
        runTest {
            // First call: asset absent — seed fails and the marker remains clear.
            assertTrue(runCatching { makeSeeder(bundleJson = null).seed() }.isFailure)

            // Second call with same shared prefs: asset now present — seeds
            val seeder = makeSeeder(FAKE_BUNDLE)
            seeder.seed()

            assertTrue(seeder.isSeeded())
            assertEquals(1, proxyGroupRepository.addedGroups.size)
        }
}

// ---------------------------------------------------------------------------
// Test double: subclass of ConfigSeeder with injected bundle string
// ---------------------------------------------------------------------------

private class TestableConfigSeeder(
    context: Context,
    proxyGroupRepository: ProxyGroupRepository,
    relayProfileActivator: RelayProfileActivator,
    awgProfileRepository: AwgProfileRepository,
    settingsRepository: AppSettingsRepository,
    private val bundleJson: String?,
) : ConfigSeeder(context, proxyGroupRepository, relayProfileActivator, awgProfileRepository, settingsRepository) {
    override fun readBundle(): String? = bundleJson

    fun isSeeded(): Boolean =
        context
            .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SEED_KEY_SEEDED, false)
}

// ---------------------------------------------------------------------------
// Fakes for stores required by RelayProfileActivator and AwgProfileRepository
// ---------------------------------------------------------------------------

private class RecordingProxyGroupRepository : ProxyGroupRepository {
    val addedGroups = mutableListOf<ProxyGroup>()

    override suspend fun add(group: ProxyGroup) {
        addedGroups.removeAll { it.id == group.id }
        addedGroups += group
    }

    override suspend fun update(group: ProxyGroup) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun list(): List<ProxyGroup> = addedGroups.toList()

    override fun groups(): Flow<List<ProxyGroup>> = flowOf(emptyList())
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

private class InMemoryAwgProfileDao : AwgProfileDao {
    val rows = MutableStateFlow<List<AwgProfileEntity>>(emptyList())

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = rows.asStateFlow()

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

private class InMemoryAwgCredentialStore : AwgCredentialStore {
    private val secrets = mutableMapOf<String, AwgSecrets>()

    override suspend fun load(profileId: String): AwgSecrets? = secrets[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        this.secrets[profileId] = secrets
    }

    override suspend fun clear(profileId: String) {
        secrets.remove(profileId)
    }
}
