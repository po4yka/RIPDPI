package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.backup.BackupEncryption
import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.testing.backupRestoreUseCase
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class BackupImportCoordinatorTest : BackupRoomTest() {
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
            awaitOperations()

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
            awaitOperations()

            assertFalse(opened)
        }

    @Test
    fun `openImport with null input clears importing with no preview and no effect`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.openImport { null }
            awaitOperations()

            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `openImport with throwing input clears importing and emits Malformed`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.openImport { throw IOException("boom") }
            awaitOperations()

            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Malformed), h.restoreEffects)
        }

    @Test
    fun `openImport rejects input above the byte cap closes it and clears importing`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val bytes = json.toByteArray(Charsets.UTF_8)
            val input = CloseTrackingInputStream(bytes)
            val h = harness(maxImportBytes = bytes.size - 1)

            h.coordinator.openImport { input }
            awaitOperations()

            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertTrue(input.closed)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Malformed), h.restoreEffects)
        }

    @Test
    fun `openImport accepts valid backup exactly at the byte cap`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val bytes = json.toByteArray(Charsets.UTF_8)
            val h = harness(maxImportBytes = bytes.size)

            h.coordinator.openImport { bytes.inputStream() }
            awaitOperations()

            assertFalse(h.state.value.importing)
            assertEquals(
                json,
                h.state.value.importPreview
                    ?.json,
            )
            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `openImport rejects invalid UTF-8 closes input and emits Malformed`() =
        runTest(StandardTestDispatcher()) {
            val input = CloseTrackingInputStream(byteArrayOf(0xc3.toByte(), 0x28))
            val h = harness()

            h.coordinator.openImport { input }
            awaitOperations()

            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertTrue(input.closed)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Malformed), h.restoreEffects)
        }

    @Test
    fun `openImport cancellation clears importing and rethrows`() =
        runTest(StandardTestDispatcher()) {
            val cancellation = CancellationException("cancelled")
            val input = CancellationInputStream(cancellation)
            val h = harness()

            h.coordinator.openImport { input }
            val importJob = coroutineContext[Job]!!.children.single()
            var completionCause: Throwable? = null
            importJob.invokeOnCompletion { completionCause = it }
            awaitOperations()

            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertTrue(input.closed)
            assertTrue(h.restoreEffects.isEmpty())
            assertTrue(importJob.isCancelled)
            assertTrue(completionCause is CancellationException)
            assertEquals(cancellation.message, completionCause?.message)
        }

    @Test
    fun `openImport Ready populates preview with exact default selection predicates`() =
        runTest(StandardTestDispatcher()) {
            // FULL backup with one decodable profile + one group, NO rules. The settings
            // map always carries the single snapshot key, so settingCount > 0.
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()

            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()

            val preview = h.state.value.importPreview!!
            assertEquals(json, preview.json)
            assertFalse(preview.encrypted)
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
    fun `encrypted FULL import waits for passphrase then opens preview`() =
        runTest(StandardTestDispatcher()) {
            val archive = encryptedArchive()
            val h = harness()

            h.coordinator.openImport { archive.inputStream() }
            awaitOperations()

            assertTrue(h.state.value.encryptedImportPending)
            assertNull(h.state.value.importPreview)

            h.coordinator.unlockEncryptedImport(ArchivePhrase.copyOf())
            awaitOperations()

            assertFalse(h.state.value.encryptedImportPending)
            assertTrue(
                h.state.value.importPreview
                    ?.preview
                    ?.containsCredentials == true,
            )
            assertTrue(
                h.state.value.importPreview
                    ?.encrypted == true,
            )
            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `wrong passphrase keeps encrypted import retryable`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            h.coordinator.openImport { encryptedArchive().inputStream() }
            awaitOperations()

            h.coordinator.unlockEncryptedImport("wrong password".toCharArray())
            awaitOperations()

            assertTrue(h.state.value.encryptedImportPending)
            assertNull(h.state.value.importPreview)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.DecryptionFailed), h.restoreEffects)
        }

    @Test
    fun `cancelling the passphrase dialog during unlock stops the unlock without an error`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()
            h.coordinator.openImport { encryptedArchive().inputStream() }
            awaitOperations()

            h.coordinator.unlockEncryptedImport(ArchivePhrase.copyOf())
            h.coordinator.cancelEncryptedImport()
            awaitOperations()

            assertFalse(h.state.value.encryptedImportPending)
            assertFalse(h.state.value.importing)
            assertNull(h.state.value.importPreview)
            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `a cancelled unlock that unwinds late keeps a newer import in progress`() =
        runTest(StandardTestDispatcher()) {
            val held = HeldDispatcher()
            val h = harness(dispatcher = held)
            h.coordinator.openImport { encryptedArchive().inputStream() }
            drain(held)
            h.coordinator.unlockEncryptedImport(ArchivePhrase.copyOf())
            testScheduler.runCurrent()
            val decrypt = held.tasks.removeFirst()

            h.coordinator.cancelEncryptedImport()
            h.coordinator.openImport { exportJson(BackupVariant.FULL).byteInputStream() }
            testScheduler.runCurrent()
            decrypt.run()
            testScheduler.runCurrent()
            val importingWhileNewerImportRuns = h.state.value.importing
            drain(held)

            assertTrue(importingWhileNewerImportRuns)
            assertFalse(h.state.value.importing)
            assertTrue(h.state.value.importPreview != null)
        }

    @Test
    fun `a cancelled unlock whose decrypt then fails stays silent for a newer import`() =
        runTest(StandardTestDispatcher()) {
            val held = HeldDispatcher()
            val h = harness(dispatcher = held)
            h.coordinator.openImport { encryptedArchive().inputStream() }
            drain(held)
            h.coordinator.unlockEncryptedImport("wrong passphrase".toCharArray())
            testScheduler.runCurrent()
            val decrypt = held.tasks.removeFirst()
            val decryptStarted = CountDownLatch(1)
            val worker =
                thread {
                    decryptStarted.countDown()
                    decrypt.run()
                }
            decryptStarted.await()
            // Past the dispatch-time cancellation check, inside the PBKDF2 derivation (210k iterations).
            Thread.sleep(10L)

            h.coordinator.cancelEncryptedImport()
            h.coordinator.openImport { exportJson(BackupVariant.FULL).byteInputStream() }
            testScheduler.runCurrent()
            worker.join()
            testScheduler.runCurrent()
            val importingWhileNewerImportRuns = h.state.value.importing
            drain(held)

            assertTrue(importingWhileNewerImportRuns)
            assertFalse(BackupRestoreEffect.DecryptionFailed in h.restoreEffects)
            assertTrue(h.state.value.importPreview != null)
        }

    private fun TestScope.drain(held: HeldDispatcher) {
        testScheduler.runCurrent()
        while (held.tasks.isNotEmpty()) {
            held.tasks.removeFirst().run()
            testScheduler.runCurrent()
        }
    }

    /** Runs dispatched work only when the test says so, like a decrypt still busy on another thread. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }
    }

    @Test
    fun `openImport default selection turns profilesAndGroups off when nothing is restorable`() =
        runTest(StandardTestDispatcher()) {
            // SHARE backup with a credential-bearing profile: credentials are redacted,
            // so the profile is undecodable and no group is present => canRestore=false.
            val json = exportJson(BackupVariant.SHARE, profiles = listOf(sampleProfile), groups = emptyList())
            val h = harness()

            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()

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
            awaitOperations()

            assertNull(h.state.value.importPreview)
            assertFalse(h.state.value.importing)
            assertTrue(h.restoreEffects.single() is BackupRestoreEffect.UnsupportedVersion)
        }

    @Test
    fun `openImport Malformed emits effect and no preview`() =
        runTest(StandardTestDispatcher()) {
            val h = harness()

            h.coordinator.openImport { "not json at all".byteInputStream() }
            awaitOperations()

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
            awaitOperations()

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
            awaitOperations()
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
            awaitOperations()

            assertTrue(h.restoreEffects.isEmpty())
            assertFalse(h.state.value.restoring)
        }

    @Test
    fun `confirmRestore is a no-op while restoring`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()
            h.state.update { it.copy(restoring = true) }
            h.restoreEffects.clear()

            h.coordinator.confirmRestore()
            awaitOperations()

            assertTrue(h.restoreEffects.isEmpty())
        }

    @Test
    fun `confirmRestore with empty selection emits NothingSelected and does not flip restoring`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()
            // Clear every category.
            h.coordinator.setProfilesAndGroupsSelected(false)
            h.coordinator.setRoutesSelected(false)
            h.coordinator.setSettingsSelected(false)
            h.restoreEffects.clear()

            h.coordinator.confirmRestore()
            awaitOperations()

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
            awaitOperations()

            h.coordinator.confirmRestore()
            // restoring flips true synchronously, before the suspend restore runs.
            assertTrue(h.state.value.restoring)
            awaitOperations()

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
            awaitOperations()

            // The preview retains the exact bytes the file carried; confirmRestore
            // threads preview.json verbatim into restore (see coordinator), so a
            // byte-identical preview.json proves the same bytes reach the restore.
            val previewedJson =
                h.state.value.importPreview!!
                    .json
            assertEquals(json, previewedJson)

            h.coordinator.confirmRestore()
            awaitOperations()

            // A successful Restored proves the previewed bytes were valid + accepted.
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Restored), h.restoreEffects)
        }

    @Test
    fun `confirmRestore maps transaction failure to Malformed`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness(beforeSeal = { throw IOException("boom") })
            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()

            h.coordinator.confirmRestore()
            awaitOperations()

            assertFalse(h.state.value.restoring)
            assertNull(h.state.value.importPreview)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Malformed), h.restoreEffects)
        }

    @Test
    fun `confirmRestore maps typed Aborted to Malformed`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()
            h.state.update { state ->
                state.copy(importPreview = state.importPreview!!.copy(json = "not json"))
            }

            h.coordinator.confirmRestore()
            awaitOperations()

            assertFalse(h.state.value.restoring)
            assertNull(h.state.value.importPreview)
            assertEquals(listOf<BackupRestoreEffect>(BackupRestoreEffect.Malformed), h.restoreEffects)
        }

    @Test
    fun `confirmRestore maps typed UnsupportedVersion to effect`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val futureJson =
                """{"schemaVersion":9999,"createdAtEpochMillis":0,"appVersion":"x",""" +
                    """"profiles":[],"groups":[],"rules":[],"settings":{}}"""
            val h = harness()
            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()
            h.state.update { state ->
                state.copy(importPreview = state.importPreview!!.copy(json = futureJson))
            }

            h.coordinator.confirmRestore()
            awaitOperations()

            assertFalse(h.state.value.restoring)
            assertNull(h.state.value.importPreview)
            assertTrue(h.restoreEffects.single() is BackupRestoreEffect.UnsupportedVersion)
        }

    @Test
    fun `confirmRestore cancellation clears restoring preserves preview and rethrows`() =
        runTest(StandardTestDispatcher()) {
            val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
            val cancellation = CancellationException("cancelled")
            val h = harness(beforeSeal = { throw cancellation })
            h.coordinator.openImport { json.byteInputStream() }
            awaitOperations()
            val preview = h.state.value.importPreview

            h.coordinator.confirmRestore()
            val restoreJob = coroutineContext[Job]!!.children.single()
            var completionCause: Throwable? = null
            restoreJob.invokeOnCompletion { completionCause = it }
            awaitOperations()

            assertFalse(h.state.value.restoring)
            assertSame(preview, h.state.value.importPreview)
            assertTrue(h.restoreEffects.isEmpty())
            assertTrue(restoreJob.isCancelled)
            assertTrue(completionCause is CancellationException)
            assertEquals(cancellation.message, completionCause?.message)
        }

    // -- Harness --------------------------------------------------------------

    private class Harness(
        val coordinator: BackupImportCoordinator,
        val state: MutableStateFlow<BackupRestoreUiState>,
        val restoreEffects: MutableList<BackupRestoreEffect>,
    )

    private suspend fun TestScope.harness(
        maxImportBytes: Int = BackupMaxImportBytes,
        beforeSeal: (() -> Unit)? = null,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Harness {
        val state = MutableStateFlow(BackupRestoreUiState())
        val effects = mutableListOf<BackupRestoreEffect>()
        val fixture = roomFixture()
        fixture.beforeSeal = { _, _ -> beforeSeal?.invoke() }
        val restoreUseCase = fixture.backupRestoreUseCase()
        val coordinator =
            BackupImportCoordinator(
                scope = this,
                restoreUseCase = restoreUseCase,
                currentState = state::value,
                updateState = state::update,
                emitRestoreEffect = { effects.add(it) },
                ioDispatcher = dispatcher,
                defaultDispatcher = dispatcher,
                maxImportBytes = maxImportBytes,
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
        return BackupSerializer.encodeToString(
            BackupExporter.export(
                variant = variant,
                profiles = profiles,
                groups = groupsWithProfiles,
                rules = emptyList(),
                settings = BackupSettingsConverter.toMap(AppSettings.getDefaultInstance(), variant),
                createdAtEpochMillis = 0L,
                appVersion = "1.0.0",
            ),
        )
    }

    private fun encryptedArchive(): ByteArray {
        val json = exportJson(BackupVariant.FULL, profiles = listOf(sampleProfile), groups = listOf(sampleGroup))
        val output = ByteArrayOutputStream()
        BackupEncryption.encryptToStream(BackupImporter.import(json), ArchivePhrase.copyOf(), output)
        return output.toByteArray()
    }

    private companion object {
        val ArchivePhrase = "archive phrase fixture".toCharArray()
    }

    private class CloseTrackingInputStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class CancellationInputStream(
        private val cancellation: CancellationException,
    ) : InputStream() {
        var closed = false
            private set

        override fun read(): Int = throw cancellation

        override fun close() {
            closed = true
            super.close()
        }
    }
}
