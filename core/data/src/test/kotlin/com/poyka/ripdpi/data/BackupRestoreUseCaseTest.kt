package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupPreviewResult
import com.poyka.ripdpi.data.backup.BackupProfileProvider
import com.poyka.ripdpi.data.backup.BackupProfileRestoreSink
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.BackupSchemaVersion
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.backup.RestoreResult
import com.poyka.ripdpi.data.backup.RestoreSelection
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRestoreUseCaseTest {
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

    private val sampleProfile =
        ProxyProfile.Shadowsocks(
            id = "ss-1",
            displayName = "SS",
            groupId = "g-1",
            server = "ss.example.com",
            serverPort = 8388,
            method = "aes-256-gcm",
            password = "secret",
        )
    private val sampleGroup =
        ProxyGroup(id = "g-1", name = "Group", type = ProxyGroupType.BASIC, order = 0, isSelector = false)
    private val sampleRule =
        RuleEntity(
            name = "rule-a",
            userOrder = 3,
            enabled = false,
            domains = "example.com",
            ports = "443",
            network = RuleNetwork.TCP,
            packages = setOf("com.example.app"),
            outboundTag = OutboundTag.Profile(7),
        )

    private fun exportUseCase(
        groups: FakeGroupRepository,
        settings: FakeAppSettingsRepository,
        profiles: List<ProxyProfile>,
    ) = BackupExportUseCase(
        groupRepository = groups,
        profileProvider = BackupProfileProvider { profiles },
        ruleDao = ruleDao,
        settingsRepository = settings,
    )

    private fun restoreUseCase(
        groups: FakeGroupRepository,
        settings: FakeAppSettingsRepository,
        sink: FakeProfileSink,
    ) = BackupRestoreUseCase(
        groupRepository = groups,
        profileSink = sink,
        ruleDao = ruleDao,
        settingsRepository = settings,
    )

    @Test
    fun `FULL export then wipe then FULL import deep-equals original state`() =
        runTest {
            // -- Seed live state and export a FULL backup. --
            val exportGroups = FakeGroupRepository(mutableListOf(sampleGroup))
            val exportSettings =
                FakeAppSettingsRepository(
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setProxyPort(4242)
                        .build(),
                )
            ruleDao.insert(sampleRule)
            val doc =
                exportUseCase(exportGroups, exportSettings, listOf(sampleProfile))
                    .gather(BackupVariant.FULL, "1.0.0", 0L)
            val json = BackupSerializer.encodeToString(doc)

            // -- Full wipe of live state. --
            ruleDao.deleteAll()
            val liveGroups = FakeGroupRepository(mutableListOf())
            val liveSettings = FakeAppSettingsRepository(AppSettings.getDefaultInstance())
            val sink = FakeProfileSink()
            assertEquals(0, ruleDao.allRules().first().size)

            // -- Full import. --
            val result =
                restoreUseCase(liveGroups, liveSettings, sink).restore(
                    json,
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )
            assertTrue(result is RestoreResult.Success)

            // -- Deep-equals across every category. --
            assertEquals(listOf(sampleProfile), sink.stored)
            assertEquals(listOf(sampleGroup), liveGroups.list())
            val restoredRule = ruleDao.allRules().first().single()
            assertEquals(sampleRule.name, restoredRule.name)
            assertEquals(sampleRule.userOrder, restoredRule.userOrder)
            assertEquals(sampleRule.enabled, restoredRule.enabled)
            assertEquals(sampleRule.domains, restoredRule.domains)
            assertEquals(sampleRule.ports, restoredRule.ports)
            assertEquals(sampleRule.network, restoredRule.network)
            assertEquals(sampleRule.packages, restoredRule.packages)
            assertEquals(OutboundTag.Profile(7), restoredRule.outboundTag)
            assertEquals(4242, liveSettings.snapshot().proxyPort)
        }

    @Test
    fun `newer-than-app schema is refused and never touches live data`() =
        runTest {
            val future = BackupSchemaVersion + 1
            val json =
                """
                {"schemaVersion":$future,"createdAtEpochMillis":0,"appVersion":"9.9.9",
                 "profiles":[],"groups":[],"rules":[],"settings":{},"containsCredentials":true}
                """.trimIndent()

            ruleDao.insert(sampleRule)
            val liveGroups = FakeGroupRepository(mutableListOf(sampleGroup))
            val liveSettings = FakeAppSettingsRepository(AppSettings.getDefaultInstance())
            val sink = FakeProfileSink()

            val result =
                restoreUseCase(liveGroups, liveSettings, sink).restore(
                    json,
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )

            assertTrue(result is RestoreResult.UnsupportedVersion)
            assertEquals(future, (result as RestoreResult.UnsupportedVersion).found)
            // Live data is untouched: the seeded rule and group survive.
            assertEquals(1, ruleDao.allRules().first().size)
            assertEquals(listOf(sampleGroup), liveGroups.list())
            assertTrue(sink.stored == null)
        }

    @Test
    fun `malformed JSON aborts without touching live data`() =
        runTest {
            ruleDao.insert(sampleRule)
            val liveGroups = FakeGroupRepository(mutableListOf(sampleGroup))
            val liveSettings = FakeAppSettingsRepository(AppSettings.getDefaultInstance())
            val sink = FakeProfileSink()

            val result =
                restoreUseCase(liveGroups, liveSettings, sink).restore(
                    "{ this is not json",
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )

            assertTrue(result is RestoreResult.Aborted)
            assertEquals(1, ruleDao.allRules().first().size)
            assertEquals(listOf(sampleGroup), liveGroups.list())
            assertTrue(sink.stored == null)
        }

    @Test
    fun `selective restore preserves unchecked categories`() =
        runTest {
            val exportGroups = FakeGroupRepository(mutableListOf(sampleGroup))
            val exportSettings =
                FakeAppSettingsRepository(
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setProxyPort(4242)
                        .build(),
                )
            ruleDao.insert(sampleRule)
            val json =
                BackupSerializer.encodeToString(
                    exportUseCase(exportGroups, exportSettings, listOf(sampleProfile))
                        .gather(BackupVariant.FULL, "1.0.0", 0L),
                )

            // Live state has a DIFFERENT rule and different settings.
            ruleDao.deleteAll()
            val existingRule = RuleEntity(name = "live-rule", outboundTag = OutboundTag.Block)
            ruleDao.insert(existingRule)
            val liveGroups = FakeGroupRepository(mutableListOf())
            val liveSettings =
                FakeAppSettingsRepository(
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setProxyPort(1111)
                        .build(),
                )
            val sink = FakeProfileSink()

            // Restore ONLY profiles+groups; routes and settings must be preserved.
            val result =
                restoreUseCase(liveGroups, liveSettings, sink).restore(
                    json,
                    RestoreSelection(profilesAndGroups = true, routes = false, settings = false),
                )
            assertTrue(result is RestoreResult.Success)

            // profiles+groups were restored ...
            assertEquals(listOf(sampleProfile), sink.stored)
            assertEquals(listOf(sampleGroup), liveGroups.list())
            // ... but the unchecked rule + settings categories were left intact.
            assertEquals(
                "live-rule",
                ruleDao
                    .allRules()
                    .first()
                    .single()
                    .name,
            )
            assertEquals(1111, liveSettings.snapshot().proxyPort)
        }

    @Test
    fun `nothing selected is a no-op`() =
        runTest {
            ruleDao.insert(sampleRule)
            val result =
                restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository(), FakeProfileSink())
                    .restore("{}", RestoreSelection(false, false, false))
            assertTrue(result is RestoreResult.NothingSelected)
            assertEquals(1, ruleDao.allRules().first().size)
        }

    @Test
    fun `SHARE backup preview reports profiles that cannot be decoded`() =
        runTest {
            val exportGroups = FakeGroupRepository(mutableListOf(sampleGroup))
            val exportSettings = FakeAppSettingsRepository()
            val json =
                BackupSerializer.encodeToString(
                    // SHARE strips the Shadowsocks password (REDACTED), so the
                    // profile can no longer be decoded into a complete ProxyProfile.
                    exportUseCase(exportGroups, exportSettings, listOf(sampleProfile))
                        .gather(BackupVariant.SHARE, "1.0.0", 0L),
                )

            val preview =
                restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository(), FakeProfileSink())
                    .preview(json)

            assertTrue(preview is BackupPreviewResult.Ready)
            val ready = (preview as BackupPreviewResult.Ready).preview
            assertEquals(0, ready.restorableProfileCount)
            assertTrue(ready.hasUndecodableProfiles)
            assertEquals(listOf("SS"), ready.undecodableProfiles)
            assertFalse(ready.containsCredentials)
            // Groups are still restorable from a SHARE backup.
            assertEquals(1, ready.groupCount)
        }

    @Test
    fun `FULL backup preview reports counts and is fully restorable`() =
        runTest {
            ruleDao.insert(sampleRule)
            val exportGroups = FakeGroupRepository(mutableListOf(sampleGroup))
            val exportSettings = FakeAppSettingsRepository()
            val json =
                BackupSerializer.encodeToString(
                    exportUseCase(exportGroups, exportSettings, listOf(sampleProfile))
                        .gather(BackupVariant.FULL, "2.0.0", 0L),
                )

            val preview =
                restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository(), FakeProfileSink())
                    .preview(json)

            assertTrue(preview is BackupPreviewResult.Ready)
            val ready = (preview as BackupPreviewResult.Ready).preview
            assertEquals(BackupSchemaVersion, ready.schemaVersion)
            assertEquals("2.0.0", ready.appVersion)
            assertEquals(1, ready.restorableProfileCount)
            assertEquals(1, ready.groupCount)
            assertEquals(1, ready.ruleCount)
            assertTrue(ready.settingCount >= 1)
            assertFalse(ready.hasUndecodableProfiles)
            assertTrue(ready.containsCredentials)
        }

    @Test
    fun `preview surfaces UnsupportedVersion for newer schema`() {
        val future = BackupSchemaVersion + 5
        val json =
            """
            {"schemaVersion":$future,"createdAtEpochMillis":0,"appVersion":"x",
            "profiles":[],"groups":[],"rules":[],"settings":{}}
            """.trimIndent()
        val preview =
            restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository(), FakeProfileSink())
                .preview(json)
        assertTrue(preview is BackupPreviewResult.UnsupportedVersion)
        assertEquals(future, (preview as BackupPreviewResult.UnsupportedVersion).found)
    }

    // -- Fakes ----------------------------------------------------------------

    private class FakeGroupRepository(
        private val groups: MutableList<ProxyGroup>,
    ) : ProxyGroupRepository {
        private val state = MutableStateFlow(groups.toList())

        override suspend fun add(group: ProxyGroup) {
            groups.removeAll { it.id == group.id }
            groups.add(group)
            state.value = groups.toList()
        }

        override suspend fun update(group: ProxyGroup) {
            val idx = groups.indexOfFirst { it.id == group.id }
            if (idx >= 0) groups[idx] = group
            state.value = groups.toList()
        }

        override suspend fun delete(id: String) {
            groups.removeAll { it.id == id }
            state.value = groups.toList()
        }

        override suspend fun list(): List<ProxyGroup> = groups.toList()

        override suspend fun replaceAll(groups: List<ProxyGroup>) {
            this.groups.clear()
            this.groups.addAll(groups)
            state.value = this.groups.toList()
        }

        override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
    }

    private class FakeProfileSink : BackupProfileRestoreSink {
        var stored: List<ProxyProfile>? = null
            private set

        override suspend fun replaceAll(profiles: List<ProxyProfile>) {
            stored = profiles
        }
    }

    private class FakeAppSettingsRepository(
        current: AppSettings = AppSettings.getDefaultInstance(),
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
