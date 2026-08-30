package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.AwgBackupProfile
import com.poyka.ripdpi.data.backup.BackupExportResult
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.BackupPrivateDataStore
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupExportUseCaseTest {
    private lateinit var db: RipDpiDatabase
    private lateinit var ruleDao: RuleDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        ruleDao = db.ruleDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun useCase(
        groups: List<ProxyGroup> = emptyList(),
        settings: AppSettings = AppSettings.getDefaultInstance(),
        privateDataStore: BackupPrivateDataStore = BackupPrivateDataStore.Empty,
    ): BackupExportUseCase =
        BackupExportUseCase(
            groupRepository = FakeGroupRepository(groups),
            ruleDao = ruleDao,
            settingsRepository = FakeAppSettingsRepository(settings),
            privateDataStore = privateDataStore,
        )

    @Test
    fun `FULL gathers separate profile credentials while SHARE never reads them`() =
        runTest {
            val privateData =
                BackupPrivateDataV1(
                    relayProfiles = listOf(RelayProfileRecord(id = "relay-1")),
                    relayCredentials = listOf(RelayCredentialRecord(profileId = "relay-1", vlessUuid = "secret")),
                    warpProfiles = listOf(WarpProfile(id = "warp-1")),
                    warpCredentials =
                        listOf(
                            WarpCredentials(
                                profileId = "warp-1",
                                deviceId = "device",
                                accessToken = "token",
                            ),
                        ),
                    awgProfiles =
                        listOf(
                            AwgBackupProfile(
                                id = "awg-1",
                                name = "AWG",
                                requestJson = "{}",
                                updatedAt = 1L,
                                secrets = AwgSecrets(privateKey = "private", presharedKey = "psk"),
                            ),
                        ),
                    xrayMetadata = listOf(XrayProfileMetadataRecord(profileId = "xray-1", revision = "fixture")),
                    xraySecrets =
                        listOf(
                            XrayProfileSecretRecord(profileId = "xray-1", revision = "fixture", uuid = "uuid"),
                        ),
                )
            var snapshots = 0
            val store =
                object : BackupPrivateDataStore {
                    override suspend fun snapshot(): BackupPrivateDataV1 {
                        snapshots += 1
                        return privateData
                    }

                    override suspend fun replaceAll(data: BackupPrivateDataV1) = error("unused")
                }

            val full = useCase(privateDataStore = store).gather(BackupVariant.FULL, "1.0.0", 0L)
            val share = useCase(privateDataStore = store).gather(BackupVariant.SHARE, "1.0.0", 0L)

            assertEquals(privateData, full.privateData)
            assertEquals(null, share.privateData)
            assertEquals(1, snapshots)
        }

    @Test
    fun `gather assembles profiles groups rules and settings`() =
        runTest {
            ruleDao.insert(RuleEntity(name = "rule-a", outboundTag = OutboundTag.Block))
            val group =
                ProxyGroup(id = "g-1", name = "Group", type = ProxyGroupType.BASIC, order = 0, isSelector = false)
            val profile =
                ProxyProfile.Shadowsocks(
                    id = "ss-1",
                    displayName = "SS",
                    groupId = "g-1",
                    server = "ss.example.com",
                    serverPort = 8388,
                    method = "aes-256-gcm",
                    password = "secret",
                )

            val doc =
                useCase(groups = listOf(group.copy(members = listOf(profile))))
                    .gather(
                        variant = BackupVariant.FULL,
                        appVersion = "9.9.9",
                        createdAtEpochMillis = 42L,
                    )

            assertEquals("9.9.9", doc.appVersion)
            assertEquals(42L, doc.createdAtEpochMillis)
            assertEquals(1, doc.profiles.size)
            assertEquals(
                "ss-1",
                doc.profiles
                    .single()
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals(1, doc.groups.size)
            assertEquals(listOf(profile), doc.groups.single().members)
            assertEquals(1, doc.rules.size)
            assertEquals("rule-a", doc.rules.single().name)
            assertTrue("FULL must mark credentials", doc.containsCredentials)
        }

    @Test
    fun `gather FULL keeps the password, SHARE strips it`() =
        runTest {
            val profile =
                ProxyProfile.Shadowsocks(
                    id = "ss-1",
                    displayName = "SS",
                    groupId = "g-1",
                    server = "ss.example.com",
                    serverPort = 8388,
                    method = "aes-256-gcm",
                    password = "secret",
                )

            val group =
                ProxyGroup(
                    id = "g-1",
                    name = "Group",
                    type = ProxyGroupType.BASIC,
                    order = 0,
                    isSelector = false,
                    members = listOf(profile),
                )
            val full = useCase(groups = listOf(group)).gather(BackupVariant.FULL, "1.0.0", 0L)
            val share = useCase(groups = listOf(group)).gather(BackupVariant.SHARE, "1.0.0", 0L)

            assertTrue("password" in full.profiles.single())
            assertEquals(
                "secret",
                (
                    full.groups
                        .single()
                        .members
                        .single() as ProxyProfile.Shadowsocks
                ).password,
            )
            assertTrue("password must be stripped in SHARE", "password" !in share.profiles.single())
            assertTrue(
                share.groups
                    .single()
                    .members
                    .isEmpty(),
            )
        }

    @Test
    fun `export streams to the OutputStream and reports a positive byte count`() =
        runTest {
            val out = ByteArrayOutputStream()
            val result =
                useCase().export(
                    variant = BackupVariant.SHARE,
                    output = out,
                    appVersion = "1.0.0",
                    createdAtEpochMillis = 0L,
                )

            assertTrue(result is BackupExportResult.Success)
            val success = result as BackupExportResult.Success
            assertEquals(out.size().toLong(), success.byteCount)
            assertTrue(success.byteCount > 0L)
            // The streamed bytes must be a valid backup document.
            val restored = BackupImporter.import(out.toString("UTF-8"))
            assertEquals("1.0.0", restored.appVersion)
        }

    @Test
    fun `export settings survive the round-trip via the converter`() =
        runTest {
            val workerBearer = "worker-bearer-must-not-enter-backup"
            val settings =
                AppSettings
                    .getDefaultInstance()
                    .toBuilder()
                    .setProxyPort(1234)
                    .setWsTunnelWorkerUrl("https://worker.example/ws")
                    .setWsTunnelWorkerCredentialRef("worker-production")
                    .build()
            val doc = useCase(settings = settings).gather(BackupVariant.FULL, "1.0.0", 0L)

            val restored = BackupSettingsConverter.fromMap(doc.settings)
            assertEquals(1234, requireNotNull(restored).proxyPort)
            assertEquals("https://worker.example/ws", restored.wsTunnelWorkerUrl)
            assertEquals("worker-production", restored.wsTunnelWorkerCredentialRef)
            assertFalse(doc.settings.values.any { workerBearer in it })
        }

    @Test
    fun `SHARE export omits full settings snapshot`() =
        runTest {
            val settings =
                AppSettings
                    .getDefaultInstance()
                    .toBuilder()
                    .setProxyLanAuthToken("share-token-secret")
                    .setRelayServer("relay-secret.example")
                    .setRelayServerPort(443)
                    .setRelayServerName("sni-secret.example")
                    .setRelayMasqueUrl("https://masque-secret.example/.well-known/masque/udp/")
                    .setRelayAppsScriptFrontDomain("front-secret.example")
                    .setEncryptedDnsHost("dns-secret.example")
                    .setEncryptedDnsDohUrl("https://dns-secret.example/dns-query")
                    .setDetectionDiagnosticTlsKeylogPath("/tmp/secret-keylog")
                    .build()

            val full = useCase(settings = settings).gather(BackupVariant.FULL, "1.0.0", 0L)
            val share = useCase(settings = settings).gather(BackupVariant.SHARE, "1.0.0", 0L)
            val fullPayload = full.settings.values.joinToString(separator = "\n")
            val sharePayload = share.settings.values.joinToString(separator = "\n")

            assertTrue(BackupSettingsConverter.SnapshotKey in full.settings)
            assertTrue("relay-secret.example" in fullPayload)
            assertTrue(share.settings.isEmpty())
            assertFalse(BackupSettingsConverter.SnapshotKey in share.settings)
            assertFalse("relay-secret.example" in sharePayload)
            assertFalse("share-token-secret" in sharePayload)
            assertFalse("https://dns-secret.example/dns-query" in sharePayload)
            assertFalse("/tmp/secret-keylog" in sharePayload)
        }

    @Test
    fun `export surfaces a typed WriteFailed when the stream throws`() =
        runTest {
            val failing =
                object : OutputStream() {
                    override fun write(b: Int): Unit = throw IOException("disk full")

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Unit = throw IOException("disk full")
                }

            val result =
                useCase().export(
                    variant = BackupVariant.FULL,
                    output = failing,
                    appVersion = "1.0.0",
                    passphrase = "test password".toCharArray(),
                    createdAtEpochMillis = 0L,
                )

            assertTrue(result is BackupExportResult.WriteFailed)
            assertEquals(BackupVariant.FULL, (result as BackupExportResult.WriteFailed).variant)
        }

    private class FakeGroupRepository(
        private val groups: List<ProxyGroup>,
    ) : ProxyGroupRepository {
        override suspend fun add(group: ProxyGroup) = error("unused")

        override suspend fun update(group: ProxyGroup) = error("unused")

        override suspend fun delete(id: String) = error("unused")

        override suspend fun list(): List<ProxyGroup> = groups

        override fun groups(): Flow<List<ProxyGroup>> = MutableStateFlow(groups).asStateFlow()
    }

    private class FakeAppSettingsRepository(
        private val current: AppSettings,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(current)

        override val settings: Flow<AppSettings> = state.asStateFlow()

        override suspend fun snapshot(): AppSettings = state.first()

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
}
