package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupPreviewResult
import com.poyka.ripdpi.data.backup.BackupPrivateDataStore
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.BackupSchemaVersion
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupSettingsConverter
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.cancellation.CancellationException

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
        privateDataStore: BackupPrivateDataStore = BackupPrivateDataStore.Empty,
    ) = BackupExportUseCase(
        groupRepository = groups,
        ruleDao = ruleDao,
        settingsRepository = settings,
        privateDataStore = privateDataStore,
    )

    private fun restoreUseCase(
        groups: FakeGroupRepository,
        settings: FakeAppSettingsRepository,
        privateDataStore: BackupPrivateDataStore = BackupPrivateDataStore.Empty,
    ) = BackupRestoreUseCase(
        groupRepository = groups,
        ruleDao = ruleDao,
        settingsRepository = settings,
        privateDataStore = privateDataStore,
    )

    private fun settingsWithPort(port: Int): AppSettings =
        AppSettings
            .getDefaultInstance()
            .toBuilder()
            .setProxyPort(port)
            .build()

    private suspend fun restoreSettings(
        settings: FakeAppSettingsRepository,
        targetPort: Int,
    ): RestoreResult {
        val document =
            BackupExporter.export(
                variant = BackupVariant.FULL,
                profiles = emptyList(),
                groups = emptyList(),
                rules = emptyList(),
                settings = BackupSettingsConverter.toMap(settingsWithPort(targetPort), BackupVariant.FULL),
                createdAtEpochMillis = 0L,
                appVersion = "1.0.0",
            )
        return restoreUseCase(FakeGroupRepository(mutableListOf()), settings).restore(
            BackupSerializer.encodeToString(document),
            RestoreSelection(profilesAndGroups = false, routes = false, settings = true),
        )
    }

    @Test
    fun `absent settings snapshot leaves live settings unchanged`() =
        runTest {
            val document =
                BackupExporter.export(
                    variant = BackupVariant.SHARE,
                    profiles = emptyList(),
                    groups = emptyList(),
                    rules = emptyList(),
                    settings = emptyMap(),
                    createdAtEpochMillis = 0L,
                    appVersion = "1.0.0",
                )
            val liveSettings =
                FakeAppSettingsRepository(
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setProxyPort(4321)
                        .build(),
                )

            val result =
                restoreUseCase(FakeGroupRepository(mutableListOf()), liveSettings).restore(
                    BackupSerializer.encodeToString(document),
                    RestoreSelection(profilesAndGroups = false, routes = false, settings = true),
                )

            assertTrue(result is RestoreResult.Success)
            assertEquals(4321, liveSettings.snapshot().proxyPort)
            assertEquals(0, liveSettings.replaceCalls)
        }

    @Test
    fun `settings write failure rolls back groups and rules`() =
        runTest {
            val targetGroup = sampleGroup.copy(id = "target")
            val targetRule = sampleRule.copy(name = "target-rule")
            val targetSettings =
                AppSettings
                    .getDefaultInstance()
                    .toBuilder()
                    .setProxyPort(9999)
                    .build()
            val document =
                BackupExporter.export(
                    variant = BackupVariant.FULL,
                    profiles = emptyList(),
                    groups = listOf(targetGroup),
                    rules = listOf(targetRule),
                    settings = BackupSettingsConverter.toMap(targetSettings, BackupVariant.FULL),
                    createdAtEpochMillis = 0L,
                    appVersion = "1.0.0",
                )
            val liveGroup = sampleGroup.copy(id = "live")
            val liveGroups = FakeGroupRepository(mutableListOf(liveGroup))
            val liveRule = sampleRule.copy(name = "live-rule")
            ruleDao.insert(liveRule)
            val liveSettings =
                FakeAppSettingsRepository(
                    current =
                        AppSettings
                            .getDefaultInstance()
                            .toBuilder()
                            .setProxyPort(1111)
                            .build(),
                    failOnReplace = true,
                )

            val result =
                restoreUseCase(liveGroups, liveSettings).restore(
                    BackupSerializer.encodeToString(document),
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )

            assertTrue(result is RestoreResult.Aborted)
            assertEquals(listOf(liveGroup), liveGroups.list())
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
    fun `write then throw is compensated from the captured settings preimage`() =
        runTest {
            val liveSettings =
                FakeAppSettingsRepository(
                    current = settingsWithPort(1111),
                    failAfterReplace = true,
                )

            val result = restoreSettings(liveSettings, targetPort = 9999)

            assertTrue(result is RestoreResult.Aborted)
            assertEquals(1111, liveSettings.snapshot().proxyPort)
            assertEquals(2, liveSettings.replaceCalls)
        }

    @Test
    fun `cancellation compensates in a non cancellable context and remains cancellation`() =
        runTest {
            val liveSettings =
                FakeAppSettingsRepository(
                    current = settingsWithPort(1111),
                    cancelAfterReplace = true,
                )

            val cancellation =
                try {
                    restoreSettings(liveSettings, targetPort = 9999)
                    null
                } catch (caught: CancellationException) {
                    caught
                }

            assertNotNull(cancellation)
            assertEquals(1111, liveSettings.snapshot().proxyPort)
            assertTrue(liveSettings.rollbackWasActive)
        }

    @Test
    fun `rollback failure reports integrity failure`() =
        runTest {
            val liveSettings =
                FakeAppSettingsRepository(
                    current = settingsWithPort(1111),
                    failAfterReplace = true,
                    failRollback = true,
                )

            val result = restoreSettings(liveSettings, targetPort = 9999)

            assertTrue(result is RestoreResult.IntegrityFailure)
            assertEquals(9999, liveSettings.snapshot().proxyPort)
            assertEquals(1, (result as RestoreResult.IntegrityFailure).rollbackFailures.size)
        }

    @Test
    fun `group write failure rolls back separate private profile stores`() =
        runTest {
            val livePrivateData = BackupPrivateDataV1(relayProfiles = listOf(RelayProfileRecord(id = "live")))
            val targetPrivateData = BackupPrivateDataV1(relayProfiles = listOf(RelayProfileRecord(id = "target")))
            val privateStore = FakePrivateDataStore(livePrivateData)
            val document =
                BackupExporter.export(
                    variant = BackupVariant.FULL,
                    profiles = emptyList(),
                    groups = listOf(sampleGroup),
                    rules = emptyList(),
                    settings = emptyMap(),
                    createdAtEpochMillis = 0L,
                    appVersion = "1.0.0",
                    privateData = targetPrivateData,
                )

            val result =
                restoreUseCase(
                    groups = FakeGroupRepository(mutableListOf(), failOnReplace = true),
                    settings = FakeAppSettingsRepository(),
                    privateDataStore = privateStore,
                ).restore(
                    BackupSerializer.encodeToString(document),
                    RestoreSelection(profilesAndGroups = true, routes = false, settings = false),
                )

            assertTrue(result is RestoreResult.Aborted)
            assertEquals(livePrivateData, privateStore.data)
        }

    @Test
    fun `FULL export then wipe then FULL import deep-equals original state`() =
        runTest {
            // -- Seed live state and export a FULL backup. --
            val groupWithProfile = sampleGroup.copy(members = listOf(sampleProfile))
            val exportGroups = FakeGroupRepository(mutableListOf(groupWithProfile))
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
                exportUseCase(exportGroups, exportSettings)
                    .gather(BackupVariant.FULL, "1.0.0", 0L)
            val json = BackupSerializer.encodeToString(doc)

            // -- Full wipe of live state. --
            ruleDao.deleteAll()
            val liveGroups = FakeGroupRepository(mutableListOf())
            val liveSettings = FakeAppSettingsRepository(AppSettings.getDefaultInstance())
            assertEquals(0, ruleDao.allRules().first().size)

            // -- Full import. --
            val result =
                restoreUseCase(liveGroups, liveSettings).restore(
                    json,
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )
            assertTrue(result is RestoreResult.Success)

            // -- Deep-equals across every category. --
            assertEquals(listOf(groupWithProfile), liveGroups.list())
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
    fun `legacy top-level profile is restored into its matching group`() =
        runTest {
            val document =
                BackupExporter.export(
                    variant = BackupVariant.FULL,
                    profiles = listOf(sampleProfile),
                    groups = listOf(sampleGroup),
                    rules = emptyList(),
                    settings = emptyMap(),
                    createdAtEpochMillis = 0L,
                    appVersion = "1.0.0",
                )
            val liveGroups = FakeGroupRepository(mutableListOf())

            val result =
                restoreUseCase(liveGroups, FakeAppSettingsRepository()).restore(
                    BackupSerializer.encodeToString(document),
                    RestoreSelection(profilesAndGroups = true, routes = false, settings = false),
                )

            assertTrue(result is RestoreResult.Success)
            assertEquals(listOf(sampleGroup.copy(members = listOf(sampleProfile))), liveGroups.list())
        }

    @Test
    fun `profile referencing a missing group aborts before any write`() =
        runTest {
            val document =
                BackupExporter.export(
                    variant = BackupVariant.FULL,
                    profiles = listOf(sampleProfile),
                    groups = emptyList(),
                    rules = emptyList(),
                    settings = emptyMap(),
                    createdAtEpochMillis = 0L,
                    appVersion = "1.0.0",
                )
            val existingGroup = sampleGroup.copy(id = "existing")
            val liveGroups = FakeGroupRepository(mutableListOf(existingGroup))
            val liveSettings =
                FakeAppSettingsRepository(
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setProxyPort(4242)
                        .build(),
                )
            ruleDao.insert(sampleRule)
            val existingRules = ruleDao.allRules().first()

            val result =
                restoreUseCase(liveGroups, liveSettings).restore(
                    BackupSerializer.encodeToString(document),
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )

            assertTrue(result is RestoreResult.Aborted)
            assertEquals(listOf(existingGroup), liveGroups.list())
            assertEquals(existingRules, ruleDao.allRules().first())
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

            val result =
                restoreUseCase(liveGroups, liveSettings).restore(
                    json,
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )

            assertTrue(result is RestoreResult.UnsupportedVersion)
            assertEquals(future, (result as RestoreResult.UnsupportedVersion).found)
            // Live data is untouched: the seeded rule and group survive.
            assertEquals(1, ruleDao.allRules().first().size)
            assertEquals(listOf(sampleGroup), liveGroups.list())
        }

    @Test
    fun `malformed JSON aborts without touching live data`() =
        runTest {
            ruleDao.insert(sampleRule)
            val liveGroups = FakeGroupRepository(mutableListOf(sampleGroup))
            val liveSettings = FakeAppSettingsRepository(AppSettings.getDefaultInstance())

            val result =
                restoreUseCase(liveGroups, liveSettings).restore(
                    "{ this is not json",
                    RestoreSelection(profilesAndGroups = true, routes = true, settings = true),
                )

            assertTrue(result is RestoreResult.Aborted)
            assertEquals(1, ruleDao.allRules().first().size)
            assertEquals(listOf(sampleGroup), liveGroups.list())
        }

    @Test
    fun `selective restore preserves unchecked categories`() =
        runTest {
            val groupWithProfile = sampleGroup.copy(members = listOf(sampleProfile))
            val exportGroups = FakeGroupRepository(mutableListOf(groupWithProfile))
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
                    exportUseCase(exportGroups, exportSettings)
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
            // Restore ONLY profiles+groups; routes and settings must be preserved.
            val result =
                restoreUseCase(liveGroups, liveSettings).restore(
                    json,
                    RestoreSelection(profilesAndGroups = true, routes = false, settings = false),
                )
            assertTrue(result is RestoreResult.Success)

            // profiles+groups were restored ...
            assertEquals(listOf(groupWithProfile), liveGroups.list())
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
                restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository())
                    .restore("{}", RestoreSelection(false, false, false))
            assertTrue(result is RestoreResult.NothingSelected)
            assertEquals(1, ruleDao.allRules().first().size)
        }

    @Test
    fun `SHARE backup preview reports profiles that cannot be decoded`() =
        runTest {
            val exportGroups = FakeGroupRepository(mutableListOf(sampleGroup.copy(members = listOf(sampleProfile))))
            val exportSettings = FakeAppSettingsRepository()
            val json =
                BackupSerializer.encodeToString(
                    // SHARE strips the Shadowsocks password (REDACTED), so the
                    // profile can no longer be decoded into a complete ProxyProfile.
                    exportUseCase(exportGroups, exportSettings)
                        .gather(BackupVariant.SHARE, "1.0.0", 0L),
                )

            val preview =
                restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository())
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
            val exportGroups = FakeGroupRepository(mutableListOf(sampleGroup.copy(members = listOf(sampleProfile))))
            val exportSettings = FakeAppSettingsRepository()
            val json =
                BackupSerializer.encodeToString(
                    exportUseCase(exportGroups, exportSettings)
                        .gather(BackupVariant.FULL, "2.0.0", 0L),
                )

            val preview =
                restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository())
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
            restoreUseCase(FakeGroupRepository(mutableListOf()), FakeAppSettingsRepository())
                .preview(json)
        assertTrue(preview is BackupPreviewResult.UnsupportedVersion)
        assertEquals(future, (preview as BackupPreviewResult.UnsupportedVersion).found)
    }

    // -- Fakes ----------------------------------------------------------------

    private class FakeGroupRepository(
        private val groups: MutableList<ProxyGroup>,
        private val failOnReplace: Boolean = false,
    ) : ProxyGroupRepository {
        private val state = MutableStateFlow(groups.toList())
        private var replaceCalls = 0

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
            replaceCalls += 1
            check(!failOnReplace || replaceCalls > 1) { "group replace failed" }
            this.groups.clear()
            this.groups.addAll(groups)
            state.value = this.groups.toList()
        }

        override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
    }

    private class FakeAppSettingsRepository(
        current: AppSettings = AppSettings.getDefaultInstance(),
        private val failOnReplace: Boolean = false,
        private val failAfterReplace: Boolean = false,
        private val failRollback: Boolean = false,
        private val cancelAfterReplace: Boolean = false,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(current)
        var replaceCalls: Int = 0
            private set
        var rollbackWasActive: Boolean = false
            private set

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
            replaceCalls += 1
            if (replaceCalls > 1) {
                rollbackWasActive = kotlinx.coroutines.currentCoroutineContext().isActive
                check(!failRollback) { "settings rollback failed" }
            }
            if (replaceCalls == 1 && (failAfterReplace || cancelAfterReplace)) {
                state.value = settings
            }
            if (replaceCalls == 1 && cancelAfterReplace) throw CancellationException("settings cancelled")
            check(replaceCalls != 1 || (!failOnReplace && !failAfterReplace)) { "settings replace failed" }
            state.value = settings
        }
    }

    private class FakePrivateDataStore(
        var data: BackupPrivateDataV1,
    ) : BackupPrivateDataStore {
        override suspend fun snapshot(): BackupPrivateDataV1 = data

        override suspend fun replaceAll(data: BackupPrivateDataV1) {
            this.data = data
        }
    }
}
