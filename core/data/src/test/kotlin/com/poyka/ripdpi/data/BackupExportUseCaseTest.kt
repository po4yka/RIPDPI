package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.backup.BackupExportResult
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.BackupProfileProvider
import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        profiles: List<ProxyProfile> = emptyList(),
        groups: List<ProxyGroup> = emptyList(),
        settings: AppSettings = AppSettings.getDefaultInstance(),
    ): BackupExportUseCase =
        BackupExportUseCase(
            groupRepository = FakeGroupRepository(groups),
            profileProvider = BackupProfileProvider { profiles },
            ruleDao = ruleDao,
            settingsRepository = FakeAppSettingsRepository(settings),
        )

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
                useCase(profiles = listOf(profile), groups = listOf(group))
                    .gather(
                        variant = BackupVariant.FULL,
                        appVersion = "9.9.9",
                        createdAtEpochMillis = 42L,
                    )

            assertEquals("9.9.9", doc.appVersion)
            assertEquals(42L, doc.createdAtEpochMillis)
            assertEquals(1, doc.profiles.size)
            assertEquals(1, doc.groups.size)
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

            val full = useCase(profiles = listOf(profile)).gather(BackupVariant.FULL, "1.0.0", 0L)
            val share = useCase(profiles = listOf(profile)).gather(BackupVariant.SHARE, "1.0.0", 0L)

            assertTrue("password" in full.profiles.single())
            assertTrue("password must be stripped in SHARE", "password" !in share.profiles.single())
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
            val settings =
                AppSettings
                    .getDefaultInstance()
                    .toBuilder()
                    .setProxyPort(1234)
                    .build()
            val doc = useCase(settings = settings).gather(BackupVariant.FULL, "1.0.0", 0L)

            val restored = BackupSettingsConverter.fromMap(doc.settings)
            assertEquals(1234, restored.proxyPort)
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
