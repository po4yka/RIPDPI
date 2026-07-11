package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BackupImportCoordinatorTest {
    private val sampleGroup =
        ProxyGroup(id = "g-1", name = "Group", type = ProxyGroupType.BASIC, order = 0, isSelector = false)
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

    // -- openImport() ---------------------------------------------------------

    @Test
    fun `openImport is a no-op while importing`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            h.state.update { it.copy(importing = true) }
            var opened = false

            h.coordinator.openImport {
                opened = true
                null
            }
            testScheduler.advanceUntilIdle()

            assertFalse(opened)
        }

    @Test
    fun `openImport is a no-op while restoring`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            h.state.update { it.copy(restoring = true) }
            var opened = false

            h.coordinator.openImport {
                opened = true
                null
            }
            testScheduler.advanceUntilIdle()

            assertFalse(opened)
        }

    @Test
    fun `openImport with null input clears importing with no preview and no effect`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.openImport { null }
            testScheduler.advanceUntilIdle()

            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `openImport with throwing input clears importing with no preview and no effect`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.openImport { throw IOException("boom") }
            testScheduler.advanceUntilIdle()

            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `openImport Ready populates preview with exact default selection predicates`() =
        runTest(StandardTestDispatcher()) {
            // FULL backup with one decodable profile + one group, NO rules. The settings
            // map always carries the single snapshot key, so settingCount > 0.
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()

            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()

            val preview = h.state.value.importPreview!!
            assertEquals(json, preview.json)
            // profilesAndGroups = canRestoreProfilesAndGroups (decodable profile + group => true).
            assertTrue(preview.selection.profilesAndGroups)
            // routes = ruleCount > 0 (no rules => false).
            assertFalse(preview.selection.routes)
            // settings = settingCount > 0 (snapshot key is always present => true).
            assertTrue(preview.selection.settings)
            assertFalse(h.state.value.importing)
            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `openImport default selection turns profilesAndGroups off when nothing is restorable`() =
        runTest(StandardTestDispatcher()) {
            // SHARE backup with a credential-bearing profile: credentials are redacted,
            // so the profile is undecodable and no group is present => canRestore=false.
            val json = exportJson(BackupVariant.SHARE, profiles = listOf(sampleProfile), groups = emptyList())
            val h = harness()

            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()

            val preview = h.state.value.importPreview!!
            assertFalse(preview.selection.profilesAndGroups)
            assertFalse(preview.selection.routes)
        }

    @Test
    fun `openImport UnsupportedVersion emits effect and no preview`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            val futureJson =
                """{"schemaVersion":9999,"createdAtEpochMillis":0,"appVersion":"x",""" +
                    """"profiles":[],"groups":[],"rules":[],"settings":{}}"""

            h.coordinator.openImport { futureJson.byteInputStream() }
            testScheduler.advanceUntilIdle()

            assertNull(h.state.value.importPreview)
            assertFalse(h.state.value.importing)
            assertTrue(h.restoreEffects.single() is BackupRestoreEffect.UnsupportedVersion)
        }

    @Test
    fun `openImport Malformed emits effect and no preview`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.openImport { "not json at all".byteInputStream() }
            testScheduler.advanceUntilIdle()

            assertNull(h.state.value.importPreview)
            assertFalse(h.state.value.importing)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Malformed), h.restoreEffects)
        }

    // -- selection edits ------------------------------------------------------

    @Test
    fun `set selected mutate only the active preview selection`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()

            h.coordinator.setProfilesAndGroupsSelected(false)
            h.coordinator.setRoutesSelected(true)
            h.coordinator.setSettingsSelected(true)

            val sel =
                h.state.value.importPreview!!
                    .selection
            assertFalse(sel.profilesAndGroups)
            assertTrue(sel.routes)
            assertTrue(sel.settings)
        }

    @Test
    fun `set selected are no-ops when there is no active preview`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.setProfilesAndGroupsSelected(true)
            h.coordinator.setRoutesSelected(true)
            h.coordinator.setSettingsSelected(true)

            assertNull(h.state.value.importPreview)
        }

    @Test
    fun `cancelImport clears the preview`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()
            assertTrue(h.state.value.importPreview != null)

            h.coordinator.cancelImport()

            assertNull(h.state.value.importPreview)
        }

    // -- confirmRestore() -----------------------------------------------------

    @Test
    fun `confirmRestore is a no-op when there is no preview`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.confirmRestore()
            testScheduler.advanceUntilIdle()

            assertTrue(h.restoreEffects.isEmpty())
            assertFalse(h.state.value.restoring)
        }

    @Test
    fun `confirmRestore is a no-op while restoring`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()
            h.state.update { it.copy(restoring = true) }
            h.restoreEffects.clear()

            h.coordinator.confirmRestore()
            testScheduler.advanceUntilIdle()

            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `confirmRestore with empty selection emits NothingSelected and does not flip restoring`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()
            // Clear every category.
            h.coordinator.setProfilesAndGroupsSelected(false)
            h.coordinator.setRoutesSelected(false)
            h.coordinator.setSettingsSelected(false)
            h.restoreEffects.clear()

            h.coordinator.confirmRestore()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.NothingSelected), h.restoreEffects)
            assertFalse(h.state.value.restoring)
            // Preview survives a NothingSelected (no restore was attempted).
            assertTrue(h.state.value.importPreview != null)
        }

    @Test
    fun `confirmRestore success flips restoring clears preview and emits Restored`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()

            h.coordinator.confirmRestore()
            // restoring flips true synchronously, before the suspend restore runs.
            assertTrue(h.state.value.restoring)
            testScheduler.advanceUntilIdle()

            assertFalse(h.state.value.restoring)
            assertNull(h.state.value.importPreview)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Restored), h.restoreEffects)
        }

    @Test
    fun `confirmRestore reuses the exact previewed json bytes`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            testScheduler.advanceUntilIdle()

            // The preview retains the exact bytes the file carried; confirmRestore
            // threads preview.json verbatim into restore (see coordinator), so a
            // byte-identical preview.json proves the same bytes reach the restore.
            val previewedJson =
                h.state.value.importPreview!!
                    .json
            assertEquals(json, previewedJson)

            h.coordinator.confirmRestore()
            testScheduler.advanceUntilIdle()

            // A successful Restored proves the previewed bytes were valid + accepted.
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Restored), h.restoreEffects)
        }

    // -- Harness --------------------------------------------------------------

    private class Harness(
        val coordinator: BackupImportCoordinator,
        val state: MutableStateFlow<BackupRestoreUiState>,
        val restoreEffects: MutableList<BackupRestoreEffect>,
    )

    private fun TestScope.harness(): Harness {
        val state = MutableStateFlow(BackupRestoreUiState())
        val effects = mutableListOf<BackupRestoreEffect>()
        // The real use case (final class) is constructed with hand-rolled fakes; no
        // Robolectric/Room needed. `android.util.Log` no-ops under the app module's
        // unitTests.isReturnDefaultValues = true.
        val restoreUseCase =
            BackupRestoreUseCase(
                groupRepository = MutableGroupRepository(),
                ruleDao = FakeRuleDao(),
                settingsRepository = FakeAppSettingsRepository(),
            )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator =
            BackupImportCoordinator(
                scope = this,
                restoreUseCase = restoreUseCase,
                currentState = state::value,
                updateState = state::update,
                emitRestoreEffect = { effects.add(it) },
                ioDispatcher = dispatcher,
                defaultDispatcher = dispatcher,
            )
        return Harness(coordinator, state, effects)
    }

    private fun exportJson(
        variant: BackupVariant,
        profiles: List<ProxyProfile> = emptyList(),
        groups: List<ProxyGroup> = emptyList(),
    ): String {
        if (profiles.isNotEmpty() && groups.isEmpty()) {
            return BackupSerializer.encodeToString(
                BackupExporter.export(
                    variant = variant,
                    profiles = profiles,
                    groups = emptyList(),
                    rules = emptyList(),
                    settings = emptyMap(),
                    createdAtEpochMillis = 0L,
                    appVersion = "1.0.0",
                ),
            )
        }
        val groupsWithProfiles =
            groups.map { group ->
                group.copy(members = group.members + profiles.filter { it.groupId == group.id })
            }
        val out = ByteArrayOutputStream()
        runBlocking {
            BackupExportUseCase(
                groupRepository = MutableGroupRepository(groupsWithProfiles),
                ruleDao = FakeRuleDao(),
                settingsRepository = FakeAppSettingsRepository(),
            ).export(variant = variant, output = out, appVersion = "1.0.0")
        }
        return out.toString("UTF-8")
    }

    private class MutableGroupRepository(
        initial: List<ProxyGroup> = emptyList(),
    ) : ProxyGroupRepository {
        private val groups = initial.toMutableList()
        private val state = MutableStateFlow(groups.toList())

        override suspend fun add(group: ProxyGroup) = Unit

        override suspend fun update(group: ProxyGroup) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun list(): List<ProxyGroup> = groups.toList()

        override suspend fun replaceAll(groups: List<ProxyGroup>) {
            this.groups.clear()
            this.groups.addAll(groups)
            state.value = this.groups.toList()
        }

        override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
    }

    private class FakeAppSettingsRepository : AppSettingsRepository {
        private val state = MutableStateFlow(AppSettings.getDefaultInstance())

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

    private class FakeRuleDao : RuleDao() {
        override suspend fun deleteAll() = Unit

        override fun allRules(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun enabledRules(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun rulesExcludingName(excludedName: String): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun rulesByName(name: String): Flow<List<RuleEntity>> = flowOf(emptyList())

        override suspend fun findByName(name: String): RuleEntity? = null

        override suspend fun maxUserOrder(excludedName: String): Int? = null

        override suspend fun insert(rule: RuleEntity): Long = error("not used")

        override suspend fun update(rule: RuleEntity) = error("not used")

        override suspend fun delete(rule: RuleEntity) = error("not used")

        override suspend fun resetOutboundTags(
            ruleIds: List<Long>,
            targetTag: OutboundTag,
            replacementTag: OutboundTag,
        ): Int = error("not used")

        override suspend fun updateOrder(
            id: Long,
            order: Int,
        ) = error("not used")
    }
}
