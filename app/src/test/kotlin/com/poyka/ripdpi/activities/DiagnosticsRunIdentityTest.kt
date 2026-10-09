package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectionReason
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsRunIdentityTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `running session controls the displayed profile path and start time`() =
        runTest {
            val manager = managerWithRunningScan()
            manager.sessionsState.value = listOf(runningSession())
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val scan = viewModel.uiState.value.scan
            assertEquals("strategy", scan.selectedProfileId)
            assertEquals(ScanKind.STRATEGY_PROBE, scan.activeProgress?.scanKind)
            assertEquals(com.poyka.ripdpi.diagnostics.ScanPathMode.IN_PATH, scan.activePathMode)
            assertEquals(1_234L, scan.activeProgress?.scanStartedAtMs)

            viewModel.selectProfile("connectivity")
            manager.progressState.value = requireNotNull(manager.progressState.value).copy(completedSteps = 3)
            advanceUntilIdle()
            assertEquals("strategy", viewModel.uiState.value.scan.selectedProfileId)
            assertEquals(
                1_234L,
                viewModel.uiState.value.scan.activeProgress
                    ?.scanStartedAtMs,
            )
            collector.cancel()
        }

    @Test
    fun `observed start remains stable until the persisted session arrives`() =
        runTest {
            val manager = managerWithRunningScan()
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            val observedStart = requireNotNull(viewModel.uiState.value.scan.activeProgress).scanStartedAtMs
            assertTrue(observedStart > 0L)

            manager.progressState.value = requireNotNull(manager.progressState.value).copy(completedSteps = 3)
            advanceUntilIdle()
            assertEquals(
                observedStart,
                viewModel.uiState.value.scan.activeProgress
                    ?.scanStartedAtMs,
            )

            manager.sessionsState.value = listOf(runningSession())
            advanceUntilIdle()
            assertEquals(
                1_234L,
                viewModel.uiState.value.scan.activeProgress
                    ?.scanStartedAtMs,
            )
            collector.cancel()
        }

    @Test
    fun `late attached manual scan completes and the next unsaved session gets a fresh start`() =
        runTest {
            val manager = managerWithRunningScan()
            manager.sessionsState.value = listOf(runningSession())
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val effects = mutableListOf<DiagnosticsEffect>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.collect { effects += it } }
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            runCurrent()
            assertEquals(
                1_234L,
                viewModel.uiState.value.scan.activeProgress
                    ?.scanStartedAtMs,
            )

            manager.progressState.value = null
            advanceUntilIdle()
            runCurrent()
            assertNull(viewModel.uiState.value.scan.activeProgress)
            assertTrue(effects.isEmpty())
            manager.sessionsState.value =
                listOf(runningSession().copy(status = "completed", summary = "Settled result", finishedAt = 2_345L))
            advanceUntilIdle()
            runCurrent()
            assertEquals("Settled result", (effects.single() as DiagnosticsEffect.ScanCompleted).summary)
            assertEquals(DiagnosticsTone.Positive, (effects.single() as DiagnosticsEffect.ScanCompleted).tone)
            manager.sessionsState.value = manager.sessionsState.value.map { it.copy(finishedAt = 2_346L) }
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, effects.size)

            manager.progressState.value = ScanProgress("next-unsaved", "tcp", 0, 8, "Testing TCP")
            advanceUntilIdle()
            runCurrent()
            val nextStart = requireNotNull(viewModel.uiState.value.scan.activeProgress).scanStartedAtMs
            assertTrue(nextStart > 0L)
            assertNotEquals(1_234L, nextStart)
            assertEquals(1, effects.size)
            collector.cancel()
        }

    @Test
    fun `observed Home stage completion stays quiet after owner release then manual completion is reported`() =
        runTest {
            val manager = managerWithRunningScan()
            manager.sessionsState.value = listOf(runningSession())
            manager.scanController.homeRunActive = true
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val effects = mutableListOf<DiagnosticsEffect>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.collect { effects += it } }
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            runCurrent()

            manager.progressState.value = null
            manager.scanController.homeRunActive = false
            advanceUntilIdle()
            runCurrent()
            assertNull(viewModel.uiState.value.scan.activeProgress)
            assertTrue(effects.isEmpty())

            manager.progressState.value = ScanProgress("next-manual", "tcp", 0, 8, "Testing TCP")
            advanceUntilIdle()
            runCurrent()
            assertNotEquals(1_234L, requireNotNull(viewModel.uiState.value.scan.activeProgress).scanStartedAtMs)
            manager.progressState.value = null
            manager.sessionsState.value =
                listOf(runningSession().copy(id = "next-manual", status = "completed", finishedAt = 2_345L))
            advanceUntilIdle()
            runCurrent()
            assertTrue(effects.single() is DiagnosticsEffect.ScanCompleted)
            collector.cancel()
        }

    @Test
    fun `cancel failure preserves active progress and exposes the failure`() =
        runTest {
            val manager = managerWithRunningScan()
            manager.sessionsState.value = listOf(runningSession())
            manager.scanController.onCancel = { error("Cancellation rejected") }
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            val failure = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }

            viewModel.cancelScan()
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.scan.activeProgress)
            assertEquals(
                1_234L,
                viewModel.uiState.value.scan.activeProgress
                    ?.scanStartedAtMs,
            )
            assertTrue(failure.await() is DiagnosticsEffect.ScanStartFailed)
            val laterEffects = mutableListOf<DiagnosticsEffect>()
            backgroundScope.launch(
                start = CoroutineStart.UNDISPATCHED,
            ) { viewModel.effects.collect { laterEffects += it } }
            manager.scanController.onCancel = { manager.progressState.value = null }
            viewModel.cancelScan()
            advanceUntilIdle()
            runCurrent()
            assertNull(viewModel.uiState.value.scan.activeProgress)
            assertTrue(laterEffects.isEmpty())

            manager.progressState.value = ScanProgress("later-manual", "tcp", 0, 8, "Testing TCP")
            advanceUntilIdle()
            runCurrent()
            manager.progressState.value = null
            manager.sessionsState.value =
                listOf(runningSession().copy(id = "later-manual", status = "completed", finishedAt = 2_345L))
            advanceUntilIdle()
            runCurrent()
            assertTrue(laterEffects.single() is DiagnosticsEffect.ScanCompleted)
            collector.cancel()
        }

    @Test
    fun `successful scan cancellation clears progress without a completed effect`() =
        runTest {
            val manager = managerWithRunningScan()
            manager.sessionsState.value = listOf(runningSession())
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val effects = mutableListOf<DiagnosticsEffect>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.collect { effects += it } }
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            runCurrent()
            viewModel.cancelScan()
            advanceUntilIdle()
            runCurrent()
            assertNull(viewModel.uiState.value.scan.activeProgress)
            assertTrue(effects.isEmpty())
            manager.sessionsState.value = listOf(runningSession().copy(status = "completed", finishedAt = 2_345L))
            advanceUntilIdle()
            runCurrent()
            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `persisted failure for the observed session suppresses a completed effect`() =
        runTest {
            val manager = managerWithRunningScan()
            manager.sessionsState.value = listOf(runningSession())
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val effects = mutableListOf<DiagnosticsEffect>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.collect { effects += it } }
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            runCurrent()
            manager.progressState.value = null
            advanceUntilIdle()
            runCurrent()
            assertNull(viewModel.uiState.value.scan.activeProgress)
            assertTrue(effects.isEmpty())
            manager.sessionsState.value = listOf(runningSession().copy(status = "failed", finishedAt = 2_345L))
            advanceUntilIdle()
            runCurrent()
            assertNull(viewModel.uiState.value.scan.activeProgress)
            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `a new run discards the prior session awaiting terminal settlement`() =
        runTest {
            val manager = managerWithRunningScan()
            manager.sessionsState.value = listOf(runningSession())
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val effects = mutableListOf<DiagnosticsEffect>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.collect { effects += it } }
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            runCurrent()
            manager.progressState.value = null
            advanceUntilIdle()
            runCurrent()
            assertNull(viewModel.uiState.value.scan.activeProgress)
            assertTrue(effects.isEmpty())

            manager.progressState.value = ScanProgress("next-manual", "tcp", 0, 8, "Testing TCP")
            advanceUntilIdle()
            runCurrent()
            assertNotEquals(1_234L, requireNotNull(viewModel.uiState.value.scan.activeProgress).scanStartedAtMs)
            manager.sessionsState.value = listOf(runningSession().copy(status = "completed", finishedAt = 2_345L))
            manager.progressState.value = null
            advanceUntilIdle()
            runCurrent()
            assertTrue(effects.isEmpty())

            manager.sessionsState.value =
                manager.sessionsState.value +
                runningSession().copy(
                    id = "next-manual",
                    status = "completed",
                    summary = "New result",
                    finishedAt = 2_346L,
                )
            advanceUntilIdle()
            runCurrent()
            assertEquals("New result", (effects.single() as DiagnosticsEffect.ScanCompleted).summary)
            collector.cancel()
        }

    @Test
    fun `interstage home ownership rejects commands and terminal release enables them`() =
        runTest {
            val manager =
                managerWithRunningScan().apply {
                    progressState.value = null
                    scanController.homeRunActive = true
                }
            var scanStarts = 0
            manager.scanController.onStartScan = { _, _ ->
                scanStarts += 1
                com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
                    .Started("manual")
            }
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectProfile("strategy")
            viewModel.startRawScan()
            advanceUntilIdle()
            assertEquals(0, scanStarts)
            assertNull(manager.lastActiveProfileId)
            assertEquals("connectivity", viewModel.uiState.value.scan.selectedProfileId)

            manager.scanController.homeRunActive = false
            viewModel.selectProfile("strategy")
            advanceUntilIdle()
            assertEquals("strategy", manager.lastActiveProfileId)
            viewModel.startRawScan()
            advanceUntilIdle()
            assertEquals(1, scanStarts)
            collector.cancel()
        }

    @Test
    fun `profile admission race leaves selection unchanged without an uncaught error`() =
        runTest {
            val manager = managerWithRunningScan().apply { progressState.value = null }
            manager.scanController.onSetActiveProfile = {
                throw DiagnosticsScanStartRejectedException(DiagnosticsScanStartRejectionReason.ScanAlreadyActive)
            }
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.selectProfile("strategy")
            advanceUntilIdle()
            assertEquals("connectivity", viewModel.uiState.value.scan.selectedProfileId)
            collector.cancel()
        }

    private fun managerWithRunningScan() =
        FakeDiagnosticsManager().apply {
            profilesState.value =
                listOf(
                    profile("connectivity", ScanKind.CONNECTIVITY),
                    profile("strategy", ScanKind.STRATEGY_PROBE),
                )
            progressState.value = ScanProgress("running", "tcp", 2, 8, "Testing TCP")
        }

    private fun profile(
        id: String,
        kind: ScanKind,
    ) = DiagnosticProfile(
        id = id,
        name = id,
        source = "bundled",
        version = 1,
        request = DiagnosticsProfileProjection(kind = kind, family = DiagnosticProfileFamily.GENERAL),
        updatedAt = 1L,
    )

    private fun runningSession() =
        DiagnosticScanSession(
            id = "running",
            profileId = "strategy",
            pathMode = "IN_PATH",
            serviceMode = "VPN",
            status = "running",
            summary = "Testing TCP",
            startedAt = 1_234L,
            finishedAt = null,
        )
}
