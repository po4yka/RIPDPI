package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.backup.BackupExportUseCase
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class BackupExportCoordinatorTest {
    // -- export() -------------------------------------------------------------

    @Test
    fun `export is a no-op when already exporting`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            h.state.update { it.copy(exporting = true) }
            var opened = false

            h.coordinator.export(BackupVariant.FULL, openOutput = {
                opened = true
                null
            }, onWriteFailed = {})
            testScheduler.advanceUntilIdle()

            assertFalse(opened)
            assertTrue(h.exportEffects.isEmpty())
        }

    @Test
    fun `export is a no-op when disabled by policy`() =
        runTest(StandardTestDispatcher()) {
            val h = harness(policyDisabled = true)
            var opened = false

            h.coordinator.export(BackupVariant.FULL, openOutput = {
                opened = true
                null
            }, onWriteFailed = {})
            testScheduler.advanceUntilIdle()

            assertFalse(opened)
            assertTrue(h.exportEffects.isEmpty())
        }

    @Test
    fun `export with null output flips exporting and emits exactly one Cancelled`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.export(
                BackupVariant.FULL,
                openOutput = { null },
                onWriteFailed = {},
                passphrase = ArchivePhrase.copyOf(),
            )
            assertTrue(h.state.value.exporting)
            testScheduler.advanceUntilIdle()

            assertFalse(h.state.value.exporting)
            assertEquals(listOf(BackupExportEffect.Cancelled), h.exportEffects)
        }

    @Test
    fun `export SHARE success emits Success with offerShare true and closes stream`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            val stream = CloseTrackingStream()

            h.coordinator.export(BackupVariant.SHARE, openOutput = { stream }, onWriteFailed = {})
            testScheduler.advanceUntilIdle()

            val effect = h.exportEffects.single() as BackupExportEffect.Success
            assertEquals(BackupVariant.SHARE, effect.variant)
            assertTrue(effect.offerShare)
            assertTrue(effect.byteCount > 0)
            assertTrue(stream.closed)
            assertFalse(h.state.value.exporting)
        }

    @Test
    fun `export FULL success emits Success with offerShare false`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.export(
                BackupVariant.FULL,
                openOutput = { ByteArrayOutputStream() },
                onWriteFailed = {},
                passphrase = ArchivePhrase.copyOf(),
            )
            testScheduler.advanceUntilIdle()

            val effect = h.exportEffects.single() as BackupExportEffect.Success
            assertEquals(BackupVariant.FULL, effect.variant)
            assertFalse(effect.offerShare)
        }

    @Test
    fun `export write failure invokes onWriteFailed once and emits WriteFailed and closes stream`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            val stream = FailingStream()
            var writeFailedCalls = 0

            h.coordinator.export(
                BackupVariant.FULL,
                openOutput = { stream },
                onWriteFailed = { writeFailedCalls++ },
                passphrase = ArchivePhrase.copyOf(),
            )
            testScheduler.advanceUntilIdle()

            assertEquals(1, writeFailedCalls)
            assertEquals(listOf<BackupExportEffect>(BackupExportEffect.WriteFailed), h.exportEffects)
            assertTrue(stream.closed)
            assertFalse(h.state.value.exporting)
        }

    // -- prepareShareBackup() -------------------------------------------------

    @Test
    fun `prepareShareBackup is a no-op when already sharing`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            h.state.update { it.copy(sharing = true) }
            var opened = false

            h.coordinator.prepareShareBackup {
                opened = true
                null
            }
            testScheduler.advanceUntilIdle()

            assertFalse(opened)
            assertTrue(h.shareEffects.isEmpty())
        }

    @Test
    fun `prepareShareBackup is a no-op when disabled by policy`() =
        runTest(StandardTestDispatcher()) {
            val h = harness(policyDisabled = true)
            var opened = false

            h.coordinator.prepareShareBackup {
                opened = true
                null
            }
            testScheduler.advanceUntilIdle()

            assertFalse(opened)
            assertTrue(h.shareEffects.isEmpty())
        }

    @Test
    fun `prepareShareBackup with null output flips sharing and emits Failed`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.prepareShareBackup { null }
            assertTrue(h.state.value.sharing)
            testScheduler.advanceUntilIdle()

            assertFalse(h.state.value.sharing)
            assertEquals(listOf<BackupShareEffect>(BackupShareEffect.Failed), h.shareEffects)
        }

    @Test
    fun `prepareShareBackup success always exports SHARE and emits Ready`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            val stream = CloseTrackingStream()

            h.coordinator.prepareShareBackup { stream }
            testScheduler.advanceUntilIdle()

            assertEquals(listOf<BackupShareEffect>(BackupShareEffect.Ready), h.shareEffects)
            // The forced-SHARE variant is observable through the redacted document:
            // SHARE marks the archive non-credential-bearing (FULL would be `true`).
            assertTrue(stream.text().contains("\"containsCredentials\": false"))
            assertFalse(h.state.value.sharing)
            assertTrue(stream.closed)
        }

    @Test
    fun `prepareShareBackup write failure emits Failed and closes stream`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            val stream = FailingStream()

            h.coordinator.prepareShareBackup { stream }
            testScheduler.advanceUntilIdle()

            assertEquals(listOf<BackupShareEffect>(BackupShareEffect.Failed), h.shareEffects)
            assertFalse(h.state.value.sharing)
            assertTrue(stream.closed)
        }

    // -- Harness --------------------------------------------------------------

    private class Harness(
        val coordinator: BackupExportCoordinator,
        val state: MutableStateFlow<BackupRestoreUiState>,
        val exportEffects: List<BackupExportEffect>,
        val shareEffects: List<BackupShareEffect>,
    )

    private companion object {
        val ArchivePhrase = "archive phrase fixture".toCharArray()
    }

    private fun TestScope.harness(policyDisabled: Boolean = false): Harness {
        val state = MutableStateFlow(BackupRestoreUiState(exportDisabledByPolicy = policyDisabled))
        val exportEffects = mutableListOf<BackupExportEffect>()
        val shareEffects = mutableListOf<BackupShareEffect>()
        val useCase =
            BackupExportUseCase(
                groupRepository = FakeGroupRepository(),
                ruleDao = FakeRuleDao(),
                settingsRepository = FakeAppSettingsRepository(),
            )
        val coordinator =
            BackupExportCoordinator(
                // The TestScope created by runTest drives launches via testScheduler.
                scope = this,
                exportUseCase = useCase,
                appVersion = "1.0.0",
                exportDisabledByPolicy = { state.value.exportDisabledByPolicy },
                currentState = state::value,
                updateState = state::update,
                emitExportEffect = { exportEffects.add(it) },
                emitShareEffect = { shareEffects.add(it) },
            )
        return Harness(coordinator, state, exportEffects, shareEffects)
    }

    private class CloseTrackingStream : OutputStream() {
        var closed = false
            private set
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) = buffer.write(b)

        fun text(): String = buffer.toString("UTF-8")

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FailingStream : OutputStream() {
        var closed = false
            private set

        override fun write(b: Int): Unit = throw IOException("boom")

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Unit = throw IOException("boom")

        override fun close() {
            closed = true
        }
    }

    private class FakeGroupRepository : ProxyGroupRepository {
        private val state = MutableStateFlow(emptyList<ProxyGroup>())

        override suspend fun add(group: ProxyGroup) = Unit

        override suspend fun update(group: ProxyGroup) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun list(): List<ProxyGroup> = emptyList()

        override suspend fun replaceAll(groups: List<ProxyGroup>) = Unit

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
