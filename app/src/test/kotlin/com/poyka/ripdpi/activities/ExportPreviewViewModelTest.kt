package com.poyka.ripdpi.activities

import androidx.lifecycle.SavedStateHandle
import com.poyka.ripdpi.diagnostics.export.CheckedDiagnosticsExportHandoff
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeasePhase
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import com.poyka.ripdpi.diagnostics.export.PreparedDiagnosticsExport
import com.poyka.ripdpi.diagnostics.export.PreparedDiagnosticsExportService
import com.poyka.ripdpi.ui.components.export.ExportPreviewPresentation
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.OutputStream

class ExportPreviewViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun `preparing and ready never consume before explicit confirm and duplicate confirm consumes once`() =
        runTest {
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive())
            advanceUntilIdle()
            val ready = requireNotNull(owner.state.value)
            assertTrue(ready.presentation is ExportPreviewPresentation.Ready)
            assertEquals(0, service.consumed.size)
            owner.confirm(ready.token)
            owner.confirm(ready.token)
            advanceUntilIdle()
            assertEquals(listOf(ready.token.leaseId), service.consumed)
            owner.cancel(ready.token)
            advanceUntilIdle()
        }

    @Test fun `stale sheet callbacks cannot consume or cancel replacement`() =
        runTest {
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive())
            advanceUntilIdle()
            val old = requireNotNull(owner.state.value)
            owner.prepare(archive())
            advanceUntilIdle()
            val replacement = requireNotNull(owner.state.value)
            owner.confirm(old.token)
            owner.cancel(old.token)
            assertEquals(replacement, owner.state.value)
            assertTrue(service.consumed.isEmpty())
            assertTrue(service.discarded.all { it == old.token.leaseId })
        }

    @Test fun `cancel fences noncooperative late preparation and discards only its own lease`() =
        runTest {
            val service = PreviewService().apply { blocker = CompletableDeferred() }
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive())
            advanceUntilIdle()
            val preparing = requireNotNull(owner.state.value)
            owner.cancel(preparing.token)
            assertNull(owner.state.value)
            service.blocker!!.complete(Unit)
            advanceUntilIdle()
            assertNull(owner.state.value)
            assertTrue(service.discarded.isNotEmpty())
            assertTrue(service.discarded.all { it == preparing.token.leaseId })
            assertTrue(service.consumed.isEmpty())
        }

    @Test fun `all purposes hand off once only after confirmation and unlocked content`() =
        runTest {
            DiagnosticsExportPurpose.entries.forEach { purpose ->
                val service = PreviewService()
                val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
                owner.prepare(
                    if (purpose == DiagnosticsExportPurpose.SaveLogs) {
                        DiagnosticsExportPreparation.Logs
                    } else {
                        archive().copy(purpose = purpose)
                    },
                )
                advanceUntilIdle()
                val ready = requireNotNull(owner.state.value)
                assertEquals(purpose.name, (ready.presentation as ExportPreviewPresentation.Ready).purpose.name)
                assertTrue(service.consumed.isEmpty())
                owner.confirm(ready.token)
                advanceUntilIdle()
                var launches = 0
                val delivery =
                    backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        owner.handoff(ready.token) { launches++ }
                    }
                runCurrent()
                assertEquals(0, launches)
                owner.setContentUnlocked(true)
                runCurrent()
                delivery.join()
                owner.handoff(ready.token) { launches++ }
                owner.confirm(ready.token)
                assertEquals(1, launches)
                assertEquals(1, service.prepareCount)
                assertEquals(listOf(ready.token.leaseId), service.consumed)
                assertNull(owner.state.value)
                assertEquals(
                    purpose == DiagnosticsExportPurpose.ShareSummary,
                    ready.token.leaseId in service.discarded,
                )
            }
        }

    @Test fun `relock during suspended validation prevents launch until unlocked again`() =
        runTest {
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive())
            advanceUntilIdle()
            val token = owner.state.value!!.token
            owner.confirm(token)
            advanceUntilIdle()
            service.validationBlocker = CompletableDeferred()
            owner.setContentUnlocked(true)
            var launches = 0
            val delivery = backgroundScope.launch { owner.handoff(token) { launches++ } }
            runCurrent()
            owner.setContentUnlocked(false)
            service.validationBlocker!!.complete(Unit)
            runCurrent()
            assertEquals(0, launches)
            assertEquals(token, owner.handoffs.value)
            owner.setContentUnlocked(true)
            runCurrent()
            delivery.join()
            assertEquals(1, launches)
            assertNull(owner.handoffs.value)
        }

    @Test fun `expiry while relocked rejects stale summary capability before unlock handoff`() =
        runTest {
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive().copy(purpose = DiagnosticsExportPurpose.ShareSummary))
            advanceUntilIdle()
            val token = requireNotNull(owner.state.value).token
            owner.setContentUnlocked(true)
            owner.confirm(token)
            advanceUntilIdle()
            service.validationBlocker = CompletableDeferred()
            var launches = 0
            val handoff = launch { owner.handoff(token) { launches++ } }
            runCurrent()
            owner.setContentUnlocked(false)
            service.validationBlocker!!.complete(Unit)
            runCurrent()
            assertEquals(0, launches)
            // The first checked capability was valid. It expires during the subsequent lock wait.
            service.validationFailure = IllegalStateException("Prepared lease expired")
            owner.setContentUnlocked(true)
            advanceUntilIdle()
            handoff.join()
            assertEquals("Expired summary was handed off using a stale check", 0, launches)
            assertEquals(3, service.validationCount)
            assertEquals(listOf(token.leaseId), service.discarded)
            assertTrue(owner.state.value!!.presentation is ExportPreviewPresentation.Error)
        }

    @Test fun `cancel after durable consumption invalidates queued external handoff`() =
        runTest {
            val service = PreviewService()
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive())
            advanceUntilIdle()
            val ready = requireNotNull(owner.state.value)
            owner.confirm(ready.token)
            advanceUntilIdle()
            assertEquals(ready.token, owner.handoffs.value)
            owner.cancel(ready.token)
            owner.setContentUnlocked(true)
            var launches = 0
            owner.handoff(ready.token) { launches++ }
            runCurrent()
            assertEquals(0, launches)
            assertNull(owner.handoffs.value)
            assertTrue(ready.token.leaseId in service.discarded)
        }

    @Test fun `prepare failure has neutral localized error and no confirm capability`() =
        runTest {
            val service = PreviewService().apply { prepareFailure = IllegalStateException("private /path secret CLI") }
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive())
            advanceUntilIdle()
            val error = requireNotNull(owner.state.value)
            val message = (error.presentation as ExportPreviewPresentation.Error).message
            assertTrue(!message.contains("private") && !message.contains("/path") && !message.contains("CLI"))
            owner.confirm(error.token)
            advanceUntilIdle()
            assertTrue(service.consumed.isEmpty())
        }

    @Test fun `cleanup error cannot overwrite replacement ready and remains observable`() =
        runTest {
            val service = PreviewService().apply { discardFailure = IllegalStateException("private path") }
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            owner.prepare(archive())
            advanceUntilIdle()
            val previous = requireNotNull(owner.state.value)
            owner.prepare(archive())
            advanceUntilIdle()
            runCurrent()
            assertTrue(owner.state.value!!.token !== previous.token)
            assertTrue(owner.state.value!!.presentation is ExportPreviewPresentation.Ready)
            assertTrue(owner.cleanupFailures.value)
            assertTrue(service.discarded.all { it == previous.token.leaseId })
        }

    @Test fun `recreated ready lease is inspected without preparing again and consumed lease never relaunches`() =
        runTest {
            val service = PreviewService()
            val id = DiagnosticsExportLeaseId.create()
            service.prepare(id, archive())
            val owner =
                ExportPreviewViewModel(
                    service,
                    SavedStateHandle(mapOf("prepared-export-lease" to id.encoded)),
                    FakeStringResolver(),
                    backgroundScope,
                )
            advanceUntilIdle()
            assertTrue(owner.state.value!!.presentation is ExportPreviewPresentation.Ready)
            assertEquals(1, service.prepareCount)
            service.consume(id)
            val recreated =
                ExportPreviewViewModel(
                    service,
                    SavedStateHandle(mapOf("prepared-export-lease" to id.encoded)),
                    FakeStringResolver(),
                    backgroundScope,
                )
            advanceUntilIdle()
            assertNull(recreated.state.value)
            assertNull(recreated.handoffs.value)
            assertTrue(service.discarded.isEmpty())
            assertEquals(1, service.consumed.size)
        }

    @Test fun `clearing owner fences late noncooperative preparation and cleans known lease`() =
        runTest {
            val service = PreviewService().apply { blocker = CompletableDeferred() }
            val owner = ExportPreviewViewModel(service, SavedStateHandle(), FakeStringResolver(), backgroundScope)
            val store = androidx.lifecycle.ViewModelStore().apply { put("preview", owner) }
            owner.prepare(archive())
            advanceUntilIdle()
            val token = owner.state.value!!.token
            store.clear()
            service.blocker!!.complete(Unit)
            advanceUntilIdle()
            runCurrent()
            assertNull(owner.state.value)
            assertTrue(service.discarded.isNotEmpty())
            assertTrue(service.discarded.all { it == token.leaseId })
            assertTrue(service.consumed.isEmpty())
        }
}

private fun archive() =
    DiagnosticsExportPreparation.Archive(
        DiagnosticsArchiveRequest(reason = DiagnosticsArchiveReason.SHARE_ARCHIVE, requestedAt = 1),
        DiagnosticsExportPurpose.ShareArchive,
    )

internal class PreviewService : PreparedDiagnosticsExportService {
    val consumed = mutableListOf<DiagnosticsExportLeaseId>()
    val discarded = mutableListOf<DiagnosticsExportLeaseId>()
    val previews = mutableMapOf<DiagnosticsExportLeaseId, PreparedDiagnosticsExport>()
    var blocker: CompletableDeferred<Unit>? = null
    var validationBlocker: CompletableDeferred<Unit>? = null
    var validationFailure: Exception? = null
    var validationCount = 0
    var prepareFailure: Exception? = null
    var discardFailure: Exception? = null
    var prepareCount = 0

    override suspend fun prepare(
        id: DiagnosticsExportLeaseId,
        preparation: DiagnosticsExportPreparation,
    ): PreparedDiagnosticsExport {
        prepareCount++
        blocker?.let { withContext(NonCancellable) { it.await() } }
        prepareFailure?.let { throw it }
        return PreparedDiagnosticsExport(
            id,
            (preparation as? DiagnosticsExportPreparation.Archive)?.purpose ?: DiagnosticsExportPurpose.SaveLogs,
            DiagnosticsExportLeasePhase.Ready,
            if (preparation == DiagnosticsExportPreparation.Logs) "ripdpi.log" else "ripdpi-diagnostics.zip",
            if (preparation == DiagnosticsExportPreparation.Logs) "text/plain" else "application/zip",
            10,
            "Final summary",
            listOf(if (preparation == DiagnosticsExportPreparation.Logs) "ripdpi.log" else "summary.txt"),
            Long.MAX_VALUE,
        ).also { previews[id] = it }
    }

    override suspend fun inspect(id: DiagnosticsExportLeaseId) = requireNotNull(previews[id])

    override suspend fun consume(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff {
        consumed += id
        val preview = requireNotNull(previews[id])
        check(preview.phase == DiagnosticsExportLeasePhase.Ready)
        previews[id] = preview.copy(phase = DiagnosticsExportLeasePhase.HandedOff)
        return validateHandoff(id)
    }

    override suspend fun validateHandoff(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff {
        validationCount++
        validationBlocker?.await()
        validationFailure?.let { throw it }
        return CheckedDiagnosticsExportHandoff(requireNotNull(previews[id]), "/private/prepared.zip")
    }

    override suspend fun copyPrepared(
        id: DiagnosticsExportLeaseId,
        destination: OutputStream,
    ) = error("Not consumed")

    override suspend fun discard(id: DiagnosticsExportLeaseId) {
        discarded += id
        discardFailure?.let { throw it }
    }
}

internal fun createExportPreviewForTest() =
    ExportPreviewViewModel(
        PreviewService(),
        SavedStateHandle(),
        FakeStringResolver(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
    )
