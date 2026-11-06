package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupPreviewResult
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.backup.BackupSchemaVersion
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.backup.RestoreResult
import com.poyka.ripdpi.data.backup.RestoreSelection
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.data.testing.backupExportUseCase
import com.poyka.ripdpi.data.testing.backupPrivateDataStore
import com.poyka.ripdpi.data.testing.backupRestoreUseCase
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.Dispatchers
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
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRestoreUseCaseTest {
    private lateinit var fixture: RoomProfileFixture
    private val all = RestoreSelection(true, true, true)
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

    @Before
    fun setUp() =
        runTest {
            fixture =
                RoomProfileFixture(
                    ApplicationProvider.getApplicationContext<Context>(),
                    testPauseAuthority(initialize = false),
                )
            fixture.initialize()
            fixture.database.ruleDao().deleteAll()
        }

    @After
    fun tearDown() = fixture.close()

    private fun settings(port: Int) =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setProxyPort(port)
            .build()

    private suspend fun seed(
        groups: List<ProxyGroup> = listOf(sampleGroup),
        rules: List<RuleEntity> = listOf(sampleRule),
        settings: AppSettings = settings(1111),
        privateData: BackupPrivateDataV1 = BackupPrivateDataV1(),
    ) {
        fixture.coordinator.restoreBackup(
            fixture.coordinator.captureMutation(ProfileMutationOrigin.RestoreProfiles),
            groups,
            rules,
            settings,
            privateData,
        )
    }

    private fun document(
        groups: List<ProxyGroup> = listOf(sampleGroup.copy(id = "target")),
        profiles: List<ProxyProfile> = emptyList(),
        rules: List<RuleEntity> = listOf(sampleRule.copy(name = "target-rule")),
        settings: AppSettings? = settings(9999),
        privateData: BackupPrivateDataV1? = null,
        variant: BackupVariant = BackupVariant.FULL,
    ) = BackupSerializer.encodeToString(
        BackupExporter.export(
            variant,
            profiles,
            groups,
            rules,
            settings?.let { BackupSettingsConverter.toMap(it, variant) }.orEmpty(),
            0L,
            "1.0.0",
            privateData,
        ),
    )

    private suspend fun snapshot(variant: BackupVariant = BackupVariant.FULL) =
        fixture
            .backupExportUseCase(
                AppCoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default),
            ).gather(variant, "1.0.0", 0L)

    @Test
    fun `restore clears a pending app lock reset notice`() =
        runTest {
            seed(settings = settings(1111).toBuilder().setAppLockResetNoticePending(true).build())
            val result = fixture.backupRestoreUseCase().restore(document(), RestoreSelection(false, false, true))
            assertTrue(result is RestoreResult.Success)
            assertEquals(9999, fixture.settings.snapshot().proxyPort)
            assertFalse(fixture.settings.snapshot().appLockResetNoticePending)
        }

    @Test
    fun `absent settings snapshot leaves live settings unchanged`() =
        runTest {
            seed()
            val before = fixture.settings.snapshot()
            val result =
                fixture.backupRestoreUseCase().restore(
                    document(settings = null),
                    RestoreSelection(false, false, true),
                )
            assertTrue(result is RestoreResult.Success)
            assertEquals(before, fixture.settings.snapshot())
        }

    @Test
    fun `invalid settings value in a valid archive aborts before any write`() =
        runTest {
            val document =
                BackupExporter.export(
                    variant = BackupVariant.FULL,
                    profiles = emptyList(),
                    groups = emptyList(),
                    rules = emptyList(),
                    settings = mapOf("app_settings.snapshot_json.v1" to """{"proxyPort":"invalid"}"""),
                    createdAtEpochMillis = 0L,
                    appVersion = "1.0.0",
                )
            val groupWithProfile = sampleGroup.copy(members = listOf(sampleProfile))
            val existingSettings = settings(4242)
            seed(groups = listOf(groupWithProfile), settings = existingSettings)
            val existingRules = fixture.database.ruleDao().allRules().first()

            val result =
                fixture.backupRestoreUseCase().restore(
                    BackupSerializer.encodeToString(document),
                    all,
                )

            assertTrue(result is RestoreResult.Aborted)
            assertEquals(listOf(groupWithProfile), fixture.groups.list())
            assertEquals(existingRules, fixture.database.ruleDao().allRules().first())
            assertEquals(existingSettings, fixture.settings.snapshot())
        }

    @Test
    fun `restore succeeds and requests restart without reading committed rows again`() =
        runTest {
            seed()
            val rowsWritten =
                java.util.concurrent.atomic
                    .AtomicBoolean()
            fixture.beforeSeal = { namespace, _ ->
                if (namespace == "app_settings") rowsWritten.set(true)
            }
            fixture.beforeOpen = { _, _ ->
                check(!rowsWritten.get()) { "Post-commit storage is unavailable" }
            }

            val result = fixture.backupRestoreUseCase().restore(document(), all)

            assertEquals(RestoreResult.Success(restartRequired = true), result)
            assertFalse(checkNotNull(fixture.authority.states.value).profileUtility.catalogReady)
            fixture.beforeOpen = null
            fixture.beforeSeal = null
            fixture = fixture.reopen()
            assertEquals(9999, fixture.settings.snapshot().proxyPort)
            assertEquals(
                listOf(sampleRule.copy(name = "target-rule")),
                fixture.database
                    .ruleDao()
                    .allRules()
                    .first()
                    .map { it.copy(id = 0L) },
            )
            fixture.coordinator.recover()
            assertTrue(checkNotNull(fixture.authority.states.value).profileUtility.catalogReady)
        }

    @Test
    fun `settings write failure rolls back groups rules and private data after reopen`() =
        runTest {
            seed(privateData = BackupPrivateDataV1(relayProfiles = listOf(RelayProfileRecord(id = "live"))))
            val before = snapshot()
            fixture.beforeSeal = { namespace, _ -> check(namespace != "app_settings") { "Fixture write failure" } }
            val result =
                fixture.backupRestoreUseCase().restore(
                    document(
                        privateData = BackupPrivateDataV1(relayProfiles = listOf(RelayProfileRecord(id = "target"))),
                    ),
                    all,
                )
            assertTrue(result is RestoreResult.Aborted)
            fixture.beforeSeal = null
            assertEquals(before, snapshot())
            fixture = fixture.reopen()
            assertEquals(before, snapshot())
        }

    @Test
    fun `cancellation rolls back written rows and remains cancellation`() =
        runTest {
            seed()
            val before = snapshot()
            fixture.beforeSeal =
                { namespace, _ -> if (namespace == "app_settings") throw CancellationException("Fixture cancellation") }
            val result = runCatching { fixture.backupRestoreUseCase().restore(document(), all) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            fixture.beforeSeal = null
            assertEquals(before, snapshot())
            fixture = fixture.reopen()
            assertEquals(before, snapshot())
        }

    @Test
    fun `group write failure leaves private profiles unchanged`() =
        runTest {
            val privateData = BackupPrivateDataV1(relayProfiles = listOf(RelayProfileRecord(id = "live")))
            seed(privateData = privateData)
            fixture.beforeSeal = { namespace, _ -> check(namespace != "proxy_groups") { "Fixture write failure" } }
            val result =
                fixture.backupRestoreUseCase().restore(
                    document(
                        privateData = BackupPrivateDataV1(relayProfiles = listOf(RelayProfileRecord(id = "target"))),
                    ),
                    all,
                )
            assertTrue(result is RestoreResult.Aborted)
            fixture.beforeSeal = null
            assertEquals(privateData, fixture.backupPrivateDataStore().snapshot())
        }

    @Test
    fun `FULL export then wipe then import restores all categories`() =
        runTest {
            seed(groups = listOf(sampleGroup.copy(members = listOf(sampleProfile))), settings = settings(4242))
            val before = snapshot()
            fixture.coordinator.runReset {}
            assertTrue(fixture.groups.list().isEmpty())
            val result = fixture.backupRestoreUseCase().restore(BackupSerializer.encodeToString(before), all)
            assertTrue(result is RestoreResult.Success)
            val after = snapshot()
            assertTrue(after.groups.all { it.ownershipGeneration.isNotBlank() })
            assertEquals(
                before.copy(groups = before.groups.map { it.copy(ownershipGeneration = "") }),
                after.copy(groups = after.groups.map { it.copy(ownershipGeneration = "") }),
            )
        }

    @Test
    fun `legacy top-level profile is restored into its matching group`() =
        runTest {
            val result =
                fixture.backupRestoreUseCase().restore(
                    document(groups = listOf(sampleGroup), profiles = listOf(sampleProfile)),
                    RestoreSelection(true, false, false),
                )
            assertTrue(result is RestoreResult.Success)
            assertEquals(
                listOf(sampleGroup.copy(members = listOf(sampleProfile))),
                fixture.groups.list().map { it.copy(ownershipGeneration = "") },
            )
        }

    @Test
    fun `profile referencing a missing group aborts before any write`() =
        runTest {
            seed()
            val before = snapshot()
            val result =
                fixture.backupRestoreUseCase().restore(
                    document(groups = emptyList(), profiles = listOf(sampleProfile)),
                    all,
                )
            assertTrue(result is RestoreResult.Aborted)
            assertEquals(before, snapshot())
        }

    @Test
    fun `newer schema and malformed JSON leave the database unchanged`() =
        runTest {
            seed()
            val before = snapshot()
            val future =
                """
                {"schemaVersion":${BackupSchemaVersion + 1},"createdAtEpochMillis":0,"appVersion":"x",
                "profiles":[],"groups":[],"rules":[],"settings":{}}
                """.trimIndent()
            assertTrue(fixture.backupRestoreUseCase().restore(future, all) is RestoreResult.UnsupportedVersion)
            assertTrue(fixture.backupRestoreUseCase().restore("not json", all) is RestoreResult.Aborted)
            assertEquals(before, snapshot())
        }

    @Test
    fun `selective restore preserves unchecked categories`() =
        runTest {
            seed()
            val rules =
                fixture.database
                    .ruleDao()
                    .allRules()
                    .first()
            val settings = fixture.settings.snapshot()
            val result =
                fixture.backupRestoreUseCase().restore(
                    document(groups = listOf(sampleGroup.copy(members = listOf(sampleProfile)))),
                    RestoreSelection(true, false, false),
                )
            assertTrue(result is RestoreResult.Success)
            assertEquals(
                listOf(sampleGroup.copy(members = listOf(sampleProfile))),
                fixture.groups.list().map { it.copy(ownershipGeneration = "") },
            )
            assertEquals(
                rules,
                fixture.database
                    .ruleDao()
                    .allRules()
                    .first(),
            )
            assertEquals(settings, fixture.settings.snapshot())
        }

    @Test
    fun `nothing selected does not parse or write`() =
        runTest {
            seed()
            val before = snapshot()
            assertEquals(
                RestoreResult.NothingSelected,
                fixture.backupRestoreUseCase().restore("{}", RestoreSelection(false, false, false)),
            )
            assertEquals(before, snapshot())
        }

    @Test
    fun `SHARE preview identifies redacted profiles while FULL preview can restore them`() =
        runTest {
            seed(groups = listOf(sampleGroup.copy(members = listOf(sampleProfile))))
            val share =
                fixture.backupRestoreUseCase().preview(
                    BackupSerializer.encodeToString(snapshot(BackupVariant.SHARE)),
                )
            val sharePreview = (share as BackupPreviewResult.Ready).preview
            assertEquals(0, sharePreview.restorableProfileCount)
            assertEquals(listOf("SS"), sharePreview.undecodableProfiles)
            assertFalse(sharePreview.containsCredentials)
            assertEquals(1, sharePreview.groupCount)
            val full = fixture.backupRestoreUseCase().preview(BackupSerializer.encodeToString(snapshot()))
            val fullPreview = (full as BackupPreviewResult.Ready).preview
            assertEquals(BackupSchemaVersion, fullPreview.schemaVersion)
            assertEquals(1, fullPreview.restorableProfileCount)
            assertEquals(1, fullPreview.groupCount)
            assertEquals(1, fullPreview.ruleCount)
            assertTrue(fullPreview.settingCount > 0)
            assertFalse(fullPreview.hasUndecodableProfiles)
            assertTrue(fullPreview.containsCredentials)
        }

    @Test
    fun `preview surfaces newer schema without opening storage`() {
        val future = BackupSchemaVersion + 5
        val json =
            """
            {"schemaVersion":$future,"createdAtEpochMillis":0,"appVersion":"x",
            "profiles":[],"groups":[],"rules":[],"settings":{}}
            """.trimIndent()
        val preview = fixture.backupRestoreUseCase().preview(json)
        assertEquals(future, (preview as BackupPreviewResult.UnsupportedVersion).found)
    }
}
